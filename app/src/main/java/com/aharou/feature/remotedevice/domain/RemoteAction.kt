package com.aharou.feature.remotedevice.domain

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 一个远端可调用的动作，以及它在本机对应的「手」。
 *
 * 设计要点：动作本身不实现任何能力，只声明「映射到本机哪个 [ToolRegistry] 工具 + 允许透传哪些参数」。
 * 真正执行走本机工具自己的实现，权限走本机策略引擎与 [com.aharou.feature.agent.domain.tool.ToolPermissionManager]，
 * 因此远端的写入/命令/截图与本机 agent 用的是同一套语义与审计，不存在第二套旁路。
 *
 * 能力位（[RemoteActionKind]）对齐 nanoMuse hub 的动作模型（`info`/`files`/`shell`/`screen`），
 * 但按 MCP 工具的粒度拆分：参考实现里一个 `files` 动作承载多形态（read/get/put），
 * 这里拆成 read_file / write_file，让「只读」与「可写」在声明层面就能分开授权与审计。
 */
data class RemoteAction(
    val name: String,
    val kind: RemoteActionKind,
    val description: String,
    /** 本机 [ToolRegistry] 中的工具名；null 表示由本包内置实现（仅 info）。 */
    val toolName: String?,
    /** 允许透传的参数名白名单：不在其中的参数一律丢弃（如 Bash 的 `elevate`/`background` 绝不透传）。 */
    val allowedArgs: Set<String>,
    /** 执行前强制注入的参数（对端无法覆盖或省略）。 */
    val forcedArgs: Map<String, String> = emptyMap(),
    /**
     * 引擎判定自动放行时，是否仍升级为一次审批（远端来源默认升级，见 [RemoteDeviceConfig.alwaysAskRemote]）。
     * info 不升级：它只回设备名/版本/能力清单，不含任何用户数据。
     */
    val escalateWhenAllowed: Boolean = true,
    /**
     * 内置动作（没有对应本机工具）自带的参数声明。
     *
     * 参数 schema 平时从本机工具的 `parameters` 过滤而来；内置动作没有那个来源，
     * 少了这份声明，对端看到的 schema 会是空对象、连 path 都传不进来。
     */
    val inlineParams: Map<String, ToolParameter> = emptyMap()
)

/** 动作的能力位：写进 info 的能力清单，也用于审计时区分「这条远端调用动到了什么」。 */
enum class RemoteActionKind(val capability: String) {
    INFO("info"),
    READ_FILE("read"),
    WRITE_FILE("write"),
    EXECUTE_COMMAND("exec"),
    SCREENSHOT("screen"),
    MESSAGE("message")
}

/**
 * 动作清单 + MCP `tools/list` 描述。
 *
 * 参数 schema 直接从本机工具的 `parameters` 过滤而来（而不是自己另写一份），
 * 保证「对端看到的参数」与「真正执行的参数」永远一致。
 * Bash 的 `timeout` 参数被钳到 [RemoteActionCatalog.REMOTE_SHELL_MAX_TIMEOUT_SECONDS]：
 * 调用方 Aharou 的 MCP client 有 120 秒 callTimeout（见 di/AgentModule.kt 的 Named("Mcp")），
 * 单次远端调用必须留出审批时间且不能超过它。
 */
