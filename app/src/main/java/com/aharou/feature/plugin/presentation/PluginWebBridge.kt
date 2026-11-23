package com.aharou.feature.plugin.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.session.AgentRunEvent
import com.aharou.feature.agent.domain.session.AgentRunStateStore
import com.aharou.feature.plugin.domain.PluginHostApiOrigin
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.PluginPage
import com.aharou.feature.plugin.runtime.PluginEventBus
import com.aharou.feature.plugin.runtime.PluginHostApiDispatcher
import com.aharou.feature.plugin.runtime.PluginModelService
import com.aharou.feature.plugin.runtime.PluginProcessException
import com.aharou.feature.plugin.runtime.PluginRuntimeManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/**
 * 插件 HTML 页面与宿主之间的桥。
 *
 * 页面通过 `__aharouBridge.postMessage(json)` 发请求，宿主在 IO 线程处理，
 * 再用 `window.__aharouResolve(id, payloadJson)` 回传。路径解析与限制由
 * [PluginHostPaths] 统一提供，与插件进程的 host API 共用同一套规则。
 *
 * 桥只由宿主注入到已启用插件的页面；消息可能来自 WebMessageListener 回调或兼容回退的
 * JavaBridge 线程，处理统一切到 IO，不能在这里直接操作 UI。
 */
