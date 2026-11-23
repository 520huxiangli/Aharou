package com.aharou.feature.plugin.runtime

import com.aharou.feature.browser.BrowserAction
import com.aharou.feature.browser.BrowserActionInput
import com.aharou.feature.browser.BrowserActionResult
import com.aharou.feature.browser.BrowserTabPool
import com.aharou.feature.browser.UserAgentProfile
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `browser.*` 宿主方法（对应 Operit 的 `Tools.Net.browser*` 与 start/stopBrowser，共 29 个）。
 *
 * 全部落在内置浏览器引擎 [BrowserTabPool] 上：它已经实现了标签页池、动作串行锁与
 * WebView 生命周期，这里只做「v2 参数 → [BrowserActionInput]」的翻译与结果整形，
 * 不另起一套 WebView。
 *
 * 参数一律是扁平命名参数（shim 负责把 v2 的单对象签名摊平）。`browser.navigate` 与
 * `browser.press` 的 v2 签名为「字符串或对象」二选一，这里两种写法都收：
 * `{url}` / `{urlOrOptions: "https://…"}`、`{key}` / `{keyOrOptions: {key}}`。
 *
 * 映射：
 * - start/stop/close/closeAll/tabs/snapshot/screenshot → 标签页与快照动作
 * - navigate/navigateBack/click/type/press/select/hover/resize/waitFor/evaluate/fillForm → 同名动作
 * - console → console；handleDialog → 挂起网页对话框
 * - networkRequests/drag/fileUpload/runCode 与会话用户脚本 5 个方法没有对应能力，明确报 NOT_SUPPORTED
 */
