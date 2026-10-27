package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.provider.RetryErrorInfo
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.TOOL_TITLE_PARAM
import com.aharou.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Agent 执行过程中向 UI 推送的分步事件。
 * 每个事件对应聊天里的一条（或对一条的更新），由 ViewModel 落库并渲染。
 */
sealed class AgentEvent {
    /** 模型产出的一段文字回复（可能同时伴随工具调用）。[reasoning] 为本轮思考过程，落库供历史展示，空串表示无。 */
    data class AssistantText(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名，随 reasoning 落库，供工具循环回传。其他 provider 为空串。 */
        val signature: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），随 reasoning 落库供后续轮原样回传。 */
        val thinkingBlocksJson: String = "",
        /** 模型直出图片（Gemini 图像模型）落盘后构造的文件卡片，随消息落库供 UI 渲染。 */
        val attachments: List<com.aharou.feature.agent.presentation.AgentAttachment> = emptyList(),
        /** 本条助手消息落库时使用的 id；由 workflow 生成，ViewModel 据此把思考耗时与落库消息关联。 */
        val messageId: String = "",
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** 流式过程中模型逐字吐出的文字（[accumulated] 为本轮已累积的完整文本，用于 UI 实时渲染，不落库）。 */
    data class AssistantDelta(val accumulated: String) : AgentEvent()

    /** 流式过程中模型逐字吐出的思考过程（[accumulated] 为本轮已累积的完整思考，用于 UI 实时渲染，不落库）。 */
    data class ReasoningDelta(val accumulated: String) : AgentEvent()

    /** 模型决定调用某工具（执行前）。 */
    data class ToolCallStarted(val id: String, val toolName: String, val argsPreview: String) : AgentEvent()

    /**
     * 模型刚开始产出某次工具调用、工具名已知（参数还在流式传输中）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间既没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。这条事件让 UI 提前把状态说具体，不落库、不参与执行判定。
     */
    data class ToolCallPreparing(val toolName: String) : AgentEvent()

    /** 工具执行过程中的实时累积输出（仅流式工具产生，用于 UI 实时渲染，不落库）。 */
    data class ToolCallProgress(val id: String, val toolName: String, val accumulated: String) : AgentEvent()

    /** 工具执行完成的结果（成功、失败或被用户拒绝）。 */
    data class ToolCallFinished(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        val argsPreview: String? = null,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，落库供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.aharou.feature.agent.presentation.AgentAttachment> = emptyList(),
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** 网络请求正在重试（首字节前失败触发自动重试）。仅用于 UI 实时展示，不落库。[error] 为触发重试的错误摘要。 */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AgentEvent()

    /** 多 Key 自动切换：当前 Key 不可用，已改用第 [newIndex]/[total] 个 Key 重发本次请求。仅用于 UI 实时展示，不落库。 */
    data class KeySwitched(val newIndex: Int, val total: Int) : AgentEvent()

    /**
     * 采样循环检测命中：模型在反复输出同一段内容（[inReasoning] 表示命中在思考还是正文），
     * 已截断到首次出现处并中止本次流式。UI 据此落一条提示记录，免得内容无声断在半截。
     */
    data class SamplingLoopDetected(val inReasoning: Boolean) : AgentEvent()

    /** 正在进行上下文压缩。仅用于 UI 实时展示，不落库。 */
    data class CompactionStarted(val estimatedTokens: Int) : AgentEvent()

    /** 上下文压缩流程已结束（成功或失败）。仅用于 UI 实时展示，不落库。 */
    object CompactionFinished : AgentEvent()

    data class ContextUsage(val inputTokens: Int, val inputBudget: Int, val estimated: Boolean) : AgentEvent()

    /** 上下文压缩失败（如压缩模型不可用）。携带失败原因，UI 展示为可展开的失败卡片；不落库。 */
    data class CompactionFailed(val reason: String) : AgentEvent()

    /**
     * 整个流程因错误终止（如流式请求被网络中断、达到迭代上限）。与 [Completed] 区别：携带错误，UI 应展示错误而非成功。
     * [reasonCode] 为服务端给出的类型码（如 Anthropic 的 refusal / model_context_window_exceeded），
     * 供 UI 换成本地化说明；null 时直接展示 [error]。
     */
    data class Failed(val error: String, val reasonCode: String? = null) : AgentEvent()

    /** 整个流程结束。 */
    object Completed : AgentEvent()

    /** 模式已切换（由 AI 调用 planMode 触发），UI 据此展示计划审查面板等。 */
    data class ModeChanged(val newMode: AgentMode, val reason: String) : AgentEvent()
}

interface AgentWorkflow {
    /**
     * 运行 Agent 循环并以事件流的形式分步推送进度：
     * 模型回复 → （可能的）工具调用 → 工具结果 → 再回复 …… 直至模型不再调用工具。
     */
    fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent>

    /**
     * 手动触发指定会话的上下文压缩（供 /compress 命令调用）。
     * 复用自动压缩逻辑：解析当前生效 provider，对历史消息调用 ContextCompactor。
     * 压缩结果由 ContextCompactor 内部持久化，通过 [onEvent] 回调推送压缩进度事件。
     * @return 是否实际触发了压缩（历史未超阈值时返回 false，不改动）。
     */
    suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit = {}): Boolean

    /**
     * 为新建会话生成标题（供首条用户消息后异步调用）。
     * 默认跟随当前聊天模型；若配置了标题总结专用模型且可用则用之。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    suspend fun generateTitle(sessionId: String, request: String): String?

    /**
     * 通用一次性调用：给定系统提示词 + 单条用户文本，直接拿模型返回的文本。
     * 供记忆蒸馏这类后台任务用——不经过会话历史与工具循环，也不改任何会话状态；
     * 取当前生效供应商，未配置或调用失败返回 null（调用方静默跳过）。
     */
    suspend fun completeOnce(systemPrompt: String, userText: String, sessionId: String? = null): String?

    /**
     * 根据 Git 差异文本生成符合 Conventional Commits 规范的提交信息。
     * 默认使用全局生效的 AI 供应商及默认模型。
     * @param diff 变更内容差异文本（`git diff --cached` 或工作区差异）
     * @return 建议的提交说明，失败返回 null。
     */
    suspend fun generateCommitMessage(diff: String): String?

    /**
     * 会话轮次结束后的记忆兑现（引擎级兜底）：用轻量模型从本轮对话文本中抽取值得长期记住的事实，
     * 直接写入记忆存储。静默失败，不抛异常。
     * @param transcript 本轮对话文本（"用户: …/助手: …" 行），由调用方截取最近内容拼出。
     * @return 本次实际写入的记忆条数。
     */
    suspend fun curateMemory(sessionId: String, projectRoot: String?, transcript: String): Int
}

/**
 * 工具循环检测的三级阈值。
 *
 * 为什么是 10 / 20 / 30：
 * - 10（警告）：正常重试（网络抖动、参数写错重填）不会到 10 次；到 10 次且结果一模一样，说明这一步
 *   没有再产生任何新信息。这一级只提醒不打断——有的模型需要一句提示才肯换招，硬断会把快到手的任务掐没；
 * - 20（严重）：提醒过一轮还在重复，就需要更强的约束（下一步必须换策略或明确收尾）；
 * - 30（熔断）：连续 30 次同参同结果已经可以认定本轮卡死，继续跑只是烧额度和时间，直接终止；
 * - 历史窗口 32：必须装得下最高阈值（30），多留 2 条给「同批多调用」留余量，否则熔断级永远数不到 30。
 */
internal object ToolLoopThresholds {
    const val WARNING = 10
    const val CRITICAL = 20
    const val CIRCUIT_BREAK = 30
    const val WINDOW = 32
}

/**
 * 循环提醒注入到上下文时的消息 id 前缀。
 *
 * 这些提醒也是 UserMessage，但它们不是「用户开了新一轮任务」，判定窗口不能因为它而清零，
 * 所以给它们一个可识别的固定 id；其它用户消息一旦出现就说明任务换了一轮，窗口从那里重新开。
 */
internal const val TOOL_LOOP_NUDGE_ID = "tool-loop-nudge"

/** 循环熔断的终止原因码（随 [AgentEvent.Failed.reasonCode] 上抛），UI 可据此换成本地化文案。 */
internal const val TOOL_LOOP_CIRCUIT_BREAK_CODE = "TOOL_LOOP_CIRCUIT_BREAK"

/**
 * 「观察/等待」类工具：同一参数反复调用、结果暂时没变，本身是合法的等待方式
 * （等子代理跑完、等长命令吐输出、等界面变化）。它们只吃提醒不吃熔断，
 * 否则一个正常等十分钟的长任务会被当成死循环杀掉。
 */
private val WAIT_POLL_TOOLS = setOf("task", "terminal", "vscreen", "a11y", "browser")

/** 循环检测的等级，枚举顺序即严重程度：reducer 用 ordinal 判断「这一批刚跨过哪一级」。 */
internal enum class ToolLoopLevel { NONE, WARNING, CRITICAL, CIRCUIT_BREAK }

/** 一次循环判定的结果：[toolName] 为最新一条工具调用，[repeatCount] 为它的连续重复次数。 */
internal data class ToolLoopVerdict(
    val level: ToolLoopLevel = ToolLoopLevel.NONE,
    val toolName: String = "",
    val repeatCount: Int = 0
)

/** 窗口里的一条记录：判定只要「调用键 + 结果哈希」，不保留结果正文。 */
internal data class ToolLoopRecord(
    val toolName: String,
    val argsKey: String,
    val resultHash: String
)

/**
 * 对已回填工具结果的消息历史做一次循环判定（纯函数，不持有任何检测状态）。
 *
 * 判定键是「工具名 + 参数 + 结果哈希」：参数相同但结果变了说明有进展，绝不能算进循环，
 * 这也是把结果纳入判定键的原因（只看调用本身会把「反复查同一个命令」误判成死循环）。
 */
internal fun toolLoopVerdict(
    messages: List<AgentMessage>,
    window: Int = ToolLoopThresholds.WINDOW
): ToolLoopVerdict {
    val records = toolCallRecords(messages, window)
    val latest = records.lastOrNull() ?: return ToolLoopVerdict()
    var pinnedResultHash: String? = null
    var repeatCount = 0
    for (index in records.indices.reversed()) {
        val record = records[index]
        // 别的调用既不计入也不打断：模型陷在循环里时常常夹着别的动作（查状态、写进度）。
        if (record.argsKey != latest.argsKey) continue
        if (pinnedResultHash == null) {
            pinnedResultHash = record.resultHash
            repeatCount = 1
        } else if (record.resultHash == pinnedResultHash) {
            repeatCount++
        } else {
            // 结果变了就是拿到了新信息，循环链在这里断开。
            break
        }
    }
    val level = when {
        repeatCount >= ToolLoopThresholds.CIRCUIT_BREAK && latest.toolName !in WAIT_POLL_TOOLS ->
            ToolLoopLevel.CIRCUIT_BREAK
        repeatCount >= ToolLoopThresholds.CRITICAL -> ToolLoopLevel.CRITICAL
        repeatCount >= ToolLoopThresholds.WARNING -> ToolLoopLevel.WARNING
        else -> ToolLoopLevel.NONE
    }
    return ToolLoopVerdict(level, latest.toolName, repeatCount)
}

/**
 * 从消息历史重建最近 [window] 条工具调用记录。
 *
 * 先数一遍工具结果总数、只哈希尾部窗口内的那几条：整段历史都哈希一遍在长会话里代价不小，
 * 而判定只需要窗口内的记录。遇到真正的用户消息（不是循环提醒）时窗口清零，
 * 判定范围永远是「本轮任务」，不会把上一轮的重复算到这一轮头上。
 */
private fun toolCallRecords(messages: List<AgentMessage>, window: Int): List<ToolLoopRecord> {
    val total = messages.count { it is AgentMessage.ToolResultMessage }
    if (total == 0 || window <= 0) return emptyList()
    val skip = (total - window).coerceAtLeast(0)
    val callsById = HashMap<String, ToolCall>()
    val records = ArrayList<ToolLoopRecord>(minOf(total, window))
    var seen = 0
    messages.forEach { message ->
        when (message) {
            is AgentMessage.AssistantMessage -> message.toolCalls.forEach { callsById[it.id] = it }
            is AgentMessage.UserMessage -> if (!message.id.startsWith(TOOL_LOOP_NUDGE_ID)) {
                records.clear()
                callsById.clear()
            }
            is AgentMessage.ToolResultMessage -> {
                seen++
                if (seen <= skip) return@forEach
                // 参数取不到（历史被裁剪/压缩、原调用对不上）就丢掉这条：
                // 只按工具名分组会把同一工具的不同调用归成一组，凭空造出循环。
                val call = callsById[message.id] ?: return@forEach
                records.add(
                    ToolLoopRecord(
                        toolName = message.toolName,
                        argsKey = loopArgsKey(message.toolName, call),
                        // 模型实际看到的是 modelResult（若有），判定也跟着它走，避免 UI 附注差异造成漏判。
                        resultHash = sha256Hex(message.modelResult ?: message.result)
                    )
                )
            }
        }
    }
    return records
}

/** 调用键：工具名 + 参数（按键排序的稳定 JSON）。 */
private fun loopArgsKey(toolName: String, call: ToolCall): String {
    // tool_title 只是给工具卡看的标题，模型每次措辞都不同；算进调用键会让同一操作的每次重复
    // 都变成「不同调用」，判重与熔断全部漏判。
    val sorted = call.arguments.filterKeys { it != TOOL_TITLE_PARAM }.toSortedMap()
    return toolName + "\u0000" + JsonObject(sorted).toString()
}

private fun sha256Hex(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
