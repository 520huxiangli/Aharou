package com.aharou.feature.browser

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 新增浏览器动作（console / wait / press / select / extract）的执行体。
 *
 * 这些动作的轮询与解析逻辑都放在这里，[BrowserUseManager.execute] 只做一行转发，
 * 避免把新的长逻辑塞进已有的大文件（行数棘轮只许降）。
 * [WebView] 是 manager 的公开字段，这里自带一份最小 evaluate 封装即可独立工作。
 */
object BrowserExtras {

    private const val WAIT_POLL_MS = 400L
    private const val DOM_STABLE_POLL_MS = 200L
    private const val DOM_STABLE_QUIET_MS = 500L
    private const val DEFAULT_WAIT_TIMEOUT_MS = 10_000
    private const val MAX_WAIT_TIMEOUT_MS = 60_000
    private const val EVAL_TIMEOUT_MS = 15_000L
    private const val DEFAULT_EXTRACT_LIMIT = 50

    /** 声明写错时回给模型的一句话写法提示。 */
    private const val EXTRACT_HINT =
        "简写字符串是纯 CSS 选择器（取 textContent）；要取属性/HTML/链接用对象形式 {selector, attr, type}；不确定就先 get_backbone"

    /** [BrowserUseManager.execute] 的转发入口：按 action 分派到本对象各实现。 */
    suspend fun run(webView: WebView, input: BrowserActionInput): BrowserActionResult =
        when (input.action) {
            BrowserAction.CONSOLE -> consoleLogs(webView, input.level, input.clearConsole)
            BrowserAction.WAIT -> wait(webView, input)
            BrowserAction.PRESS -> press(webView, input.key)
            BrowserAction.SELECT -> select(webView, input.selector, input.text)
            BrowserAction.EXTRACT -> extract(webView, input)
            else -> BrowserActionResult.error("unsupported browser action: ${input.action.value}")
        }

    // -- Console --

    suspend fun consoleLogs(webView: WebView, level: String?, clear: Boolean): BrowserActionResult =
        BrowserActionResult(text = BrowserConsoleStore.drain(webView, level, clear))

    // -- Wait --

    suspend fun wait(webView: WebView, input: BrowserActionInput): BrowserActionResult {
        val timeout = (input.timeoutMs ?: DEFAULT_WAIT_TIMEOUT_MS).coerceIn(200, MAX_WAIT_TIMEOUT_MS)
        val condition = resolveWaitCondition(input)
            ?: return BrowserActionResult.error("wait requires 'condition', 'selector' or 'text'")
        val ok = if (condition == "domStable") {
            waitForDomStable(webView, timeout)
        } else {
            waitForCondition(webView, condition, timeout)
        }
        return if (ok) {
            BrowserActionResult(text = "Wait satisfied: $condition (within ${timeout}ms)")
        } else {
            BrowserActionResult(text = "Wait timed out after ${timeout}ms: $condition", success = false)
        }
    }

    /**
     * 解析 wait 的等待条件：显式 [BrowserActionInput.condition] 优先（支持 selector= / text= /
     * text*= / domStable），否则回落到已有的 selector / text 参数。
     */
    private fun resolveWaitCondition(input: BrowserActionInput): String? {
        val cond = input.condition?.takeIf { it.isNotBlank() }
        return when {
            cond != null -> cond
            input.selector != null -> "selector=${input.selector}"
            input.text != null -> "text=${input.text}"
            else -> null
        }
    }

