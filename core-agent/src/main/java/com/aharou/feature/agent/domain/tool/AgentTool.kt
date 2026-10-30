package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
sealed class ToolResult {
    @Serializable
    @SerialName("success")
    data class Success(
        val data: JsonElement,
        val images: List<AgentImage> = emptyList(),
        /**
         * 结果里的明文是「用户当面点头放行」的（当前只有 config 的 reveal 在确认面板通过后置位）。
         *
         * 置位的路径**不参与序列化**（模型既看不到、也无法伪造），只在本进程内告诉
         * [com.aharou.feature.vault.redactWith] 跳过凭据脱敏：用户刚亲自批准读取，再把值换成
         * `[REDACTED:NAME]` 等于让他白批一次。默认 false，普通工具结果照旧脱敏。
         */
        @Transient val plaintextApproved: Boolean = false
    ) : ToolResult()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val code: String = "UNKNOWN") : ToolResult()

    @Serializable
    @SerialName("partial")
    data class Partial(val data: JsonElement, val message: String) : ToolResult()
}

private val ToolResultTransportJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

fun ToolResult.toTransportString(): String {
    return ToolResultTransportJson.encodeToString(this)
}

/**
 * 「人话标题」参数名。
 *
 * 由 [AgentTool.toJsonSchema] 统一注入到每个内置工具的 schema 里（排在第一位、且必填），
 * 工具实现**不必**在 [AgentTool.parameters] 里声明它、也不必读它：模型填的是一句 5~10 个词的
 * 人话描述（如「读取依赖配置」「跑一次冒烟编译」），工具卡标题行直接显示，用户就不用面对
 * `Bash` / `readFile` 这类机器名加一坨参数 JSON 去猜这一步在干什么。
 *
 * 注入点选在 schema 而不是 `parameters` 表：`parameters` 还被远程设备动作（RemoteAction 按
 * `allowedArgs` 白名单过滤）、工具说明等地方按「工具真正会用到的入参」消费，混进一个执行侧
 * 不消费的字段会污染那些语义；对模型可见的 schema 才是唯一该加的地方。
 */
const val TOOL_TITLE_PARAM = "tool_title"

/** 标题的字符上限：模型偶尔把整句解释塞进来，显示侧按此截断，避免撑爆标题行。 */
private const val TOOL_TITLE_MAX_CHARS = 80

/** [TOOL_TITLE_PARAM] 的 schema 描述（模型看的，与其它参数描述一样用中文）。 */
private const val TOOL_TITLE_DESCRIPTION =
    "一句话人话标题：用 5~10 个词、与用户同语言说明这一步在做什么，会直接显示在工具卡上" +
        "（例：读取依赖配置、跑一次冒烟编译）。不要重复工具名，也不要粘参数原文。"

/**
 * 从工具调用参数里取模型给的人话标题；缺失、非字符串或全是空白时返回 null。
 *
 * 显示侧据此回退到工具名——老会话里已存的工具调用没有这个字段，必须能优雅退化。
 * 主循环判断「这次是否漏填标题」也用它（`extractToolTitle(args) == null`）。
 */
fun extractToolTitle(args: Map<String, JsonElement>): String? =
    (args[TOOL_TITLE_PARAM] as? JsonPrimitive)?.contentOrNull?.toToolTitleOrNull()

/** 同 [extractToolTitle]，入参是持久化里的原始参数 JSON 串（工具卡从会话里读到的就是它）。 */
fun extractToolTitle(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        ?.let { return extractToolTitle(it) }
    // 会话里存的参数是「前 500 字符的预览」，带大内容的调用（writeFile 等）会被截成非法 JSON；
    // 标题排在最前面，整串解不开时按字面量再捞一次，别让截断把标题弄丢。
    return TOOL_TITLE_LITERAL_RE.find(argsJson)?.groupValues?.get(1)
        ?.replace("\\n", " ")?.replace("\\t", " ")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        ?.toToolTitleOrNull()
}