class PluginWebBridge(
    private val context: Context,
    private val page: PluginPage,
    private val pluginVersion: String,
    private val languageTag: String,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val eventBus: PluginEventBus,
    private val pluginManager: PluginManager,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val modelService: PluginModelService,
    private val agentRunStateStore: AgentRunStateStore,
    private val hostApiDispatcher: PluginHostApiDispatcher,
    private val environment: PluginPageEnvironment,
    @Volatile private var theme: () -> String,
    @Volatile private var onClosePage: () -> Unit,
    @Volatile private var post: (String) -> Unit,
    /** 页面绑定的入口上下文；缓存复用时由页面宿主刷新。 */
    @Volatile private var pageContext: () -> PluginEntryContext = { PluginEntryContext() },
    @Volatile private var lastPageContext: PluginEntryContext? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installation = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId }?.record
    internal val isActive: Boolean get() = isAuthorized()
    private val hostPaths = PluginHostPaths(
        fileAccess,
        homeResolver,
        environment::workspaceRoot,
        page.pluginId
    )
    @Volatile private var overlayActive = false
    @Volatile private var visible = false
    @Volatile private var closed = false
    @Volatile private var contextKey = environment.contextKey()
    private val fileSubscriptions = PluginFileSubscriptions(
        scope = scope,
        watch = { path, recursive -> environment.watch(hostPaths, path, recursive) },
        emit = { payload -> postEvent("fs.changed", payload) }
    )
    private val eventSubscription = eventBus.subscribe(page.pluginId) { event ->
        postEvent(event)
    }
    private val runSubscription = scope.launch {
        agentRunStateStore.events.collect { event ->
            when (event) {
                is AgentRunEvent.Started -> postEvent("chat.run.started", buildJsonObject {
                    put("sessionId", event.sessionId)
                    put("startedAt", event.startedAt)
                })
                is AgentRunEvent.Finished -> postEvent("chat.run.finished", buildJsonObject {
                    put("sessionId", event.sessionId)
                    put("status", event.status)
                    event.error?.let { put("error", it) }
                    put("finishedAt", event.finishedAt)
                })
            }
        }
    }

    init {
        // 状态发布时立即关闭旧桥，避免快速停用再启用被 StateFlow 合并后恢复旧授权。
        scope.launch(Dispatchers.Unconfined) {
            pluginManager.plugins.collect {
                if (!isAuthorized()) {
                    val wasVisible = visible
                    close()
                    mainHandler.post {
                        PluginWebViewCache.invalidateBridge(this@PluginWebBridge)
                        if (wasVisible) onClosePage()
                    }
                }
            }
        }
        scope.launch {
            environment.changes.collect {
                val key = environment.contextKey()
                if (contextKey != key) {
                    contextKey = key
                    fileSubscriptions.setActive(visible, restart = true)
                    postLifecycle("contextChanged")
                }
            }
        }
    }

    @JavascriptInterface
    fun postMessage(payload: String) {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
        val requestContext = environment.contextKey()
        scope.launch {
            val reply = try {
                if (method.startsWith("fs.") && method != "fs.unwatch") {
                    val expected = (params["contextKey"] as? JsonPrimitive)?.contentOrNull
                    if (requestContext != environment.contextKey() ||
                        (expected != null && expected != requestContext)
                    ) {
                        throw PluginHostException("STALE_CONTEXT", "page context has changed")
                    }
                }
                val data = dispatch(method, params)
                if (method in setOf("fs.read", "fs.list", "fs.stat") &&
                    requestContext != environment.contextKey()
                ) {
                    throw PluginHostException("STALE_CONTEXT", "page context has changed")
                }
                buildJsonObject {
                    put("ok", true)
                    put("data", data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginHostException) {
                errorReply(e.code, e.message)
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件页面桥调用失败: ${page.pluginId} $method", e)
                errorReply("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            deliver(id, reply)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        visible = false
        modelService.closeOrigin("page:${page.cacheKey}")
        fileSubscriptions.close()
        updateOverlay(false, null)
        eventSubscription()
        scope.cancel()
    }

    fun setVisible(value: Boolean, reason: String) {
        if (closed || (!value && !visible)) return
        visible = value
        val key = environment.contextKey()
        val changed = contextKey != key
        contextKey = key
        fileSubscriptions.setActive(value, restart = changed)
        if (changed) postLifecycle("contextChanged")
        postLifecycle(reason)
    }

    /**
     * 插件数据目录在当前执行环境中的真实路径。进程侧同一路径通过 AICODE_DATA_DIR 提供；
     * 本地为容器内 `/…/.aharou/plugins/data/<id>`，远程落在服务器同名目录。
     */
    private fun dataDirPath(): String = PluginHostPaths.dataRoot(homeResolver, page.pluginId)

    private fun postLifecycle(reason: String) {
        postEvent("ui.lifecycle", buildJsonObject {
            put("visible", visible)
            put("reason", reason)
            put("contextKey", contextKey)
        })
    }

    private fun postEvent(name: String, payload: JsonObject) {
        postEvent(buildJsonObject {
            put("name", name)
            put("payload", if (name == "fs.changed" && "contextKey" !in payload) {
                buildJsonObject {
                    payload.forEach { (key, value) -> put(key, value) }
                    put("contextKey", environment.contextKey())
                }
            } else payload)
        })
    }

    private fun postEvent(event: JsonObject) {
        val payload = event["payload"] as? JsonObject
        val key = (payload?.get("contextKey") as? JsonPrimitive)?.contentOrNull
        val contextual = (event["name"] as? JsonPrimitive)?.contentOrNull in setOf("fs.changed", "ui.lifecycle")
        val script = "window.__aharouEvent(${jsString(event.toString())})"
        mainHandler.post {
            if (isAuthorized() && (!contextual || key == null || key == environment.contextKey())) {
                runCatching { post(script) }
            }
        }
    }

    /**
     * WebView 缓存复用时页面持有的是同一个桥实例（注入对象只能在页面加载时出现），
     * 重新进入页面要把回调换成当前组合的。
     */
    fun updateCallbacks(
        theme: () -> String,
        onClosePage: () -> Unit,
        post: (String) -> Unit,
        pageContext: () -> PluginEntryContext = { PluginEntryContext() }
    ) {
        this.theme = theme
        this.onClosePage = onClosePage
        this.post = post
        this.pageContext = pageContext
        val next = pageContext()
        if (lastPageContext != null && lastPageContext != next) postLifecycle("contextChanged")
        lastPageContext = next
    }

    private fun isAuthorized(): Boolean {
        if (closed) return false
        val expected = installation ?: return false
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId && it.isActive }
            ?: return false
        return plugin.record.version == pluginVersion &&
            plugin.record.version == expected.version &&
            plugin.record.installedAt == expected.installedAt &&
            plugin.record.archiveSha256 == expected.archiveSha256 &&
            plugin.record.installRevision == expected.installRevision
    }

    internal suspend fun dispatch(method: String, params: JsonObject): JsonElement {
        if (!isAuthorized()) {
            throw PluginHostException("PLUGIN_INACTIVE", "plugin page is no longer active: ${page.pluginId}")
        }
        return when (method) {
        "context" -> buildJsonObject {
            put("pluginId", page.pluginId)
            put("pageId", page.localId)
            page.slot?.let { put("slot", it) }
            val ctx = pageContext()
            ctx.sessionId?.let { put("sessionId", it) }
            ctx.path?.let { put("path", it) }
            ctx.isDirectory?.let { put("isDirectory", it) }
            ctx.selection?.let { put("selection", it) }
            ctx.language?.let { put("language", it) }
            ctx.lineStart?.let { put("lineStart", it) }
            ctx.lineEnd?.let { put("lineEnd", it) }
            put("version", pluginVersion)
            put("languageTag", languageTag)
            put("theme", theme())
            put("workspaceName", environment.workspaceName())
            put("contextKey", environment.contextKey())
            put("visible", visible)
            put("home", homeResolver.home())
            put("workspace", environment.workspaceRoot())
            put("dataDir", dataDirPath())
        }

        "toast" -> {
            val message = PluginHostPaths.requireString(params, "message")
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            buildJsonObject { put("ok", true) }
        }

        // 让插件拿到某个站点的 cookie（用于网页版模型登录后自动取会话）
        "cookies.get" -> {
            val url = PluginHostPaths.requireString(params, "url")
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url).orEmpty()
            buildJsonObject {
                put("cookie", cookie)
                put("empty", cookie.isBlank())
            }
        }

        "ui.close" -> {
            mainHandler.post { onClosePage() }
            buildJsonObject { put("ok", true) }
        }

        "ui.overlay" -> {
            val visible = (params["visible"] as? JsonPrimitive)?.booleanOrNull
                ?: throw PluginHostException("PARAM_INVALID", "visible required")
            val rect = (params["rect"] as? JsonObject)?.let { readOverlayRect(it) }
            updateOverlay(visible, rect)
            buildJsonObject { put("ok", true) }
        }

        // 文件读写与进程侧共用同一套实现，页面只多出订阅（fs.watch / fs.unwatch）。
        "fs.watch" -> {
            val id = PluginHostPaths.requireString(params, "subscriptionId")
            val path = PluginHostPaths.requireString(params, "path")
            val recursive = params["recursive"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull
                    ?: throw PluginHostException("PARAM_INVALID", "recursive must be a boolean")
            } ?: false
            fileSubscriptions.add(id, path, recursive)
            buildJsonObject { put("subscriptionId", id) }
        }
        "fs.unwatch" -> {
            fileSubscriptions.remove(PluginHostPaths.requireString(params, "subscriptionId"))
            buildJsonObject { put("ok", true) }
        }
        // 页面专属能力到此为止，其余公共能力统一交给分发器处理。
        "plugin.invoke" -> invokePlugin(params)
        else -> hostApiDispatcher.dispatch(
            pluginId = page.pluginId,
            method = method,
            params = params,
            origin = PluginHostApiOrigin.PAGE,
            workspaceRoot = environment::workspaceRoot,
            modelOrigin = "page:${page.cacheKey}"
        )
        }
    }

    /** 页面调用自身插件的常驻进程；不允许跨插件。 */
    private suspend fun invokePlugin(params: JsonObject): JsonElement {
        val handler = PluginHostPaths.requireString(params, "handler").trim()
        if (!HANDLER_NAME.matches(handler)) {
            throw PluginHostException("PARAM_INVALID", "invalid handler")
        }
        val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())
        if (args.toString().length > MAX_INVOKE_ARGS_CHARS) {
            throw PluginHostException("TOO_LARGE", "invoke args too large")
        }
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: ${page.pluginId}")
        val runtime = plugin.content.runtime
            ?: throw PluginHostException("RUNTIME_UNAVAILABLE", "plugin has no runtime")
        return try {
            pluginRuntimeManager.invoke(
                pluginId = page.pluginId,
                version = plugin.record.version,
                runtime = runtime,
                handler = handler,
                args = args,
                method = "ui.invoke",
                timeoutMs = UI_INVOKE_TIMEOUT_MS
            )
        } catch (e: PluginProcessException) {
            throw PluginHostException(e.code, e.message)
        }
    }

    /** 页面弹层开关与位置；多个页面同时打开时由控制器逐个挖洞。 */
    @Synchronized
    private fun updateOverlay(visible: Boolean, rect: PluginOverlayController.CssRect?) {
        if (!visible && !overlayActive) return
        overlayActive = visible
        PluginOverlayController.setOverlay(page.cacheKey, visible, rect)
    }

    private fun readOverlayRect(obj: JsonObject): PluginOverlayController.CssRect? {
        fun value(key: String): Float? = (obj[key] as? JsonPrimitive)?.floatOrNull
        val left = value("left") ?: return null
        val top = value("top") ?: return null
        val width = value("width") ?: return null
        val height = value("height") ?: return null
        if (width <= 0f || height <= 0f) return null
        return PluginOverlayController.CssRect(
            left = left,
            top = top,
            width = width,
            height = height,
            topRadius = value("topRadius") ?: 0f,
            bottomRadius = value("bottomRadius") ?: 0f,
            coversBottom = (obj["coversBottom"] as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    private fun errorReply(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun deliver(id: String, payload: JsonObject) {
        val script = "window.__aharouResolve(${jsString(id)}, ${jsString(payload.toString())})"
        // 预热时 WebView 还没 attach，View.post 会一直排队；直接走主线程 Handler，不依赖窗口。
        mainHandler.post { if (isAuthorized()) post(script) }
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val TAG = "PluginWebBridge"
        const val UI_INVOKE_TIMEOUT_MS = 60_000L
        const val MAX_INVOKE_ARGS_CHARS = 256 * 1024
        val HANDLER_NAME = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    }
}