@Singleton
class PluginBrowserService @Inject constructor(
    private val tabPool: BrowserTabPool,
    private val pluginManager: PluginManager
) {

    suspend fun handle(
        pluginId: String,
        method: String,
        params: JsonObject,
        paths: PluginHostPaths
    ): JsonElement {
        ensureActive(pluginId)
        return when (method) {
            "browser.start" -> start(params)
            "browser.stop", "browser.closeAll" -> stop(params)
            "browser.close" -> close()
            "browser.tabs" -> tabs(params)
            "browser.navigate" -> navigate(params)
            "browser.navigateBack" -> navigateBack()
            "browser.click" -> click(params)
            "browser.type" -> type(params)
            "browser.press" -> press(params)
            "browser.select" -> select(params)
            "browser.hover" -> hover(params)
            "browser.resize" -> resize(params)
            "browser.evaluate" -> evaluate(params)
            "browser.waitFor" -> waitFor(params)
            "browser.snapshot" -> snapshot(params, paths)
            "browser.screenshot" -> screenshot(params, paths)
            "browser.console" -> console(params, paths)
            "browser.fillForm" -> fillForm(params)
            "browser.handleDialog" -> handleDialog(params)
            "browser.networkRequests" -> throw PluginHostException(
                "NOT_SUPPORTED",
                "宿主浏览器不记录网络请求日志。需要接口响应或精确报文请用 http.request/httpGet/httpPost。"
            )
            "browser.drag" -> throw PluginHostException(
                "NOT_SUPPORTED",
                "宿主浏览器没有拖拽动作。可用 browser.evaluate 在页面里派发 pointer/mouse 事件自行实现。"
            )
            "browser.fileUpload" -> throw PluginHostException(
                "NOT_SUPPORTED",
                "宿主浏览器没有文件选择器通道，无法把本地文件交给页面 <input type=file>。"
            )
            "browser.runCode" -> throw PluginHostException(
                "NOT_SUPPORTED",
                "宿主没有 Playwright 执行环境，无法运行 Playwright 风格代码；请改用 browser.snapshot + browser.click/browser.type 等原语组合。"
            )
            else -> when {
                method.startsWith("browser.userscript") -> throw PluginHostException(
                    "NOT_SUPPORTED",
                    "宿主暂不支持用户脚本（userscript）：没有脚本安装、启停与注入通道。"
                )
                else -> throw PluginHostException("METHOD_UNKNOWN", "unknown browser host method: $method")
            }
        }
    }

    // -- 会话与标签页 --

    private suspend fun start(params: JsonObject): JsonElement {
        val notes = ArrayList<String>()
        val url = string(params, "url")
        val userAgent = string(params, "user_agent")
        if (params["headers"] != null && !isEmptyValue(params["headers"])) {
            notes += "headers 未应用：宿主浏览器不注入自定义请求头，需要精确报文请用 http.request。"
        }
        if (string(params, "session_name") != null) {
            notes += "session_name 未使用：宿主只有一个浏览器会话（session_id=default）。"
        }
        if (userAgent != null) {
            // UA 要落到池里才会作用于之后创建的标签页，WebView 设置只能在主线程改。
            withContext(Dispatchers.Main) { tabPool.setUserAgentFromUI(UserAgentProfile.CUSTOM, userAgent) }
        }
        val result = tabPool.execute(
            if (url != null) BrowserActionInput(BrowserAction.NAVIGATE, url = url)
            else BrowserActionInput(BrowserAction.NEW_TAB)
        ).orThrow()
        return buildJsonObject {
            put("ok", true)
            put("result", appendNotes(result.text, notes))
            put("session_id", SESSION_ID)
            put("tabs", tabPool.tabs.value.size)
            put("selectedTabId", tabPool.selectedTabId.value)
            result.tabId?.let { put("tabId", it) }
            result.pageURL?.let { put("pageURL", it) }
        }
    }

    private suspend fun stop(params: JsonObject): JsonElement {
        val sessionId = string(params, "session_id")
        if (sessionId != null && sessionId != SESSION_ID) {
            throw PluginHostException(
                "PARAM_INVALID",
                "宿主只有一个浏览器会话（session_id=$SESSION_ID），没有会话：$sessionId"
            )
        }
        val closed = closeAllTabs()
        return buildJsonObject {
            put("ok", true)
            put("result", "已关闭 $closed 个标签页")
            put("session_id", SESSION_ID)
        }
    }

    private suspend fun close(): JsonElement = toJson(tabPool.execute(BrowserActionInput(BrowserAction.CLOSE_TAB)))

    private suspend fun tabs(params: JsonObject): JsonElement {
        val action = string(params, "action")?.lowercase()
            ?: throw paramInvalid("tabs needs 'action'（list / new / close / select）")
        val index = int(params, "index")
        val snapshot = tabPool.tabs.value
        val selected = tabPool.selectedTabId.value
        val targetId = index?.let { snapshot.getOrNull(it)?.id }
        return when (action) {
            "list" -> {
                val items = JsonArray(snapshot.mapIndexed { i, tab ->
                    buildJsonObject {
                        put("index", i)
                        put("tabId", tab.id)
                        put("url", tab.manager.currentURL.value)
                        put("title", tab.manager.pageTitle.value)
                        put("selected", tab.id == selected)
                    }
                })
                buildJsonObject {
                    put("ok", true)
                    put("tabs", items)
                    put("result", if (snapshot.isEmpty()) "No open tabs" else "共 ${snapshot.size} 个标签页")
                }
            }
            "new", "open" -> toJson(tabPool.execute(BrowserActionInput(BrowserAction.NEW_TAB)))
            "close" -> {
                val id = targetId ?: selected
                toJson(tabPool.execute(BrowserActionInput(BrowserAction.CLOSE_TAB, tabId = id)))
            }
            "select", "switch", "activate" -> {
                val id = targetId
                    ?: throw PluginHostException("PARAM_INVALID", "select 需要 index")
                if (snapshot.none { it.id == id }) {
                    throw PluginHostException("PARAM_INVALID", "没有第 ${index} 个标签页")
                }
                tabPool.selectTab(id)
                buildJsonObject {
                    put("ok", true)
                    put("result", "已切换到标签页 $id")
                    put("tabId", id)
                }
            }
            else -> throw paramInvalid("tabs 不支持 action=$action（可用：list / new / close / select）")
        }
    }

    private suspend fun closeAllTabs(): Int {
        var closed = 0
        // 先取快照再逐个关：close 会改写池内列表，边遍历边改会漏掉后面的标签页。
        tabPool.tabs.value.map { it.id }.forEach { id ->
            if (tabPool.execute(BrowserActionInput(BrowserAction.CLOSE_TAB, tabId = id)).success) closed++
        }
        return closed
    }

    // -- 导航与页面动作 --

    private suspend fun navigate(params: JsonObject): JsonElement {
        val notes = ArrayList<String>()
        val source = params["urlOrOptions"] ?: params["url_or_options"]
        val (url, headers) = when (source) {
            is JsonPrimitive -> source.contentOrNull to params["headers"]
            is JsonObject -> string(source, "url") to source["headers"]
            else -> string(params, "url") to params["headers"]
        }
        if (url.isNullOrBlank()) throw paramInvalid("navigate needs 'url'")
        if (headers != null && !isEmptyValue(headers)) {
            notes += "headers 未应用：宿主浏览器不注入自定义请求头。"
        }
        return toJson(tabPool.execute(BrowserActionInput(BrowserAction.NAVIGATE, url = url)), notes)
    }

    private suspend fun navigateBack(): JsonElement = withContext(Dispatchers.Main) {
        val manager = activeManager()
        manager.goBack()
        buildJsonObject {
            put("ok", true)
            put("result", "已执行返回（无历史时页面不变）")
            put("pageURL", manager.currentURL.value)
        }
    }

    private suspend fun click(params: JsonObject): JsonElement {
        val selector = selectorOf(params)
            ?: throw paramInvalid("click needs 'selector' or 'ref'")
        val notes = ArrayList<String>()
        val button = string(params, "button")?.lowercase()
        if (button != null && button != "left") notes += "button=$button 未应用：宿主只派发左键单击。"
        val modifiers = params["modifiers"] as? JsonArray
        if (modifiers != null && modifiers.isNotEmpty()) notes += "modifiers 未应用：宿主不派发组合键点击。"
        if ((params["doubleClick"] as? JsonPrimitive)?.booleanOrNull == true) {
            notes += "doubleClick 未应用：宿主 click 是单击。"
        }
        return toJson(tabPool.execute(BrowserActionInput(BrowserAction.CLICK, selector = selector)), notes)
    }

    private suspend fun type(params: JsonObject): JsonElement {
        val selector = selectorOf(params)
            ?: throw paramInvalid("type needs 'ref' or 'selector'")
        val text = (params["text"] as? JsonPrimitive)?.contentOrNull
            ?: throw paramInvalid("type needs 'text'")
        val notes = ArrayList<String>()
        val result = tabPool.execute(
            BrowserActionInput(BrowserAction.TYPE, selector = selector, text = text)
        ).orThrow()
        if ((params["submit"] as? JsonPrimitive)?.booleanOrNull == true) {
            // 宿主没有「提交表单」原语；输入框里按 Enter 就是浏览器触发提交的方式。
            val pressed = tabPool.execute(BrowserActionInput(BrowserAction.PRESS, key = "Enter"))
            notes += if (pressed.success) "submit 已通过 Enter 触发。" else "submit 失败：${pressed.text}"
        }
        if ((params["slowly"] as? JsonPrimitive)?.booleanOrNull == true) {
            notes += "slowly 未应用：宿主一次性写入文本。"
        }
        return toJson(result, notes)
    }

    private suspend fun press(params: JsonObject): JsonElement {
        val source = params["keyOrOptions"] ?: params["key_or_options"]
        val key = when (source) {
            is JsonPrimitive -> source.contentOrNull
            is JsonObject -> string(source, "key")
            else -> string(params, "key")
        } ?: throw paramInvalid("press needs 'key'")
        return toJson(tabPool.execute(BrowserActionInput(BrowserAction.PRESS, key = key)))
    }

    private suspend fun select(params: JsonObject): JsonElement {
        val selector = selectorOf(params) ?: throw paramInvalid("select needs 'ref' or 'selector'")
        val values = (params["values"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: throw paramInvalid("select needs non-empty 'values'")
        if (values.size == 1) {
            return toJson(
                tabPool.execute(
                    BrowserActionInput(BrowserAction.SELECT, selector = selector, text = values.first())
                )
            )
        }
        // 多选只能逐项选中：宿主的 select 一次只写一个值。
        val done = ArrayList<String>()
        values.forEach { value ->
            val result = tabPool.execute(
                BrowserActionInput(BrowserAction.SELECT, selector = selector, text = value)
            ).orThrow()
            done += result.text
        }
        return buildJsonObject {
            put("ok", true)
            put("result", done.joinToString("\n"))
            put("selected", values.size)
        }
    }

    private suspend fun hover(params: JsonObject): JsonElement {
        val selector = selectorOf(params) ?: throw paramInvalid("hover needs 'ref' or 'selector'")
        return toJson(tabPool.execute(BrowserActionInput(BrowserAction.HOVER, selector = selector)))
    }

    private suspend fun resize(params: JsonObject): JsonElement {
        val width = int(params, "width") ?: throw paramInvalid("resize needs 'width'")
        val height = int(params, "height") ?: throw paramInvalid("resize needs 'height'")
        if (width <= 0 || height <= 0) throw paramInvalid("resize 的 width/height 必须为正数")
        // set_viewport 必须走池：它要统一改所有标签页并持久化会话级覆盖。
        return toJson(
            tabPool.execute(
                BrowserActionInput(
                    BrowserAction.SET_VIEWPORT,
                    viewportWidth = width,
                    viewportHeight = height
                )
            )
        )
    }

    private suspend fun evaluate(params: JsonObject): JsonElement {
        val function = string(params, "function") ?: throw paramInvalid("evaluate needs 'function'")
        val notes = ArrayList<String>()
        if (string(params, "ref") != null) notes += "ref 未应用：宿主 evaluate 在整页作用域执行。"
        return toJson(tabPool.execute(BrowserActionInput(BrowserAction.EXECUTE_JS, script = function)), notes)
    }

    private suspend fun waitFor(params: JsonObject): JsonElement {
        val text = string(params, "text")
        val gone = string(params, "textGone") ?: string(params, "text_gone")
        val seconds = (params["time"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        return when {
            text != null -> toJson(
                tabPool.execute(
                    BrowserActionInput(
                        BrowserAction.WAIT,
                        condition = "text*=$text",
                        timeoutMs = DEFAULT_WAIT_MS
                    )
                )
            )
            gone != null -> waitTextGone(gone)
            seconds != null -> {
                val notes = ArrayList<String>()
                val requested = (seconds * 1000).toLong().coerceAtLeast(0)
                val actual = requested.coerceAtMost(MAX_WAIT_MS.toLong())
                if (actual < requested) notes += "等待已按 ${MAX_WAIT_MS / 1000}s 上限截断。"
                delay(actual)
                buildJsonObject {
                    put("ok", true)
                    put("result", appendNotes("已等待 ${actual}ms", notes))
                }
            }
            else -> throw paramInvalid("waitFor 需要 time / text / textGone 之一")
        }
    }

    private suspend fun waitTextGone(text: String): JsonElement {
        // 宿主没有「文本消失」条件（wait 只支持出现类条件），这里用 execute_js 轮询取反。
        val quoted = org.json.JSONObject.quote(text)
        val script = "var __b=document.body;return !(__b && __b.innerText && __b.innerText.indexOf($quoted)>=0);"
        val deadline = System.currentTimeMillis() + DEFAULT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val seen = tabPool.execute(BrowserActionInput(BrowserAction.EXECUTE_JS, script = script)).orThrow()
            if (seen.text.trim() == "true") {
                return buildJsonObject {
                    put("ok", true)
                    put("result", "文本已消失：$text")
                }
            }
            delay(WAIT_POLL_MS)
        }
        throw PluginHostException("TIMEOUT", "等待文本消失超时（${DEFAULT_WAIT_MS}ms）：$text")
    }

    private suspend fun snapshot(params: JsonObject, paths: PluginHostPaths): JsonElement {
        val notes = ArrayList<String>()
        if (string(params, "selector") != null) notes += "selector 未应用：宿主快照从整页根节点开始。"
        val result = tabPool.execute(
            BrowserActionInput(BrowserAction.GET_BACKBONE, maxDepth = int(params, "depth"))
        ).orThrow()
        val filename = string(params, "filename")
        if (filename != null) {
            paths.write(filename, result.text)
            notes += "快照已写入：$filename"
        }
        return toJson(result, notes)
    }

    private suspend fun screenshot(params: JsonObject, paths: PluginHostPaths): JsonElement {
        val notes = ArrayList<String>()
        if (string(params, "ref") != null || string(params, "element") != null) {
            notes += "ref/element 未应用：宿主整页截图，不按元素裁剪。"
        }
        if (string(params, "type")?.lowercase() == "png") {
            notes += "type=png 未应用：宿主固定输出 JPEG。"
        }
        val fullPage = (params["fullPage"] as? JsonPrimitive)?.booleanOrNull == true
        val result = tabPool.execute(
            BrowserActionInput(BrowserAction.SCREENSHOT, fullPage = fullPage)
        ).orThrow()
        val filename = string(params, "filename")
        val saved = filename?.let { saveImage(result.imageFilePath, it, paths) }
        return buildJsonObject {
            put("ok", true)
            put(
                "result",
                appendNotes(
                    result.text + if (saved == null) "（未落盘：传 filename 可写入插件可访问的路径）" else "，已写入 $saved",
                    notes
                )
            )
            result.tabId?.let { put("tabId", it) }
            result.pageURL?.let { put("pageURL", it) }
            saved?.let { put("imagePath", it) }
        }
    }

    private suspend fun console(params: JsonObject, paths: PluginHostPaths): JsonElement {
        val level = string(params, "level")
        val result = tabPool.execute(
            BrowserActionInput(BrowserAction.CONSOLE, level = level)
        ).orThrow()
        val filename = string(params, "filename")
        val notes = ArrayList<String>()
        if (filename != null) {
            paths.write(filename, result.text)
            notes += "日志已写入：$filename"
        }
        return toJson(result, notes)
    }

    private suspend fun fillForm(params: JsonObject): JsonElement {
        val fields = params["fields"] as? JsonArray
            ?: throw paramInvalid("fillForm needs non-empty 'fields'")
        if (fields.isEmpty()) throw paramInvalid("fillForm needs non-empty 'fields'")
        val done = ArrayList<String>()
        fields.forEachIndexed { index, element ->
            val field = element as? JsonObject
                ?: throw paramInvalid("fields[$index] 必须是对象")
            val name = string(field, "name") ?: "field#$index"
            val kind = string(field, "type")?.lowercase().orEmpty()
            val selector = selectorOf(field)
                ?: throw paramInvalid("fields[$index]（$name）需要 ref 或 selector")
            val value = field["value"]
            val applied = when (kind) {
                "checkbox", "radio", "toggle" -> {
                    if ((value as? JsonPrimitive)?.booleanOrNull == true) {
                        tabPool.execute(BrowserActionInput(BrowserAction.CLICK, selector = selector)).orThrow().text
                    } else {
                        "跳过：宿主不读取勾选状态，无法安全取消勾选"
                    }
                }
                "select", "selectoption", "select-one", "select-multiple" ->
                    tabPool.execute(
                        BrowserActionInput(BrowserAction.SELECT, selector = selector, text = valueText(value))
                    ).orThrow().text
                else -> tabPool.execute(
                    BrowserActionInput(BrowserAction.TYPE, selector = selector, text = valueText(value))
                ).orThrow().text
            }
            done += "$name: $applied"
        }
        return buildJsonObject {
            put("ok", true)
            put("result", done.joinToString("\n"))
            put("filled", done.size)
        }
    }

    private suspend fun handleDialog(params: JsonObject): JsonElement {
        val accept = (params["accept"] as? JsonPrimitive)?.booleanOrNull
            ?: throw paramInvalid("handleDialog needs boolean 'accept'")
        val promptText = string(params, "promptText") ?: string(params, "prompt_text")
        withContext(Dispatchers.Main) {
            activeManager().resolvePendingJsDialog(accept, promptText)
        }
        return buildJsonObject {
            put("ok", true)
            put("result", "已应答当前标签页的挂起对话框（accept=$accept）")
        }
    }

    // -- 辅助 --

    private suspend fun activeManager() =
        tabPool.activeManager ?: throw PluginHostException(
            "NO_BROWSER_SESSION",
            "没有打开的浏览器标签页，先用 browser.start 或 browser.navigate 打开页面。"
        )

    private fun toJson(result: BrowserActionResult, notes: List<String> = emptyList()): JsonElement {
        val ok = result.orThrow()
        return buildJsonObject {
            put("ok", true)
            put("result", appendNotes(ok.text, notes))
            ok.pageURL?.let { put("pageURL", it) }
            ok.tabId?.let { put("tabId", it) }
            ok.fetchedFileName?.let { put("fetchedFileName", it) }
        }
    }

    private fun BrowserActionResult.orThrow(): BrowserActionResult =
        if (success) this else throw PluginHostException("BROWSER_ACTION_FAILED", text)

    private fun appendNotes(text: String, notes: List<String>): String =
        if (notes.isEmpty()) text else text + "\n" + notes.joinToString("\n")

    private fun selectorOf(params: JsonObject): String? {
        string(params, "selector")?.let { return it }
        string(params, "ref")?.let { return "ref=$it" }
        return null
    }

    private fun valueText(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    /** headers 允许写成对象或序列化文本；空值等同于「没给」。 */
    private fun isEmptyValue(value: JsonElement?): Boolean = when (value) {
        null -> true
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        is JsonPrimitive -> value.contentOrNull.isNullOrBlank()
        else -> false
    }

    private fun saveImage(hostPath: String?, filename: String, paths: PluginHostPaths): String? {
        if (hostPath == null) return null
        val bytes = runCatching { File(hostPath).readBytes() }.getOrNull()
            ?: throw PluginHostException("IO_ERROR", "读取宿主截图失败：$hostPath")
        if (bytes.size > PluginHostPaths.MAX_WRITE_BYTES) {
            throw PluginHostException(
                "TOO_LARGE",
                "截图 ${bytes.size} 字节，超过单文件写入上限 ${PluginHostPaths.MAX_WRITE_BYTES} 字节"
            )
        }
        paths.writeBinary(filename, Base64.getEncoder().encodeToString(bytes))
        return filename
    }

    private fun string(params: JsonObject, key: String): String? =
        (params[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(params: JsonObject, key: String): Int? =
        (params[key] as? JsonPrimitive)?.intOrNull

    private fun paramInvalid(message: String) = PluginHostException("PARAM_INVALID", message)

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private companion object {
        /** 宿主只有池里这一个浏览器会话；v2 的 session_id 由它代表。 */
        const val SESSION_ID = "default"
        const val DEFAULT_WAIT_MS = 10_000
        const val MAX_WAIT_MS = 60_000
        const val WAIT_POLL_MS = 300L
    }
}
