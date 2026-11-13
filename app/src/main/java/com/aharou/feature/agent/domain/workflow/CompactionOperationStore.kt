package com.aharou.feature.agent.domain.workflow

import androidx.room.withTransaction
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文压缩的留痕与撤销。
 *
 * 压缩提交时原始消息只是被标成 isCompacted（不是物理删除），所以撤销的主路径是「清标记 + 删摘要」；
 * [snapshotJson] 是另一层保险：万一将来改成物理删除、或被回退路径扫掉，仍能按原时间戳补回原位。
 * 顺序一律靠原有 timestamp 恢复，不追加到末尾——追加会让摘要在历史里的位置失真。
 */
@Singleton
class CompactionOperationStore @Inject constructor(
    private val database: AgentDatabase,
    private val agentMessageDao: AgentMessageDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase
) {
    /**
     * 惰性从数据库取 DAO：`di/AgentModule.kt` 有按 DAO 逐个 @Provides 的惯例，但那个文件不归本改动管，
     * 加一条绑定要动共享的 DI 文件；直接向已注入的 AgentDatabase 要实例同样由 Room 缓存，没有额外开销。
     */
    private val operationDao: ContextOperationDao get() = database.contextOperationDao()
    internal companion object {
        const val TAG = "CompactionOperation"

        /**
         * 单条快照的落库上限。工具输出单行可达上百 KB，长会话压一次几十 MB 也正常；
         * 超过就只记元数据，宁可放弃这一次撤销，也不让一次压缩把数据库撑爆。
         */
        const val MAX_SNAPSHOT_BYTES = 4L * 1024 * 1024

        /** SQLite 单条语句的绑定变量上限是 999，留出余量后分片。 */
        private const val ID_CHUNK = 900
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 在替换历史之前记一条操作。[removedMessageIds] 就是即将被压缩掉的那批消息。
     * 返回操作 id；失败返回 null——记录失败不能挡住压缩本身，只是这一次没有撤销机会。
     */
    suspend fun record(
        sessionId: String,
        markerMessageId: String,
        summaryMessageId: String,
        removedMessageIds: List<String>
    ): String? {
        if (removedMessageIds.isEmpty()) return null
        return try {
            val rows = removedMessageIds.chunked(ID_CHUNK)
                .flatMap { operationDao.messagesByIds(sessionId, it) }
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
            val encoded = json.encodeToString(rows.map { it.toSnapshot() })
            val bytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
            val id = UUID.randomUUID().toString()
            operationDao.insert(
                ContextOperationEntity(
                    id = id,
                    sessionId = sessionId,
                    createdAt = System.currentTimeMillis(),
                    kind = ContextOperationEntity.KIND_COMPRESS,
                    summaryMessageId = summaryMessageId,
                    markerMessageId = markerMessageId,
                    removedMessageIds = json.encodeToString(removedMessageIds),
                    // 超限时只留大小：撤销入口据此告诉用户「这次压缩没有快照」。
                    snapshotJson = encoded.takeIf { bytes <= MAX_SNAPSHOT_BYTES },
                    snapshotBytes = bytes
                )
            )
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "记录上下文操作失败（压缩照常提交）：session=$sessionId", e)
            null
        }
    }

    /** 压缩提交失败时把它标成已撤销，免得一条没有对应摘要的记录成为「最近一次压缩」。 */
    suspend fun markRevoked(operationId: String?) {
        val id = operationId ?: return
        try {
            operationDao.markRevoked(id, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "标记上下文操作已撤销失败：id=$id", e)
        }
    }

    /** 本会话最近一条未撤销的操作（含不可撤销的，界面要能解释为什么撤不了）。 */
    suspend fun latest(sessionId: String): CompactionOperation? =
        operationDao.latestActive(sessionId)?.toOperation()

    /** 本会话最近一条可撤销的压缩操作。 */
    suspend fun latestRevertible(sessionId: String): CompactionOperation? =
        latest(sessionId)?.takeIf { it.revertible }

    /**
     * 撤销一次压缩：清掉这次压缩的标记、删掉摘要与锚点、把被物理删掉的行按快照补回。
     * 三步在同一个事务里，任一步失败整体回滚（含撤销标记本身）。
     *
     * 只能撤销最近一次：更早的压缩之后又来了一次压缩，恢复旧摘要会让中间那段历史重新落回摘要里，
     * 语义对不上。
     */
    suspend fun revertCompaction(operationId: String): Result<Unit> {
        return try {
            val operation = operationDao.byId(operationId)
                ?: throw CompactionRevertException(CompactionRevertFailure.NOT_FOUND)
            if (operation.revoked) throw CompactionRevertException(CompactionRevertFailure.ALREADY_REVERTED)
            val latest = operationDao.latestActive(operation.sessionId)
            if (latest?.id != operation.id) throw CompactionRevertException(CompactionRevertFailure.NOT_LATEST)
            val snapshotJson = operation.snapshotJson
                ?: throw CompactionRevertException(CompactionRevertFailure.SNAPSHOT_UNAVAILABLE)
            val summaryId = operation.summaryMessageId
                ?: throw CompactionRevertException(CompactionRevertFailure.SNAPSHOT_UNAVAILABLE)
            val removed = decodeIds(operation.removedMessageIds)
            val snapshot = try {
                json.decodeFromString<List<SnapshotMessage>>(snapshotJson)
            } catch (e: Exception) {
                throw CompactionRevertException(CompactionRevertFailure.SNAPSHOT_CORRUPTED)
            }

            database.withTransaction {
                operationDao.restoreCompactedBySummary(operation.sessionId, summaryId)
                // 行通常都还在（压缩只清标记），这里只补真正缺失的，按原 timestamp 插回原位置。
                val existing = removed.chunked(ID_CHUNK)
                    .flatMap { operationDao.messagesByIds(operation.sessionId, it) }
                    .mapTo(HashSet()) { it.id }
                val missing = snapshot.filter { it.id !in existing }
                if (missing.isNotEmpty()) agentMessageDao.insertAll(missing.map { it.toEntity() })
                operationDao.deleteMessagesByIds(listOfNotNull(operation.markerMessageId, summaryId))
                agentMessageDao.resetLastInputTokens(operation.sessionId)
                check(operationDao.markRevoked(operation.id, System.currentTimeMillis()) == 1) {
                    "Context operation ${operation.id} was already revoked"
                }
            }
            messagePersistenceUseCase.invalidateHistory(operation.sessionId)
            FileLogger.i(TAG, "已撤销上下文压缩：session=${operation.sessionId}，恢复 ${removed.size} 条消息")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "撤销上下文压缩失败：id=$operationId", e)
            Result.failure(e)
        }
    }

    private fun decodeIds(raw: String): List<String> = try {
        json.decodeFromString<List<String>>(raw)
    } catch (e: Exception) {
        FileLogger.w(TAG, "上下文操作的消息 id 列表解析失败，按空处理", e)
        emptyList()
    }

    private fun ContextOperationEntity.toOperation(): CompactionOperation = CompactionOperation(
        id = id,
        sessionId = sessionId,
        createdAt = createdAt,
        summaryMessageId = summaryMessageId,
        removedMessageIds = decodeIds(removedMessageIds),
        snapshotBytes = snapshotBytes,
        hasSnapshot = snapshotJson != null
    )

    /**
     * 落库形态的快照。直接序列化 Room 实体做不到（没标 @Serializable），
     * 而绑定序列化框架到实体本身正是备份层刻意避开的事；这里逐列抄一份，缺列一律带默认值，
     * 以后加列时旧快照仍能解出来。
     */
    @Serializable
    private data class SnapshotMessage(
        val id: String,
        val sessionId: String,
        val role: String,
        val content: String,
        val timestamp: Long,
        val toolCallsJson: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val isError: Boolean = false,
        val reasoning: String? = null,
        val signature: String? = null,
        val attachmentsJson: String? = null,
        val isCompacted: Boolean = false,
        val isContextSummary: Boolean = false,
        val isCompactionMarker: Boolean = false,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val thinkingBlocksJson: String? = null,
        val cachedInputTokens: Int = 0,
        val isContextExcluded: Boolean = false,
        val compactedBySummaryId: String? = null,
        val error: String? = null,
        val variantGroupId: String? = null,
        val variantIndex: Int = 0
    )

    private fun AgentMessageEntity.toSnapshot(): SnapshotMessage = SnapshotMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        timestamp = timestamp,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgs = toolArgs,
        isError = isError,
        reasoning = reasoning,
        signature = signature,
        attachmentsJson = attachmentsJson,
        isCompacted = isCompacted,
        isContextSummary = isContextSummary,
        isCompactionMarker = isCompactionMarker,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        thinkingBlocksJson = thinkingBlocksJson,
        cachedInputTokens = cachedInputTokens,
        isContextExcluded = isContextExcluded,
        compactedBySummaryId = compactedBySummaryId,
        error = error,
        variantGroupId = variantGroupId,
        variantIndex = variantIndex
    )

    private fun SnapshotMessage.toEntity(): AgentMessageEntity = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        timestamp = timestamp,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgs = toolArgs,
        isError = isError,
        reasoning = reasoning,
        signature = signature,
        attachmentsJson = attachmentsJson,
        isCompacted = isCompacted,
        isContextSummary = isContextSummary,
        isCompactionMarker = isCompactionMarker,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        thinkingBlocksJson = thinkingBlocksJson,
        cachedInputTokens = cachedInputTokens,
        isContextExcluded = isContextExcluded,
        compactedBySummaryId = compactedBySummaryId,
        error = error,
        variantGroupId = variantGroupId,
        variantIndex = variantIndex
    )
}
