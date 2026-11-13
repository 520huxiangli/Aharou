package com.aharou.feature.agent.domain.workflow

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity

/**
 * 一次上下文操作的留痕。目前只有压缩（[KIND_COMPRESS]）会写它，撤销入口据此把被压缩掉的
 * 原始消息恢复回去。
 *
 * 放在 domain 包而不是 `data/local/entity`：与 `PendingUserQuestionEntity` 一样，这张表只服务
 * 一个功能域，跟着它的读写逻辑走比堆进 data 层更容易找到。
 */
@Entity(
    tableName = "context_operations",
    // 撤销入口只查「本会话最近一条未撤销」的记录，(sessionId, createdAt) 直接给出有序结果；
    // (sessionId, revoked) 供「这个会话还有没有可撤销的压缩」这类判断走索引。
    indices = [Index(value = ["sessionId", "createdAt"]), Index(value = ["sessionId", "revoked"])]
)
data class ContextOperationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val kind: String,
    /** 压缩后插入的摘要消息 id：撤销时要删掉它。 */
    val summaryMessageId: String? = null,
    /** 压缩后插入的用户锚点消息 id（UI 上的压缩分隔线），撤销时一并删掉。 */
    val markerMessageId: String? = null,
    /** 被这次操作移出上下文的原始消息 id 列表（JSON 数组文本）。 */
    val removedMessageIds: String,
    /** 原始消息的完整落库快照；超过上限时为空，只留 [snapshotBytes] 作说明。 */
    val snapshotJson: String? = null,
    val snapshotBytes: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val revoked: Boolean = false,
    val revokedAt: Long? = null
) {
    companion object {
        const val KIND_COMPRESS = "compress"
    }
}

/**
 * 撤销需要的消息表读写与 `context_operations` 的读写放在同一个 DAO 里：撤销必须是一次事务，
 * 把跨表语句摊到两个 DAO 里，调用方很容易只调一半就提交。
 *
 * `agent_messages` 的这几条语句本可以放进 `AgentMessageDao`，但那里已有 `restoreCompactedMessagesBefore`
 * 这类按时间窗恢复的旧路径，两套恢复语义挨在一起容易被混用；等旧路径清理掉再合并。
 */
@Dao
interface ContextOperationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: ContextOperationEntity)

    @Query("SELECT * FROM context_operations WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ContextOperationEntity?

    /** 本会话最近一条未撤销的操作（不限于可撤销的：快照过大的记录也要能查到并解释原因）。 */
    @Query("SELECT * FROM context_operations WHERE sessionId = :sessionId AND revoked = 0 ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestActive(sessionId: String): ContextOperationEntity?

    @Query("UPDATE context_operations SET revoked = 1, revokedAt = :revokedAt WHERE id = :id AND revoked = 0")
    suspend fun markRevoked(id: String, revokedAt: Long): Int

    /** 按 id 取原始消息行；[ids] 需要调用方按 SQLite 变量上限分片。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND id IN (:ids) ORDER BY timestamp ASC, id ASC")
    suspend fun messagesByIds(sessionId: String, ids: List<String>): List<AgentMessageEntity>

    /**
     * 只清掉「归属这次摘要」的压缩标记。用 compactedBySummaryId 而不是 removedMessageIds 过滤：
     * 之后又被更新的压缩标记过的行归属已经变了，撤销旧记录不该把它们的标记也清掉。
     */
    @Query("UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL WHERE sessionId = :sessionId AND compactedBySummaryId = :summaryId")
    suspend fun restoreCompactedBySummary(sessionId: String, summaryId: String): Int

    @Query("DELETE FROM agent_messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)
}

/** 一次压缩在撤销入口眼里的样子。[revertible] 为 false 时说明快照没存全文。 */
data class CompactionOperation(
    val id: String,
    val sessionId: String,
    val createdAt: Long,
    val summaryMessageId: String?,
    val removedMessageIds: List<String>,
    val snapshotBytes: Long,
    val hasSnapshot: Boolean
) {
    val revertible: Boolean get() = hasSnapshot
}

/**
 * 撤销失败的原因用枚举而不是现成文案：文案要进双语 strings.xml，由界面层决定怎么显示。
 */
enum class CompactionRevertFailure {
    NOT_FOUND,
    ALREADY_REVERTED,
    NOT_LATEST,
    SNAPSHOT_UNAVAILABLE,
    SNAPSHOT_CORRUPTED
}

class CompactionRevertException(val failure: CompactionRevertFailure) : IllegalStateException(failure.name)
