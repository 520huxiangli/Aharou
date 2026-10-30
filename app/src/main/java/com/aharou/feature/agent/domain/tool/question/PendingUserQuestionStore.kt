package com.aharou.feature.agent.domain.tool.question

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.database.AgentDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待答问题的持久化入口：把 [AskUserQuestionManager] 的内存状态镜像到 `pending_user_questions`。
 *
 * 落库只是「重启后还能找回」，不是提问的前置条件——任何一步失败都只记日志，
 * 让提问按原样继续，避免数据库问题把交互链路一起拖死。
 */
@Singleton
class PendingUserQuestionStore @Inject constructor(
    private val database: AgentDatabase
) {

    private companion object {
        const val TAG = "QuestionStore"

        /** 终态行的保留期：够排查用，又不至于让表无限增长。 */
        const val RESOLVED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** 从库读回的待答问题 + 它的创建时间（用于算剩余可等待时长）。 */
    data class Restored(val question: PendingUserQuestion, val createdAt: Long)

    private val dao get() = database.pendingUserQuestionDao()

    suspend fun persist(question: PendingUserQuestion) {
        try {
            val now = System.currentTimeMillis()
            dao.upsert(
                PendingUserQuestionEntity(
                    id = question.id,
                    sessionId = question.sessionId,
                    questionsJson = QuestionJsonCodec.encodeQuestions(question.questions),
                    status = UserQuestionStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now
                )
            )
            dao.deleteResolvedBefore(now - RESOLVED_RETENTION_MS)
        } catch (e: Exception) {
            FileLogger.w(TAG, "待答问题落库失败 id=${question.id}: ${e.message}")
        }
    }

    /** 读最近一条待答问题；读不到或结构已无法重建时返回 null。 */
    suspend fun latestPending(): Restored? {
        val row = try {
            dao.latestPending() ?: return null
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取待答问题失败: ${e.message}")
            return null
        }
        val questions = QuestionJsonCodec.decodeQuestions(row.questionsJson) ?: run {
            FileLogger.w(TAG, "待答问题 ${row.id} 内容无法解析，判失效")
            markCancelled(row.id, "内容无法解析")
            return null
        }
        return Restored(
            question = PendingUserQuestion(id = row.id, questions = questions, sessionId = row.sessionId),
            createdAt = row.createdAt
        )
    }

    suspend fun markAnswered(id: String, answer: UserQuestionAnswer) {
        try {
            val now = System.currentTimeMillis()
            dao.markResolved(
                id = id,
                status = UserQuestionStatus.ANSWERED.name,
                updatedAt = now,
                answeredAt = now,
                answerJson = QuestionJsonCodec.encodeAnswer(answer),
                reason = null
            )
        } catch (e: Exception) {
            FileLogger.w(TAG, "标记已作答失败 id=$id: ${e.message}")
        }
    }

    suspend fun markCancelled(id: String, reason: String) {
        try {
            dao.markResolved(
                id = id,
                status = UserQuestionStatus.CANCELLED.name,
                updatedAt = System.currentTimeMillis(),
                answeredAt = null,
                answerJson = null,
                reason = reason
            )
        } catch (e: Exception) {
            FileLogger.w(TAG, "标记作废失败 id=$id: ${e.message}")
        }
    }

    /** 已作答但没送回会话的答案。 */
    data class Answered(val id: String, val answer: UserQuestionAnswer)

    /**
     * 取出并删除该会话「已作答但无人接续」的答案。
     *
     * 进程被杀时正在等待的 agent 协程随之消失，之后的作答只能落库；不把它们送回会话，
     * 用户会看到自己的选择石沉大海，模型也永远不知道自己被答过什么。
     */
    suspend fun takeAnswered(sessionId: String): List<Answered> {
        val rows = try {
            dao.answeredForSession(sessionId, UserQuestionStatus.ANSWERED.name)
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取已答提问失败 session=$sessionId: ${e.message}")
            return emptyList()
        }
        if (rows.isEmpty()) return emptyList()
        val taken = rows.mapNotNull { row ->
            row.answerJson?.let(QuestionJsonCodec::decodeAnswer)?.let { Answered(row.id, it) }
        }
        try {
            dao.deleteByIds(rows.map { it.id })
        } catch (e: Exception) {
            // 删不掉只会有重复播报风险，比丢答案轻，留给终态清理兜底。
            FileLogger.w(TAG, "清理已答提问失败: ${e.message}")
        }
        return taken
    }
}