    private suspend fun waitForCondition(webView: WebView, condition: String, timeoutMs: Int): Boolean {
        val checkJs = BrowserUseJS.waitCondition(condition)
        val wrapped = "(function(){try{$checkJs}catch(e){return false}})()"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (evaluate(webView, wrapped).trim('"') == "true") return true
            delay(WAIT_POLL_MS)
        }
        return false
    }

    private suspend fun waitForDomStable(webView: WebView, timeoutMs: Int): Boolean {
        val sigJs = "(function(){var b=document.body||document.documentElement;" +
            "return b?b.getElementsByTagName('*').length+':'+b.innerHTML.length:'0:0'})()"
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val sig = evaluate(webView, sigJs).trim('"')
            val now = System.currentTimeMillis()
            if (sig.isNotEmpty() && sig == last) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= DOM_STABLE_QUIET_MS) return true
            } else {
                last = sig
                stableSince = 0L
            }
            delay(DOM_STABLE_POLL_MS)
        }
        return false
    }

    // -- Press / Select --

    suspend fun press(webView: WebView, key: String?): BrowserActionResult {
        if (key.isNullOrBlank()) return BrowserActionResult.error("press requires 'key'")
        val raw = evaluate(webView, BrowserUseJS.press(key))
        return if (raw.trim('"') == "true") {
            BrowserActionResult(text = "Pressed key: $key")
        } else {
            BrowserActionResult.error("press failed: $key")
        }
    }

    suspend fun select(webView: WebView, selector: String?, value: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("select requires 'selector'")
        if (value == null) return BrowserActionResult.error("select requires 'text' (option value or label)")
        val raw = evaluate(webView, BrowserUseJS.selectOption(selector, value))
        val json = try { JSONObject(raw) } catch (_: Exception) { null }
        return when {
            json == null -> BrowserActionResult.error("select failed")
            json.optBoolean("matched") -> BrowserActionResult(
                text = "Selected '${json.optString("text")}' (value=${json.optString("value")}) in <select>",
            )
            else -> BrowserActionResult.error("select not applied: ${json.optString("reason")}")
        }
    }

    // -- Extract（声明式抽取） --

    /**
     * 把字段声明编译成零参自执行 JS 注入页面执行。
     *
     * 模型只交声明、不写 JS：选择器与属性名全走 [jsLiteral] 的 JSON 字面量注入，
     * 所以非法选择器只会在页面侧让 querySelector 报错（被收进 bad），不会变成可执行代码。
     */
    suspend fun extract(webView: WebView, input: BrowserActionInput): BrowserActionResult {
        val fields = input.extractFields?.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("extract requires 'fields'（例：{\"title\":\"h1\"}）")
        val limit = (input.extractLimit ?: DEFAULT_EXTRACT_LIMIT).coerceAtLeast(1)
        val js = "(function(){var spec=JSON.parse(" +
            jsLiteral(extractSpec(input.extractFrom, input.extractMultiple, fields, limit)) +
            ");" + EXTRACT_JS_BODY + "})()"
        val raw = evaluate(webView, js)
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("extract failed: 页面返回的不是 JSON")
        // ok:false（非法选择器等）是可恢复的提示，不算调用失败：标成 error 会让模型以为调用挂了去重试，
        // 而不是照 hint 改写声明。真正算失败的只有上面「页面没回 JSON」那条。
        return BrowserActionResult(text = json.toString(2), success = true)
    }

    /** 字段声明本身也是一份 JSON，整份序列化后再注入，属性名同样不用拼进 JS。 */
    private fun extractSpec(
        from: String?,
        multiple: Boolean,
        fields: Map<String, ExtractField>,
        limit: Int,
    ): String {
        val fieldArray = JSONArray()
        fields.forEach { (name, field) ->
            fieldArray.put(
                JSONObject()
                    .put("name", name)
                    .put("selector", field.selector)
                    .put("type", field.type)
                    .put("attr", field.attr ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("from", from ?: JSONObject.NULL)
            .put("multiple", multiple)
            .put("limit", limit)
            .put("fields", fieldArray)
            .put("hint", EXTRACT_HINT)
            .toString()
    }

    /**
     * 任意字符串 → 可安全嵌入 JS 的双引号字面量。
     *
     * JSON 转义之外还要额外转义 < > U+2028 U+2029：前两者能提前闭合 <script>，
     * 后两者在旧引擎里被当成换行符，JSON 自身都不处理。
     */
    private fun jsLiteral(value: String): String = JSONObject.quote(value)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /** 抽取动作的 JS 主体；spec 由调用方以 JSON 字面量注入，不进入本段源码。 */
    private val EXTRACT_JS_BODY: String = """
        var bad=[];
        function __badSelector(s){try{document.querySelector(s);return false;}catch(e){return true;}}
        if(spec.from&&__badSelector(spec.from))bad.push(String(spec.from));
        for(var i=0;i<spec.fields.length;i++){if(__badSelector(spec.fields[i].selector))bad.push(String(spec.fields[i].selector));}
        if(bad.length){return JSON.stringify({ok:false,error:'invalid CSS selector: '+bad.join(', '),bad:bad,hint:spec.hint});}
        var containers;
        if(spec.from){containers=spec.multiple?Array.prototype.slice.call(document.querySelectorAll(spec.from)):[document.querySelector(spec.from)];}
        else{containers=[document];}
        var kept=[],total=0;
        for(var j=0;j<containers.length;j++){if(!containers[j])continue;total++;if(kept.length<spec.limit)kept.push(containers[j]);}
        function __read(el,f){
            var node=null;
            try{node=el.querySelector(f.selector);}catch(e){return null;}
            if(!node)return null;
            if(f.type==='html')return node.innerHTML;
            if(f.type==='href')return node.href||node.getAttribute('href')||null;
            if(f.type==='attr')return f.attr?node.getAttribute(f.attr):null;
            return (node.textContent||'').trim();
        }
        var records=[];
        for(var k=0;k<kept.length;k++){
            var rec={};
            for(var m=0;m<spec.fields.length;m++){var f=spec.fields[m];rec[f.name]=__read(kept[k],f);}
            records.push(rec);
        }
        return JSON.stringify({ok:true,count:records.length,records:records,total:total,truncated:total>records.length,limit:spec.limit});
    """.trimIndent()

    // -- Evaluate --

    private suspend fun evaluate(webView: WebView, js: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavascript(js) { result ->
            val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                try { JSONObject("{\"v\":$result}").getString("v") } catch (_: Exception) { result }
            } else {
                result ?: "null"
            }
            deferred.complete(unquoted)
        }
        withTimeoutOrNull(EVAL_TIMEOUT_MS) { deferred.await() } ?: "null"
    }
}