@Singleton
class RemoteActionCatalog @Inject constructor(
    private val toolRegistry: ToolRegistry
) {
    companion object {
        const val ACTION_INFO = "info"
        const val ACTION_READ_FILE = "read_file"
        const val ACTION_WRITE_FILE = "write_file"
        const val ACTION_SHELL = "shell"
        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_FETCH_FILE = "fetch_file"
        const val ACTION_PUSH_FILE = "push_file"
        const val ACTION_SEND_MESSAGE = "send_message"

        /** 远端 shell 单次执行上限（秒）：留出审批时间，避免超过调用方 120 秒的 callTimeout。 */
        const val REMOTE_SHELL_MAX_TIMEOUT_SECONDS = 60L

        /** 本机工具的挂载名（与 di/AgentModule.kt 的注册名一致）。 */
        const val TOOL_READ_FILE = "readFile"
        const val TOOL_WRITE_FILE = "writeFile"
        const val TOOL_BASH = "Bash"
        const val TOOL_A11Y = "a11y"
    }

    val actions: List<RemoteAction> = listOf(
        RemoteAction(
            name = ACTION_INFO,
            kind = RemoteActionKind.INFO,
            description = "返回本机信息：设备名、应用版本、Android 版本、可调用的动作与能力清单。" +
                "不读取任何用户数据，因此不需要本机用户审批。",
            toolName = null,
            allowedArgs = emptySet(),
            escalateWhenAllowed = false
        ),
        RemoteAction(
            name = ACTION_READ_FILE,
            kind = RemoteActionKind.READ_FILE,
            description = "读取本机文件。path 用 ~/workspace/... 表示工作区文件，绝对路径（/etc/... 等）表示系统文件；" +
                "单次有条数与字节上限，超出用 start_line 分段续读。",
            toolName = TOOL_READ_FILE,
            allowedArgs = setOf("path", "start_line", "end_line")
        ),
        RemoteAction(
            name = ACTION_WRITE_FILE,
            kind = RemoteActionKind.WRITE_FILE,
            description = "写入本机文件，路径写法与本地 agent 一致（~/workspace/... 表工作区，绝对路径落容器 rootfs）；" +
                "落盘前会做越界校验（只允许工作区 / 容器 rootfs / AI 配置 / 记忆 / 共享区之内），越界直接拒绝。" +
                "文件不存在时自动创建（含父目录）。每次写入都需要本机用户审批。",
            toolName = TOOL_WRITE_FILE,
            allowedArgs = setOf("path", "content", "overwrite")
        ),
        RemoteAction(
            name = ACTION_SHELL,
            kind = RemoteActionKind.EXECUTE_COMMAND,
            description = "在本机容器/终端执行一条 shell 命令并返回输出。" +
                "单次最长 $REMOTE_SHELL_MAX_TIMEOUT_SECONDS 秒（超时会被钳到该值）；" +
                "不支持后台任务与提权参数——远端调用不提供这两条路径。",
            toolName = TOOL_BASH,
            allowedArgs = setOf("command", "timeout")
        ),
        RemoteAction(
            name = ACTION_SCREENSHOT,
            kind = RemoteActionKind.SCREENSHOT,
            description = "截取本机当前屏幕（图片随结果返回）。需本机已开启无障碍服务，且 Android 11+。",
            toolName = TOOL_A11Y,
            allowedArgs = emptySet(),
            forcedArgs = mapOf("action" to "shot")
        ),
        RemoteAction(
            name = ACTION_FETCH_FILE,
            kind = RemoteActionKind.READ_FILE,
            description = "取回本机的**整个文件**：返回一条一次性下载链接，用它对本机发 HTTP GET 即可拿到原文件。" +
                "二进制安全、**不设大小上限**（不走 JSON，所以不受 MCP 正文限制），适合 APK / 图片 / 压缩包这类" +
                "用 read_file 读不出来的东西。路径写法与越界校验同 read_file。",
            toolName = null,
            allowedArgs = setOf("path"),
            inlineParams = mapOf(
                "path" to ToolParameter(
                    name = "path",
                    type = ParameterType.STRING,
                    description = "要取回的文件路径，写法与 read_file 一致（~/workspace/... 或绝对路径）",
                    required = true
                )
            )
        ),
        RemoteAction(
            name = ACTION_PUSH_FILE,
            kind = RemoteActionKind.WRITE_FILE,
            description = "往本机放一个**完整文件**：返回一条一次性上传链接，把文件原始字节 POST 上去即可。" +
                "二进制安全、**不设大小上限**（不走 JSON），适合 APK / 图片 / 压缩包这类不适合用 write_file 写的内容。" +
                "路径写法与越界校验同 write_file。",
            toolName = null,
            allowedArgs = setOf("path"),
            inlineParams = mapOf(
                "path" to ToolParameter(
                    name = "path",
                    type = ParameterType.STRING,
                    description = "要写入的文件路径，写法与 write_file 一致；文件不存在时自动创建（含父目录）",
                    required = true
                )
            )
        ),
        RemoteAction(
            name = ACTION_SEND_MESSAGE,
            kind = RemoteActionKind.MESSAGE,
            description = "给这台设备的 Aharou 投一条**聊天消息**：直接出现在其当前会话里，并弹一条系统通知" +
                "（不是写文件——用户打开 App 就能看到）。参数 text 为纯文本正文。",
            toolName = null,
            allowedArgs = setOf("text"),
            inlineParams = mapOf(
                "text" to ToolParameter(
                    name = "text",
                    type = ParameterType.STRING,
                    description = "消息正文（纯文本，建议一两句话以内）",
                    required = true
                )
            )
        )
    )

    fun byName(name: String): RemoteAction? = actions.firstOrNull { it.name == name }

    /** 能力清单（info 用）：按动作去重后的能力位。 */
    fun capabilityList(): List<String> = actions.map { it.kind.capability }.distinct()

    fun resolveTool(action: RemoteAction): AgentTool? = action.toolName?.let { toolRegistry.getTool(it) }

    /** 该动作的入参 JSON Schema：从本机工具参数过滤白名单而来；工具暂不可用时回退为空对象 schema。 */
    fun inputSchema(action: RemoteAction): JsonObject {
        val declared = action.inlineParams.takeIf { it.isNotEmpty() }
            ?: resolveTool(action)?.parameters.orEmpty()
        val params = declared.filterKeys { it in action.allowedArgs }
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                params.forEach { (key, param) ->
                    putJsonObject(key) {
                        put("type", schemaType(param))
                        put("description", param.description)
                        param.enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
                    }
                }
                // 强制注入的参数对调用方可见（省得对端猜），但标为不可改。
                action.forcedArgs.forEach { (key, value) ->
                    if (key !in params) {
                        putJsonObject(key) {
                            put("type", "string")
                            put("description", "固定值（由本机执行时注入）")
                            put("const", value)
                            put("readOnly", true)
                        }
                    }
                }
            }
            putJsonArray("required") {
                params.filterValues { it.required }.keys.forEach { add(JsonPrimitive(it)) }
            }
        }
    }

    private fun schemaType(param: ToolParameter): String = when (param.type) {
        ParameterType.STRING -> "string"
        ParameterType.INTEGER -> "integer"
        ParameterType.BOOLEAN -> "boolean"
        ParameterType.ARRAY -> "array"
        ParameterType.OBJECT -> "object"
    }
}

