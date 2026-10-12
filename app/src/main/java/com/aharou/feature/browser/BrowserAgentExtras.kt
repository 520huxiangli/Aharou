package com.aharou.feature.browser

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 新增浏览器动作（console / wait / press / select）的执行体。
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

    /** [BrowserUseManager.execute] 的转发入口：按 action 分派到本对象各实现。 */
    suspend fun run(webView: WebView, input: BrowserActionInput): BrowserActionResult =
        when (input.action) {
            BrowserAction.CONSOLE -> consoleLogs(webView, input.level, input.clearConsole)
            BrowserAction.WAIT -> wait(webView, input)
            BrowserAction.PRESS -> press(webView, input.key)
            BrowserAction.SELECT -> select(webView, input.selector, input.text)
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
