package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个 MCP server 的会话客户端：负责握手、列举工具、转发工具调用，以及资源列举/读取/订阅。
 *
 * 生命周期：[connect] 完成 initialize → notifications/initialized → tools/list 后，
 * [tools] 即为该 server 暴露的工具描述符；[callTool] 把一次调用转成 `tools/call`。
 * 任何一步失败都抛 [McpException]，由上层（McpManager）按 server 粒度兜底，不影响其它 server。
 *
 * 资源能力是显式按需使用的：这条 client **不会主动订阅任何东西**，[subscribeResource] 只由上层
 * （用户配置的自动化任务）显式调用；server 推来的通知按不可信数据处理（只取 uri 做匹配）。
 */
class McpClient(
    val serverName: String,
    private val transport: McpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private companion object {
        const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-06-18"

        /** server 声明资源变更时用的通知方法名。 */
        const val RESOURCE_UPDATED_METHOD = "notifications/resources/updated"

        /** `resources/list` 翻页上限：防 server 给出循环游标时无限请求。 */
        const val MAX_RESOURCE_PAGES = 20
    }

    /**
     * 构造时就挂上传输层的通知回调，而不是等上层第一次订阅才挂：
     * 握手期间 server 也可能推 `notifications/resources/list_changed`，晚挂会丢。
     * 没有监听者时这里只落一条 debug 日志，行为与改造前一致。
     */
    init {
        transport.setNotificationHandler { method, params -> onServerMessage(method, params) }
    }

    @Volatile
    var tools: List<McpToolDescriptor> = emptyList()

    /** initialize 返回的 server 能力对象；未握手或返回畸形时为 null。 */
    @Volatile
    private var serverCapabilities: JsonObject? = null

    /** 当前订阅中的资源 URI。仅供诊断与断线后重建订阅参考：订阅不跨会话，重连后必须重新订阅。 */
    private val subscribedUris: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** 资源变更回调；set 语义（整体替换），见 [setResourceUpdatedListener]。 */
    @Volatile
    private var resourceUpdatedListener: ((uri: String) -> Unit)? = null

    /** 底层传输是否还可用。工具重连时靠它判断「表里挂着但已死」的连接。 */
    val isOpen: Boolean get() = transport.isOpen

    /** 完整握手：initialize → initialized 通知 → tools/list。返回拿到的工具数。 */
    suspend fun connect(): Int {
        FileLogger.i(TAG, "[$serverName] 开始连接")
        initialize()
        transport.notify("notifications/initialized")
        val count = refreshTools()
        FileLogger.i(TAG, "[$serverName] 握手完成，发现 $count 个工具")
        return count
    }

    private suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { /* 客户端暂不声明额外能力 */ }
            putJsonObject("clientInfo") {
                put("name", "ai-code-editor")
                put("version", "1.0.0")
            }
        }
        FileLogger.i(TAG, "[$serverName] 发送 initialize（协议 v$PROTOCOL_VERSION）")
        val t0 = System.currentTimeMillis()
        val response = transport.request("initialize", params)
        // capabilities 决定上层能不能用 resources/*：存下来供能力探测，格式不合规就当没声明，
        // 但绝不因为解析失败而阻断握手（否则一个不规范的 server 会直接连不上）。
        val capabilities = runCatching { response.result?.get("capabilities") as? JsonObject }.getOrNull()
        serverCapabilities = capabilities
        val caps = capabilities?.keys?.sorted()?.joinToString(",").orEmpty()
        FileLogger.i(TAG, "[$serverName] initialize 完成（${System.currentTimeMillis() - t0}ms），能力: [${caps.ifEmpty { "未声明" }}]")
    }

    /** 重新拉取工具列表（配置变更或手动刷新时调用）。 */
    suspend fun refreshTools(): Int {
        FileLogger.i(TAG, "[$serverName] 拉取工具列表 tools/list")
        val t0 = System.currentTimeMillis()
        val response = transport.request("tools/list")
        val result = response.result ?: throw McpException(message = "[$serverName] tools/list 无 result")
        tools = runCatching {
            json.decodeFromJsonElement(McpToolsListResult.serializer(), result).tools
        }.getOrElse {
            throw McpException(message = "[$serverName] 解析工具列表失败: ${it.message}", cause = it)
        }
        FileLogger.i(TAG, "[$serverName] tools/list 完成，${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
        return tools.size
    }

    // ---- 资源能力（resources/*） ----

    /** server 是否声明了 resources 能力（initialize 结果的 `capabilities.resources`）。 */
    val supportsResources: Boolean get() = resourcesCapability() != null

    /**
     * server 是否明确声明支持 `resources/subscribe`。
     *
     * 很多 server 声明了 resources 却省略 subscribe 字段（规范默认 false），所以这里只表达
     * 「确定支持」，[subscribeResource] 在字段缺失时会先尝试再报错，而不是仅凭这个值就拒绝。
     */
    val supportsResourceSubscribe: Boolean
        get() = (resourcesCapability()?.get("subscribe") as? JsonPrimitive)?.booleanOrNull == true

    private fun resourcesCapability(): JsonObject? = serverCapabilities?.get("resources") as? JsonObject

    /**
     * 列举 server 暴露的资源（`resources/list`）。
     *
     * 按规范跟随 nextCursor 翻页（上限 [MAX_RESOURCE_PAGES] 页），按 uri 去重：
     * 静默返回半截清单比直接报错更难查。
     */
    suspend fun listResources(): List<McpResource> {
        requireResourcesSupported("resources/list")
        val all = LinkedHashMap<String, McpResource>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
            val response = transport.request("resources/list", params)
            val result = response.result
                ?: throw McpException(message = "[$serverName] resources/list 无 result")
            val page = runCatching {
                json.decodeFromJsonElement(McpResourceListResult.serializer(), result)
            }.getOrElse {
                throw McpException(message = "[$serverName] 解析资源列表失败: ${it.message}", cause = it)
            }
            page.resources.forEach { resource -> all.putIfAbsent(resource.uri, resource) }
            val next = page.nextCursor
            // 游标原地打转（server 实现 bug）立刻停手，不陪它无限请求。
            cursor = if (next.isNullOrBlank() || next == cursor) null else next
            pages++
        } while (cursor != null && pages < MAX_RESOURCE_PAGES)
        FileLogger.i(TAG, "[$serverName] resources/list 完成，${all.size} 个资源（$pages 页）")
        return all.values.toList()
    }

    /**
     * 读取一份资源内容（`resources/read`）：text 块按顺序拼接；blob 块只留一行占位描述，
     * 不把 base64 塞进上下文。没有 contents 视为失败而不是返回空串——空串会让上层把
     * 「读不到」当成「资源是空的」。
     */
    suspend fun readResource(uri: String): String {
        val target = uri.trim()
        if (target.isEmpty()) throw McpException(message = "[$serverName] readResource 需要非空 uri")
        requireResourcesSupported("resources/read")
        val response = transport.request("resources/read", buildJsonObject { put("uri", target) })
        val result = response.result
            ?: throw McpException(message = "[$serverName] resources/read 无 result")
        val contents = runCatching {
            json.decodeFromJsonElement(McpResourceReadResult.serializer(), result)
        }.getOrElse {
            throw McpException(message = "[$serverName] 解析资源内容失败: ${it.message}", cause = it)
        }.contents
        if (contents.isEmpty()) {
            throw McpException(message = "[$serverName] 资源 $target 没有返回任何内容")
        }
        return contents.joinToString("\n") { block ->
            val text = block.text
            val blob = block.blob
            when {
                text != null -> text
                blob != null -> "[binary resource ${block.mimeType ?: "unknown"}, ${blob.length} chars base64]"
                else -> "[empty resource content]"
            }
        }.trimEnd()
    }

    /**
     * 订阅某个资源的变更（`resources/subscribe`），之后变更通过 [setResourceUpdatedListener] 送达。
     *
     * **只能由上层显式调用**（用户配的自动化任务），client 不主动订阅任何东西。
     * 失败/超时直接抛 [McpException]，不在本地记录订阅成功，也不会静默吞掉。
     */
    suspend fun subscribeResource(uri: String) {
        val target = uri.trim()
        if (target.isEmpty()) throw McpException(message = "[$serverName] subscribeResource 需要非空 uri")
        requireResourcesSupported("resources/subscribe")
        // 明确写了 subscribe=false 的 server 不要发注定失败的请求；字段缺失时按未知处理，试了才知道。
        if (resourcesCapability()?.containsKey("subscribe") == true && !supportsResourceSubscribe) {
            throw McpException(message = "[$serverName] 这条 server 声明了 resources.subscribe=false，不支持资源订阅")
        }
        transport.request("resources/subscribe", buildJsonObject { put("uri", target) })
        subscribedUris.add(target)
        FileLogger.i(TAG, "[$serverName] 已订阅资源 $target")
    }

    /**
     * 取消订阅（`resources/unsubscribe`）。对端报错时保留本地记录并原样上抛：
     * 对端可能仍处于订阅状态，把记录清了会让上报的订阅表与事实不符。
     */
    suspend fun unsubscribeResource(uri: String) {
        val target = uri.trim()
        if (target.isEmpty()) throw McpException(message = "[$serverName] unsubscribeResource 需要非空 uri")
        requireResourcesSupported("resources/unsubscribe")
        transport.request("resources/unsubscribe", buildJsonObject { put("uri", target) })
        subscribedUris.remove(target)
        FileLogger.i(TAG, "[$serverName] 已取消订阅资源 $target")
    }

    /** 当前订阅中的资源（只读快照）；重连后旧订阅随会话失效，上层需要重新订阅。 */
    val subscribedResourceUris: Set<String> get() = subscribedUris.toSet()

    /**
     * 注册资源变更回调（`notifications/resources/updated`）。整体替换上一个回调，传 null 注销。
     *
     * 回调只在传输读线程上同步执行，实现里不要做耗时操作（需要就去起协程）。
     * 只有 `params.uri` 会被交出去：notification 是 server 推来的不可信数据，
     * 其它字段（以及可能的文本内容）一律不读、不进日志、不拼进提示词。
     */
    fun setResourceUpdatedListener(listener: ((uri: String) -> Unit)?) {
        resourceUpdatedListener = listener
    }

    /**
     * 能力探测：server 没声明 resources 能力就直接报错，不发一个注定失败的请求，
     * 让上层能给出「这个 server 不能订阅」的明确提示，而不是等到超时。
     */
    private fun requireResourcesSupported(action: String) {
        if (!supportsResources) {
            throw McpException(
                message = "[$serverName] 这条 server 未声明 resources 能力，无法执行 $action"
            )
        }
    }

    /**
     * 传输层收到的 server 主动消息入口。
     *
     * 只消费 `notifications/resources/updated` 的 `uri`；其它方法只记 debug 日志就丢弃——
     * 不认识的推送不能被当成指令去执行，也不能把它的正文当成可信内容带进上层。
     */
    private fun onServerMessage(method: String, params: JsonElement?) {
        if (method != RESOURCE_UPDATED_METHOD) {
            FileLogger.d(TAG, "[$serverName] 收到未处理的 server 通知: $method")
            return
        }
        val uri = ((params as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        if (uri.isNullOrBlank()) {
            FileLogger.w(TAG, "[$serverName] 资源变更通知缺少 uri，已忽略")
            return
        }
        val listener = resourceUpdatedListener
        if (listener == null) {
            FileLogger.d(TAG, "[$serverName] 资源 ${uri.take(200)} 有更新，但没有监听者")
            return
        }
        FileLogger.i(TAG, "[$serverName] 资源变更: ${uri.take(200)}")
        // 监听方抛错不能影响传输读循环（否则一次回调异常就表现成传输掉线）。
        runCatching { listener(uri) }
            .onFailure { FileLogger.w(TAG, "[$serverName] 资源变更回调异常（已忽略）: ${it.message}") }
    }

    /**
     * 执行一次工具调用，返回扁平化为文本的结果（喂回模型）与是否报错。
     * MCP 的 `tools/call` 结果是 content 块数组；这里把 text 块拼接，其它类型给出占位描述。
     * [McpCallResult.isError] 直接取自结果里的 `isError` 字段，避免把文本前缀当判据。
     */
    suspend fun callTool(toolName: String, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val response = transport.request("tools/call", params)
        val result = response.result ?: throw McpException(message = "[$serverName] tools/call 无 result")

        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val text = flattenContent(result["content"])
        return McpCallResult(text, isError, extractImages(result["content"]))
    }

    /**
     * 把 content 数组里的 image 块抽出来（MCP 图片块：{type:"image", data:base64, mimeType}），
     * 供上层转成应用内的图片附件——否则远端截图只能落到 [flattenContent] 的 "[image content]" 占位符。
     */
    private fun extractImages(content: kotlinx.serialization.json.JsonElement?): List<McpContentImage> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "image") return@mapNotNull null
            val data = (obj["data"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (data.isBlank()) return@mapNotNull null
            McpContentImage(
                mimeType = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "image/jpeg",
                base64Data = data
            )
        }
    }

    /**
     * 把 content 数组里的块转成纯文本：text 块取 text；其它块给类型占位
     * （image 块的原始数据另由 [extractImages] 取走，这里只留一行占位说明）。
     */
    private fun flattenContent(content: kotlinx.serialization.json.JsonElement?): String {
        val array = content as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (block in array) {
            val obj = block as? JsonObject ?: continue
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> sb.append((obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                "image" -> sb.append("[image content]")
                "resource" -> sb.append("[resource content]")
                else -> obj["text"]?.let { sb.append((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun close() {
        // 订阅随会话结束失效：本地记录一并清掉，避免上层以为还订着。
        subscribedUris.clear()
        resourceUpdatedListener = null
        transport.close()
    }

    /** 底层连接是否还活着（stdio 看子进程）；false 说明这条 client 已死，需重建。 */
    val isAlive: Boolean get() = transport.isAlive
}

/** 一次 `tools/call` 的结果：扁平化文本 + 是否报错（源自 MCP 结果的 `isError` 字段）+ 图片块。 */
data class McpCallResult(
    val text: String,
    val isError: Boolean,
    val images: List<McpContentImage> = emptyList()
)

/** MCP 结果里的一张图片（base64 + MIME）；由 [McpTool] 转成应用内的 [com.aharou.feature.agent.domain.model.AgentImage]。 */
data class McpContentImage(val mimeType: String, val base64Data: String)