/** 从（可能被截断的）参数串里抓 `"tool_title": "..."` 的字面量。 */
private val TOOL_TITLE_LITERAL_RE = Regex("\\\"tool_title\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"")

/** 去掉标题参数后的参数表：执行前若要展示/转发参数，用它避免把标题当业务入参。 */
fun stripToolTitle(args: Map<String, JsonElement>): Map<String, JsonElement> =
    if (TOOL_TITLE_PARAM in args) args - TOOL_TITLE_PARAM else args

/** 压成单行、去空白，超长按 [TOOL_TITLE_MAX_CHARS] 截断；无有效内容返回 null。 */
private fun String.toToolTitleOrNull(): String? {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    if (oneLine.isEmpty()) return null
    return if (oneLine.length <= TOOL_TITLE_MAX_CHARS) oneLine
    else oneLine.take(TOOL_TITLE_MAX_CHARS).trimEnd() + "…"
}

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    /**
     * 当 [type] 为 [ParameterType.ARRAY] 时，描述数组元素的 JSON Schema（原样并入
     * function-calling 的 items 字段）。例如元素是对象时传
     * `{"type":"object","properties":{...},"required":[...]}`。为空则不输出 items。
     */
    val itemsSchema: Map<String, Any>? = null
)

enum class ParameterType {
    STRING, INTEGER, BOOLEAN, ARRAY, OBJECT
}

enum class ToolPermissionPolicy {
    AUTO_APPROVE, ASK
}

enum class ToolCapability {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_COMMANDS,
    NETWORK_READ,
    NETWORK_WRITE,
    READ_AGENT_CONFIG,
    MODIFY_AGENT_CONFIG,
    MODIFY_CONTAINER_ENV,
    USER_INTERACTION,
    MODIFY_SESSION_STATE,
    MODIFY_TODO_STATE,
    EXTERNAL_TOOL,

    /** 读宿主设备数据：剪贴板 / 日历 / 联系人 / 通知 / 定位 / 相册 / 设备状态。 */
    READ_DEVICE_DATA,

    /** 改宿主设备状态：建闹钟、写日历或联系人、写剪贴板、发通知、控制媒体。 */
    MODIFY_DEVICE_STATE
}

/**
 * 授权弹窗里预览用的差异片段，与 editFile/writeFile 执行结果里的 hunks 同构
 * （start_line/added/removed/diff），UI 可直接用 [com.aharou.feature.agent.presentation.component.DiffView] 渲染。
 */
data class ToolPermissionHunk(
    val startLine: Int,
    val added: Int,
    val removed: Int,
    val diff: String
)

