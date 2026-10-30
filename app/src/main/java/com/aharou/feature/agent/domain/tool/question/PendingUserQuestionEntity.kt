package com.aharou.feature.agent.domain.tool.question

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待答问题的落库形态：进程被杀后靠它把问题重新亮出来。
 *
 * 状态取 [UserQuestionStatus] 的名字落成文本（PENDING / ANSWERED / CANCELLED），只有 PENDING 会被恢复。
 * 问题与答案以 JSON 存 [questionsJson] / [answerJson]（结构见 [QuestionJsonCodec]），
 * 这样提问结构变化时无需改表。
 *
 * 索引 (status, createdAt) 覆盖「取最近一条 PENDING」与「按时间清理终态行」两个查询。
 */
@Entity(
    tableName = "pending_user_questions",
    indices = [Index(value = ["status", "createdAt"])]
)
data class PendingUserQuestionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val questionsJson: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val answeredAt: Long? = null,
    val answerJson: String? = null,
    /** 非超时的终结原因（被新提问顶替、恢复后超龄等），便于排查「问题为什么自己没了」。 */
    val resolveReason: String? = null
)

/** 待答问题的生命周期状态。 */
enum class UserQuestionStatus {
    PENDING,
    ANSWERED,
    CANCELLED
}
