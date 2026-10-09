package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.domain.model.ReasoningEffort

@Entity(
    tableName = "chat_sessions",
    // (parentId, isPinned, updatedAt)：parentId 用于子会话查询与 getAllRootSessions 的 parentId IS NULL 过滤，
    // 后两列让根会话列表（按置顶/更新时间排序）走索引顺序，免去全表扫描 + 临时排序。
    indices = [Index(value = ["workspacePath"]), Index(value = ["parentId", "isPinned", "updatedAt"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = AgentMode.BUILD.name,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: String? = null,
    val reasoningEffort: String = ReasoningEffort.DEFAULT.name,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** 隐身模式：开启后本会话消息不落库、记忆不写入，关闭或切走即不留痕。 */
    val ghostMode: Boolean = false
) {
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        mode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.BUILD),
        modeBeforePlan = modeBeforePlan?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() },
        reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(ReasoningEffort.DEFAULT),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        parentId = parentId,
        subagentType = subagentType,
        ghostMode = ghostMode
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            workspacePath = session.workspacePath,
            mode = session.mode.name,
            modeBeforePlan = session.modeBeforePlan?.name,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens,
            isPinned = session.isPinned,
            parentId = session.parentId,
            subagentType = session.subagentType,
            ghostMode = session.ghostMode
        )
    }
}
