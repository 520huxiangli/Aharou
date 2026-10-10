package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.permission.PermissionChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一批待授权的工具调用：同一回合并发的多个 tool_call 合并成一个弹窗一次性审批。
 * [items] 保持模型返回的原始顺序，[sessionId] 用于多会话并行时区分弹窗归属。
 */
data class PendingPermissionBatch(
    val id: String,
    val sessionId: String,
    val items: List<PendingToolPermission>
)

@Singleton
class ToolPermissionManager @Inject constructor() {
    /**
     * 所有待决授权请求，按插入顺序保存（key = 请求 id）。多会话并行时每个会话可各自挂起一个请求，
     * 互不阻塞——这样侧边栏才能同时点亮所有等待授权的会话。同一会话内的权限请求由 workflow 串行处理，
     * 故一个会话在此表中至多出现一次。
     */
    private val pending = LinkedHashMap<String, Entry>()

    /** 所有待决批量授权，按插入顺序保存（key = 批次 id）。批量路径与单条路径互不影响。 */
    private val pendingBatches = LinkedHashMap<String, BatchEntry>()
    private val lock = Any()

    /** 弹窗展示用：当前应展示的单个请求（取最早进入的），解决后自动切到下一个。 */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** 批量弹窗展示用：当前应展示的批量请求（取最早进入的），解决后自动切到下一个。 */
    private val _pendingBatch = MutableStateFlow<PendingPermissionBatch?>(null)
    val pendingBatch: StateFlow<PendingPermissionBatch?> = _pendingBatch.asStateFlow()

    /** 侧边栏用：当前正在等待授权的会话 id 集合。 */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /** 挂起等待用户在弹窗中的选择（拒绝/本次/始终）。不同会话可并行挂起，互不阻塞。 */
    suspend fun awaitApproval(request: PendingToolPermission): PermissionChoice {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /** UI 回传用户选择，唤醒对应 id 的挂起请求。 */
    fun resolve(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pending[id] } ?: return
        entry.decision.complete(choice)
    }

    /**
     * 批量挂起等待用户选择，返回「tool_call id → 选择」的映射。整批只挂起一次、只清理一次；
     * 不同会话的批量请求可并行挂起，互不阻塞。
     */
    suspend fun awaitBatchApproval(batch: PendingPermissionBatch): Map<String, PermissionChoice> {
        val decision = CompletableDeferred<Map<String, PermissionChoice>>()
        synchronized(lock) {
            pendingBatches[batch.id] = BatchEntry(batch, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                pendingBatches.remove(batch.id)
                publish()
            }
        }
    }

    /** UI 回传批量选择：整批同判。 */
    fun resolveBatch(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pendingBatches[id] } ?: return
        entry.decision.complete(entry.batch.items.associate { it.id to choice })
    }

    /** UI 回传批量选择：逐项判定，缺项按拒绝处理。 */
    fun resolveBatch(id: String, decisions: Map<String, PermissionChoice>) {
        val entry = synchronized(lock) { pendingBatches[id] } ?: return
        entry.decision.complete(
            entry.batch.items.associate { it.id to (decisions[it.id] ?: PermissionChoice.REJECT) }
        )
    }

    /** 取某会话当前的待决请求（stopAgent 停止单个会话时用，避免误取其它会话的弹窗）。 */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    /** 取某会话当前的待决批量请求（强打断结算时用，避免误取其它会话的弹窗）。 */
    fun pendingBatchForSession(sessionId: String): PendingPermissionBatch? =
        synchronized(lock) { pendingBatches.values.firstOrNull { it.batch.sessionId == sessionId }?.batch }

    /** 在持锁区内调用：把内部表投影到对外 StateFlow。 */
    private fun publish() {
        _pendingRequest.value = pending.values.firstOrNull()?.request
        _pendingBatch.value = pendingBatches.values.firstOrNull()?.batch
        val singleIds = pending.values.mapNotNull { it.request.sessionId.ifBlank { null } }
        val batchIds = pendingBatches.values.mapNotNull { it.batch.sessionId.ifBlank { null } }
        _awaitingSessionIds.value = (singleIds + batchIds).toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )

    private class BatchEntry(
        val batch: PendingPermissionBatch,
        val decision: CompletableDeferred<Map<String, PermissionChoice>>
    )
}
