package com.aharou.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.tool.ToolCall

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val images: List<AgentImage> = emptyList()
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** 本轮模型的思考过程（对应 OpenAI/DeepSeek 的 reasoning_content）。回传上下文时需要原样发回，否则 DeepSeek 思考模式会报 400 错误。 */
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名。与 [reasoning] 一起原样回传（工具循环必须），否则 400。其他 provider 为空串。 */
        val signature: String = "",
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），回传时需保持原样与原序。其他 provider 为空串。 */
        val thinkingBlocksJson: String = "",
        /**
         * 本轮模型直接生成的图片（Gemini 图像模型）。内存态下 base64Data 可为空、path 指向容器文件，
         * 回放时按 path 重建 base64 喂模型；落库只存附件路径不存 base64（见 [MessagePersistenceUseCase]）。
         */
        val images: List<AgentImage> = emptyList(),
        val inputTokens: Int = 0
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** 仅喂模型的精简结果文本；null 时回退用 [result]。UI 与持久化仍用 result。 */
        val modelResult: String? = null,
        val messageId: String = "tool_$id"
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "What did we do so far?"
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> messageId
    }

/**
 * 一个工作区的标识：名称（列表里查不到名称时为 null）+ 绝对路径。
 *
 * 只用于让模型知道「当前是哪个仓库」——`~/workspace` 是容器内的固定别名，
 * 指着同一个字符串，光看它认不出背后是哪个仓库。
 */
data class WorkspaceRef(
    val name: String?,
    val path: String
) {
    /** 喂给模型的可读形式，如「Aharou（/data/data/.../projects/Aharou）」。 */
    fun display(): String = if (name.isNullOrBlank()) path else "$name（$path）"
}

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    /**
     * 本轮用户请求的原始文本。提示词源据此按任务关键词挑选内容（如记忆清单的相关性排序）。
     * 不能从 [history] 取——取历史发生在插入本轮用户消息之前，那里拿到的只是上一轮，首轮还是空。
     */
    val currentUserText: String = "",
    val inputImages: List<AgentImage> = emptyList(),
    /** 当前会话 id：用于把本轮所有 AI 请求/响应落到该会话的日志文件（[com.aharou.core.util.AILogger]）。 */
    val sessionId: String? = null,
    val inputMessageId: String = java.util.UUID.randomUUID().toString(),
    val lastInputTokens: Int = 0,
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）：退出 PLAN 时恢复到它，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    /** 思考强度（"low"/"medium"/"high"），随每次 LLM 请求传给支持的 provider。 */
    val reasoningEffort: String? = null,
    /**
     * 本会话绑定的自定义子代理定义；非空表示这是一个按定义运行的子代理会话，
     * 系统提示词与工具集都按其配置组装（见 [com.aharou.feature.agent.domain.prompt.SystemPromptProvider]）。
     */
    val agentDefinition: AgentDefinition? = null,
    /**
     * 子代理写路径租约：null 表示不受限（主会话，或派发时未声明）；
     * 空集表示只读；非空时写文件类工具只允许命中其中的路径。
     */
    val writePaths: Set<String>? = null,
    /**
     * 本轮实际运行的模型身份；null 时不注入「当前模型身份」段。
     * 有了它，模型自报能力时有据可依，不必靠翻记忆或调 config 工具。
     */
    val modelIdentity: ModelIdentity? = null,
    /** 会话级持续目标正文（goal），仅在本轮有目标时非空。 */
    val goal: String? = null,
    /** 目标状态；为 [GoalStatus.ACTIVE] 时授权强度提为 AUTO（见 [permissionMode]）。 */
    val goalStatus: GoalStatus? = null,
    /** 会话绑定工作区的名称（列表里查不到时为空）；与 [projectRoot] 一起说明「当前是哪个仓库」。 */
    val workspaceName: String? = null,
    /** 与上一轮相比工作区变过时，这里是上一轮的工作区；未变时为 null（系统提示据此追加一次性切换提示）。 */
    val workspaceSwitchedFrom: WorkspaceRef? = null,
    /** 界面当前选中的工作区；与会话绑定的 [projectRoot] 不一致时非空，用于提醒别按错仓库操作。 */
    val selectedWorkspace: WorkspaceRef? = null
) {
    /**
     * 实际用于权限判定的模式：goal 激活时不改变用户看到的 [mode]，只把授权强度提为 AUTO。
     * 目标循环是用户亲自设定的持续作业，每轮都要重复审批等于把功能用废。
     */
    val permissionMode: AgentMode
        get() = if (goalStatus == GoalStatus.ACTIVE) AgentMode.AUTO else mode
}
