package com.aharou.feature.agent.domain.tool.question

/**
 * AI 调用 ask_user_question 工具后，挂起等待用户回答的请求对象。
 *
 * @param id 本次调用的唯一标识（对应 ToolCall.id），用于 resolve 时配对。
 * @param questions 本次要问用户的 1-4 个问题。
 * @param sessionId 发起该问题的会话 id；用于在通知栏标注归属、点通知回到对应会话。
 */
data class PendingUserQuestion(
    val id: String,
    val questions: List<QuestionItem>,
    val sessionId: String = ""
)

/**
 * 单个问题。
 *
 * @param question 完整问题文本，如「该用哪个库做 HTTP 请求？」
 * @param header 短标签（≤12 字符），展示为 Chip/Tag，如「库」「方案」。
 * @param options 2-4 个预设选项（UI 会自动追加一个「其他」选项）。
 * @param multiSelect 是否允许多选。false = 单选 RadioButton，true = 多选 Checkbox。
 */
data class QuestionItem(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false
)

/**
 * 单个选项。
 *
 * @param label 选项标签（简短 1-5 个词），如「OkHttp（推荐）」。
 * @param description 选项说明，解释选它的含义或利弊。
 */
data class QuestionOption(
    val label: String,
    val description: String
)

/**
 * 用户对一次 ask_user_question 的全部回答。
 *
 * @param answers 每个问题对应一个 [SingleAnswer]。若用户点了「补充」，此列表为空。
 */
data class UserQuestionAnswer(
    val answers: List<SingleAnswer>
)

/**
 * 对单个问题的回答。
 *
 * @param question 原问题文本（便于 AI 对应回答是针对哪个问题的）。
 * @param selected 用户选中的选项 label 列表（单选=1 个元素，多选可能多个）。
 * @param customText 若用户选了「其他」并输入了自定义文本，存于此字段；否则为 null。
 */
data class SingleAnswer(
    val question: String,
    val selected: List<String>,
    val customText: String? = null
)

/**
 * `askUserQuestion` 的等待结果。
 *
 * 提问可能等不到人：进程被杀后恢复的旧问题没人接续、用户长时间离开。工具侧必须拿到明确结果，
 * 不能永久挂起等一个也许永远不会来的回答。
 */
sealed interface AskUserQuestionResult {
    data class Answered(val answer: UserQuestionAnswer) : AskUserQuestionResult

    /** 超过最长等待上限仍未作答；[timeoutMs] 即该上限，供工具侧向模型说明。 */
    data class Timeout(val timeoutMs: Long) : AskUserQuestionResult
}
