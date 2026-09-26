package com.aharou.feature.agent.domain.tool.mode

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理计划审查流程的挂起/恢复：
 * 当 AI 从 PLAN 模式切回 BUILD 模式时，workflow 调用 [awaitApproval] 挂起，
 * 等 UI 上的计划审查面板得到用户决策后恢复。
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) {
    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentDecision: CompletableDeferred<PlanApprovalChoice>? = null
    private var currentSessionId: String? = null

    /** 挂起等待用户在计划审查面板中的决策。 */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val decision = CompletableDeferred<PlanApprovalChoice>()
        currentDecision = decision
        currentSessionId = sessionId
        _pendingApproval.value = PlanApprovalRequest(reason = reason)

        try {
            return decision.await()
        } catch (e: CancellationException) {
            // 用户未批准切换（点「停止」等取消路径）：SwitchModeTool 已把目标模式写入 DB，
            // 取消意味着未获批准，需回滚，否则模式会未经批准直接切过去。
            rollbackModeToPlan(sessionId)
            throw e
        } finally {
            currentDecision = null
            currentSessionId = null
            _pendingApproval.value = null
        }
    }

    /** UI 回传用户选择，唤醒挂起的 [awaitApproval]。 */
    fun resolve(choice: PlanApprovalChoice) {
        _pendingApproval.value = null

        // 用户拒绝时，回滚数据库中的模式到 PLAN（SwitchModeTool 已写入 BUILD）
        if (choice == PlanApprovalChoice.REFINE) {
            rollbackModeToPlan(currentSessionId)
        }

        currentDecision?.complete(choice)
    }

    /** 回滚会话模式到 PLAN（SwitchModeTool 已提前写入目标模式，未获批准时还原）。 */
    private fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        scope.launch {
            val entity = chatSessionDao.getById(sid)
            if (entity != null && entity.mode != "PLAN") {
                chatSessionDao.upsert(entity.copy(mode = "PLAN"))
            }
        }
    }
}

data class PlanApprovalRequest(val reason: String)

enum class PlanApprovalChoice {
    /** 批准计划，切换到 BUILD 模式执行 */
    APPROVE,
    /** 拒绝，留在 PLAN 模式继续反馈 */
    REFINE
}