data class PendingToolPermission(
    val id: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val details: String,
    val argsPreview: String,
    /**
     * 「始终允许」会记忆的模式列表（shell 命令为命令前缀：子命令分发器记 `git pull` 这类带子命令的
     * 前缀，普通程序记 `cat`/`ls`；非 shell 工具为 `*`）。
     * 为空表示该调用不可记忆（命令不可静态判定），UI 应禁用「始终允许」、只留单次放行。
     * 由 [com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine] 评估后填入。
     */
    val rememberablePatterns: List<String> = emptyList(),
    val rememberDisabledReason: String? = null,
    /** 发起该授权请求的会话 id。多会话并行时用于在 UI 上区分弹窗归属、点亮侧边栏等待授权指示。 */
    val sessionId: String = "",
    /**
     * 写操作的真实「旧→新」差异预览（由 [AgentTool.buildPermissionPreview] 产出，当前仅 editFile/writeFile）。
     * 为空表示无预览，UI 退回展示 [summary]/[details]。
     */
    val previewHunks: List<ToolPermissionHunk> = emptyList(),
    /** 预览类型："edit" 时 UI 按文件差异渲染 [previewHunks]，其余按 [summary]/[details] 渲染。 */
    val itemKind: String = "tool"
)

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()

    /**
     * 是否接受 `{{vault:NAME}}` 密钥占位符：为真时由工作流在审批通过后、执行前解析为真值。
     * 默认全 false——密钥只供给配置了它的连接器，工具（尤其命令/脚本类）一律不接受。
     */
    open val acceptsSecrets: Boolean = false

    /**
     * 按运行模式解析实际权限策略；默认取 [permissionPolicy]，工具可覆盖。
     * 例：子代理派发（task）在非 PLAN 模式下免确认，PLAN 下仍弹窗。
     */
    open fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy = permissionPolicy

    /**
     * 延迟加载：为 true 的工具不直接进每轮 tools 数组，先由 `tool_search` 按需发现后才展开 schema。
     * 用于 MCP 这类「工具多、单轮用不上几个」的来源，避免全量定义长期占上下文。
     */
    open val deferredLoading: Boolean = false

    open fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return capabilities
    }

    /**
     * 本次调用是否属于「敏感操作」：为真时策略引擎在读已记忆规则**之前**直接升为 ASK 且不可记忆，
     * 用于屏幕敏感点按（支付/发送/删除等）这类需要逐次确认、不能被「始终允许」记忆绕过的场景。
     *
     * 默认 false；由具体工具根据参数判定（如 VdTool/A11yTool 按点击标签判定）。仅 BUILD（ask/strict）
     * 生效：AUTO 维持全放行（用户已定，与 nanoMuse 不同），PLAN 写操作另有拦截。
     */
    open fun isCallSensitive(args: Map<String, JsonElement>): Boolean = false

    /**
     * 不带上下文的执行。只实现了 [executeWithContext] 的工具无需实现它——工具调用链统一走
     * [executeWithContext]，这里只是兜底，正常运行不会走到。
     */
    open suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return ToolResult.Error("工具 $name 需要执行上下文", "CONTEXT_REQUIRED")
    }

    open suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.aharou.feature.agent.domain.model.AgentContext): ToolResult {
        return execute(args)
    }
    open fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行工具",
            summary = "AI 请求执行 $name",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    /**
     * 构建带真实差异预览的授权请求（不写盘），供批量审批面板渲染。默认无预览（返回 null），
     * 由写文件类工具覆写；预览失败/超时也返回 null，调用方回退 [buildPermissionRequest]，
     * 绝不让预览辅助步骤卡住审批。
     */
    open suspend fun buildPermissionPreview(
        callId: String,
        args: Map<String, JsonElement>,
        context: com.aharou.feature.agent.domain.model.AgentContext
    ): PendingToolPermission? = null

    /**
     * 该工具的 schema 是否要求人话标题：内置工具恒为 true（[toJsonSchema] 统一注入），
     * 覆写了 [toJsonSchema] 的外部工具（MCP 透传服务端原始 schema）为 false。
     *
     * 供主循环的「漏填标题软提醒」判定用：只有要求标题的工具才谈得上「漏填」，MCP 工具不该被提醒。
     * 首次计算后缓存——主循环每次工具调用都会问一次，反复拼 schema 纯属浪费。
     */
    fun declaresToolTitle(): Boolean = declaresToolTitleCache

    private val declaresToolTitleCache: Boolean by lazy {
        ((toJsonSchema()["properties"] as? Map<*, *>)?.containsKey(TOOL_TITLE_PARAM)) == true
    }

    /**
     * 生成符合 JSON Schema 的参数描述，用于真正传给大模型的 function-calling 接口
     * （OpenAI 的 function.parameters / Anthropic 的 input_schema）。
     *
     * 设为 open：MCP 等外部工具携带的是任意原始 inputSchema，无法用受限的 [ParameterType]
     * 枚举表达，需要覆写本方法直接透传服务端 schema。
     */
    open fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        // 人话标题作为第一个参数注入：模型先写「这一步在做什么」，再写业务参数，
        // 参数顺序上也更贴近「先说人话」的期望。执行侧只读自己关心的键，多这一项不影响行为。
        properties[TOOL_TITLE_PARAM] = linkedMapOf<String, Any>(
            "type" to "string",
            "description" to TOOL_TITLE_DESCRIPTION
        )
        required.add(TOOL_TITLE_PARAM)
        parameters.forEach { (key, param) ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.name.lowercase()
            prop["description"] = param.description
            param.enum?.let { prop["enum"] = it }
            if (param.type == ParameterType.ARRAY) param.itemsSchema?.let { prop["items"] = it }
            properties[key] = prop
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * 可流式执行的工具：在最终结果产生之前，逐步 emit 过程输出（如命令的逐行 stdout），
 * 让 UI 能实时显示「执行过程」而非只有最终结果。
 *
 * 实现类应同时实现 [AgentTool.execute] 作为非流式兜底；工作流优先走 [executeStream]。
 */
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: com.aharou.feature.agent.domain.model.AgentContext): Flow<ToolStreamEvent>
}

/** 流式工具执行过程中产生的事件。 */
sealed class ToolStreamEvent {
    /** 一段新的过程输出（通常是一行）。 */
    data class Progress(val chunk: String) : ToolStreamEvent()
    /** 执行结束，附最终聚合结果（喂回模型用）。 */
    data class Completed(val result: ToolResult) : ToolStreamEvent()
}
