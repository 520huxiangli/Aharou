package com.aharou.feature.plugin.presentation

import com.aharou.feature.plugin.domain.InstalledPlugin
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.dsl.ComposeDslParser
import com.aharou.feature.plugin.dsl.ComposeDslRenderResult
import com.aharou.feature.plugin.runtime.PluginProcessException
import com.aharou.feature.plugin.runtime.PluginRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 面板首次打开的结果：解析出的路线 ID 与第一棵树。 */
internal data class PluginDslOpened(
    val routeId: String,
    val result: ComposeDslRenderResult
)

/**
 * Compose DSL 面板与插件进程之间的调用面。
 *
 * 协议与 [PluginWebBridge.invokePlugin] 同一条通道：走插件进程的 `ui.invoke`，由
 * `operit-shim.js` 内置的 `ui.routes` / `ui.render` / `ui.dispatch` 接手；宿主不解析页面脚本。
 * 面板没有 WebView，所以也不需要页面桥的文件订阅、主题与生命周期部分。
 */
internal class PluginDslBridge(
    private val pluginId: String,
    private val pluginManager: PluginManager,
    private val pluginRuntimeManager: PluginRuntimeManager
) {

    /** 已加租约的插件版本；版本变了要换一把，否则旧版本进程会一直被跳过回收。 */
    private var leasedVersion: String? = null
    private var leaseRelease: (() -> Unit)? = null

    /** 打开面板：按清单里的 screen 找到插件注册的路线，再渲染第一棵树。 */
    suspend fun open(entryId: String, screen: String): PluginDslOpened {
        val plugin = activePlugin()
        ensureLease(plugin.record.version)
        val routeId = resolveRouteId(listRoutes(plugin), entryId, screen)
        return PluginDslOpened(routeId, parse(invoke(plugin, HANDLER_RENDER, routeArgs(routeId))))
    }

    /** 派发一次动作；返回的树已经是 handler 执行后重新渲染的结果。 */
    suspend fun dispatch(routeId: String, actionId: String, payload: Any?): ComposeDslRenderResult {
        val plugin = activePlugin()
        ensureLease(plugin.record.version)
        val args = buildJsonObject {
            put("routeId", routeId)
            put("actionId", actionId)
            if (payload != null) put("payload", toJsonElement(payload))
        }
        return parse(invoke(plugin, HANDLER_DISPATCH, args))
    }

    /**
     * 按 routeId 直接渲染：v2 的浮动窗口给的就是完整 route，没有入口 id 与脚本路径。
     */
    suspend fun openByRoute(routeId: String): ComposeDslRenderResult {
        val plugin = activePlugin()
        ensureLease(plugin.record.version)
        return parse(invoke(plugin, HANDLER_RENDER, routeArgs(routeId)))
    }

    /**
     * 网页里的 JS 接口调用：转给插件进程执行，返回结果文本。
     *
     * 失败一律抛出去 —— 页面侧拿到的 Promise 必须 reject，不能让插件以为调用成功。
     */
    suspend fun callWebJs(
        routeId: String,
        controllerId: String,
        interfaceName: String,
        method: String,
        argsJson: String
    ): String {
        val plugin = activePlugin()
        ensureLease(plugin.record.version)
        val args = buildJsonObject {
            put("controllerId", controllerId)
            put("interfaceName", interfaceName)
            put("method", method)
            put("argsJson", argsJson)
        }
        val result = invoke(plugin, HANDLER_JS_BRIDGE, args)
        return when (result) {
            is kotlinx.serialization.json.JsonPrimitive -> result.content
            is kotlinx.serialization.json.JsonNull -> ""
            else -> result.toString()
        }
    }

    /** 面板关闭时释放保活，进程回到正常空闲回收。 */
    @Synchronized
    fun close() {
        leaseRelease?.invoke()
        leaseRelease = null
        leasedVersion = null
    }

    private suspend fun listRoutes(plugin: InstalledPlugin): List<PluginDslRoute> {
        val payload = invoke(plugin, HANDLER_ROUTES, JsonObject(emptyMap()))
        val routes = (payload as? JsonObject)?.get("routes") as? JsonArray ?: return emptyList()
        return routes.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("id") ?: return@mapNotNull null
            PluginDslRoute(id = id, screen = obj.stringOrNull("screen"))
        }
    }

    /**
     * 清单只声明页面脚本路径，路线 ID 由插件自己 `registerUiRoute` 决定。
     * 依次按入口 ID、脚本路径匹配；插件只注册了一条路线时直接用它，避免路径写法
     * （`ui/x.js` / `./ui/x.js`）不同就让面板打不开。
     */
    private fun resolveRouteId(routes: List<PluginDslRoute>, entryId: String, screen: String): String {
        routes.firstOrNull { it.id == entryId }?.let { return it.id }
        val wanted = normalizeScreenPath(screen)
        routes.firstOrNull { it.screen?.let(::normalizeScreenPath) == wanted }?.let { return it.id }
        // 插件的 route id 通常取自入口文件所在的目录名，而入口路径是导入时按包内文件拼的：
        // dist/ui/chat-input-memory/index.ui.js 对应注册的 id 就是 chat-input-memory。
        // 从最内层目录往外比，先命中先得。
        wanted.split('/')
            .filter { it.isNotBlank() && !it.contains('.') }
            .asReversed()
            .forEach { segment -> routes.firstOrNull { it.id == segment }?.let { return it.id } }
        if (routes.size == 1) return routes.first().id
        throw PluginHostException(
            "ROUTE_NOT_FOUND",
            if (routes.isEmpty()) {
                "plugin registered no compose route: $pluginId"
            } else {
                "no compose route matches entry $entryId ($screen)"
            }
        )
    }

    private fun normalizeScreenPath(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").trimStart('/')

    private suspend fun invoke(plugin: InstalledPlugin, handler: String, args: JsonObject): JsonElement {
        val runtime = plugin.content.runtime
            ?: throw PluginHostException("RUNTIME_UNAVAILABLE", "plugin has no runtime: $pluginId")
        return withContext(Dispatchers.IO) {
            try {
                pluginRuntimeManager.invoke(
                    pluginId = plugin.id,
                    version = plugin.record.version,
                    runtime = runtime,
                    handler = handler,
                    args = args,
                    method = METHOD_INVOKE,
                    timeoutMs = DSL_TIMEOUT_MS
                )
            } catch (e: PluginProcessException) {
                throw PluginHostException(e.code, e.message)
            }
        }
    }

    private fun parse(element: JsonElement): ComposeDslRenderResult =
        ComposeDslParser.parseRenderResult(element.toString())
            ?: throw PluginHostException("RENDER_EMPTY", "compose dsl render returned no tree: $pluginId")

    private fun routeArgs(routeId: String): JsonObject = buildJsonObject { put("routeId", routeId) }

    @Synchronized
    private fun ensureLease(version: String) {
        if (leasedVersion == version && leaseRelease != null) return
        leaseRelease?.invoke()
        leasedVersion = version
        leaseRelease = pluginRuntimeManager.acquireLease(pluginId, version)
    }

    private fun activePlugin(): InstalledPlugin {
        pluginManager.ensureLoaded()
        return pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    /** 渲染层回传的值只有基本类型与 JSON 结构，转成 JSON-RPC 参数即可。 */
    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), toJsonElement(item))
            }
        }
        is Iterable<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private data class PluginDslRoute(val id: String, val screen: String?)

    private companion object {
        // shim 只接受 ui.invoke，具体入口由 handler 名区分。
        const val METHOD_INVOKE = "ui.invoke"
        const val HANDLER_ROUTES = "ui.routes"
        const val HANDLER_JS_BRIDGE = "ui.jsbridge"
        const val HANDLER_RENDER = "ui.render"
        const val HANDLER_DISPATCH = "ui.dispatch"
        const val DSL_TIMEOUT_MS = 60_000L
    }
}
