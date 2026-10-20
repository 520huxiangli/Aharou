package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一轮 AI 结束后要做的旁路动作分发：托管判定（交给 [StewardBridge]）+ 临时终端标签清理。
 *
 * 为什么是「分发器」而不是在 [AIAgentViewModel] 里再写一行调用：那个 ViewModel 顶在架构门禁的
 * 行数基线上（零余量），新增收尾动作塞不进去。于是把原本唯一的 [StewardBridge.onTurnFinished]
 * 调用点升级为这一层——托管逻辑原样转交，终端清理挂在这里，之后再有收尾动作也往这儿加，
 * ViewModel 侧始终保持同一个调用点。
 *
 * 清理只动「AI 创建 + 一次性任务（notify=true）+ 已结束」的标签：用户手动开的标签、dev server
 * 之类的常驻服务、还在跑的标签一律不碰。开关关闭时整段短路，行为完全回到「标签一直留着」。
 */
@Singleton
class AgentTurnEndBridge @Inject constructor(
    private val stewardBridge: StewardBridge,
    private val terminalSessions: TerminalSessionProvider,
    private val terminalSettings: TerminalSettingsRepository,
) {
    private companion object {
        const val TAG = "AgentTurnEndBridge"
    }

    /**
     * 清理跑在自己持有的 scope 上：ViewModel 在回合收尾后可能马上被清掉（切走聊天页/退出），
     * 挂在 viewModelScope 上会被连同取消，标签就留在那儿了。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 一轮结束的收尾入口（语义与 [StewardBridge.onTurnFinished] 一致，多做了终端清理）。 */
    fun onTurnFinished(scope: CoroutineScope, sessionId: String, send: (String) -> Unit) {
        stewardBridge.onTurnFinished(scope, sessionId, send)
        closeFinishedAgentTabs(sessionId)
    }

    private fun closeFinishedAgentTabs(sessionId: String) {
        cleanupScope.launch {
            val enabled = runCatching { terminalSettings.autoCloseFinishedAgentTabs() }
                .onFailure { FileLogger.w(TAG, "读取终端自动清理开关失败，跳过本轮清理", it) }
                .getOrDefault(false)
            if (!enabled) return@launch
            // TerminalSessionManager 的可变状态按约定只在主线程读写。
            val closed = runCatching {
                withContext(Dispatchers.Main) { terminalSessions.closeFinishedAgentTabs() }
            }.onFailure {
                FileLogger.e(TAG, "回合结束自动清理终端标签失败（会话 $sessionId）", it)
            }.getOrDefault(0)
            if (closed > 0) {
                FileLogger.i(TAG, "会话 $sessionId 回合结束，自动关闭 ${closed} 个已结束的临时终端标签")
            }
        }
    }
}