/**
 * `info` 动作的载体：把它也做成一个 [AgentTool]，好让远端调用统一经过本机门禁（
 * [com.aharou.feature.agent.domain.workflow.PermissionGate] 与策略引擎），并在审计里留下一条记录。
 *
 * 无能力位、策略 AUTO_APPROVE：它不读也不写用户数据，门禁必然放行（见策略引擎对 AUTO_APPROVE 的处理）。
 */
class RemoteInfoTool(
    private val payload: suspend () -> JsonElement
) : AgentTool() {
    override val name = "remote_info"
    override val description = "远端协作服务的设备信息查询（只读）"
    override val parameters: Map<String, ToolParameter> = emptyMap()
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        ToolResult.Success(payload())
}

/**
 * `fetch_file` 动作的载体：签一张下载票据并回链接。
 *
 * 文件本体不走 MCP —— 那要过 JSON 与 256 KB 正文上限，几十 MB 的东西根本过不去。
 * 这里只换一张一次性票据，真正的字节由原生 HTTP 流搬（无大小上限、不吃内存）。
 */
class RemoteFetchFileTool(
    private val issueTicket: suspend (String) -> String
) : AgentTool() {
    override val name = "remote_fetch_file"
    override val description = "取回本机文件，返回一次性下载链接（二进制、无大小上限）"
    override val parameters: Map<String, ToolParameter> = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "要取回的文件路径",
            required = true
        )
    )
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return ToolResult.Error("缺少参数: path", "MISSING_PATH")
        return ToolResult.Success(
            buildJsonObject {
                put("url", issueTicket(path))
                put("hint", "用 HTTP GET 请求这条路径即可下载原文件；链接一次性、15 分钟内有效")
            }
        )
    }
}

/** `push_file` 动作的载体：签一张上传票据并回链接（与 [RemoteFetchFileTool] 同理，本体不走 MCP）。 */
class RemotePushFileTool(
    private val issueTicket: suspend (String) -> String
) : AgentTool() {
    override val name = "remote_push_file"
    override val description = "往本机放一个文件，返回一次性上传链接（二进制、无大小上限）"
    override val parameters: Map<String, ToolParameter> = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "要写入的文件路径",
            required = true
        )
    )
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return ToolResult.Error("缺少参数: path", "MISSING_PATH")
        return ToolResult.Success(
            buildJsonObject {
                put("url", issueTicket(path))
                put("hint", "把文件原始字节 POST 到这条路径即可（Content-Type 任意）；链接一次性、15 分钟内有效")
            }
        )
    }
}

/**
 * `send_message` 动作的载体：把对端给的正文投进本机 Aharou 的当前会话。
 *
 * 与其它动作的区别在于它不碰文件系统——落到的是会话消息与一条系统通知，用户打开 App 就能在聊天里看到。
 */
class RemoteSendMessageTool(
    private val deliver: suspend (String) -> String
) : AgentTool() {
    override val name = "remote_send_message"
    override val description = "给本机 Aharou 的当前会话投一条消息（会出现在聊天里并弹通知）"
    override val parameters: Map<String, ToolParameter> = mapOf(
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "消息正文",
            required = true
        )
    )
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE, ToolCapability.USER_INTERACTION)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.Error("缺少参数: text", "MISSING_TEXT")
        return ToolResult.Success(buildJsonObject { put("delivered", deliver(text)) })
    }
}
