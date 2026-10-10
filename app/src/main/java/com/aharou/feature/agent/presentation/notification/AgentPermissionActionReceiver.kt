package com.aharou.feature.agent.presentation.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 工具授权通知的动作接收器：把通知栏的「允许 / 拒绝 / 直接回复」回填到 App 内同一套授权入口。
 *
 * 只接收 [AgentPermissionNotifier] 用显式 PendingIntent 发来的广播，用 action 区分动作；
 * 「允许」等价于弹窗的单次放行（[PermissionChoice.ONCE]），「拒绝」等价于 [PermissionChoice.REJECT]，
 * 二者都调 [ToolPermissionManager.resolve]（批量时为 [ToolPermissionManager.resolveBatch]）唤醒挂起的
 * 授权等待，不另造逻辑。
 *
 * 声明为 exported=false（无 intent-filter，仅接收本 App 的显式广播），避免外部伪造动作。
 */
@AndroidEntryPoint
class AgentPermissionActionReceiver : BroadcastReceiver() {

    @Inject lateinit var toolPermissionManager: ToolPermissionManager

    @Inject lateinit var notifier: AgentPermissionNotifier

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgentPermissionNotifier.ACTION_ALLOW -> resolve(intent, PermissionChoice.ONCE)
            AgentPermissionNotifier.ACTION_REJECT -> resolve(intent, PermissionChoice.REJECT)
            AgentPermissionNotifier.ACTION_REPLY -> {
                val sessionId = intent.getStringExtra(AgentPermissionNotifier.EXTRA_SESSION_ID).orEmpty()
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AgentPermissionNotifier.KEY_REPLY)
                    ?.toString()?.trim().orEmpty()
                notifier.emitReply(sessionId, text)
                notifier.refresh(sessionId)
            }
            else -> Unit
        }
    }

    private fun resolve(intent: Intent, choice: PermissionChoice) {
        val requestId = intent.getStringExtra(AgentPermissionNotifier.EXTRA_REQUEST_ID) ?: return
        val isBatch = intent.getBooleanExtra(AgentPermissionNotifier.EXTRA_IS_BATCH, false)
        FileLogger.i(TAG, "通知栏授权动作: choice=$choice request=$requestId batch=$isBatch")
        if (isBatch) toolPermissionManager.resolveBatch(requestId, choice)
        else toolPermissionManager.resolve(requestId, choice)
    }

    private companion object {
        const val TAG = "AgentPermReceiver"
    }
}
