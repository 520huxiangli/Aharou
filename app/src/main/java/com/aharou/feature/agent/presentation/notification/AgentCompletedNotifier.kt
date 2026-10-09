package com.aharou.feature.agent.presentation.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 一轮跑完后的系统通知（App 在后台时才发）。
 *
 * 与 [AgentPermissionNotifier] 一样按「屏幕上方横幅」构造：渠道 importance 已是 HIGH 还不够，
 * 通知本身必须带上声音/震动这类「提醒」，否则多数 ROM 只把它静默塞进通知栏，不会弹 heads-up。
 *
 * 「回复」动作复用 [AgentPermissionActionReceiver] 的 REPLY 分支（文本经 [AgentPermissionNotifier]
 * 的 replies 流回到会话层、按会话重开一轮），因此不需要另建接收器。
 */
@Singleton
class AgentCompletedNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 发一条「任务完成」横幅通知；[body] 由调用方按当前语言与任务内容拼好。 */
    fun notify(sessionId: String, body: CharSequence) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(replyAction(sessionId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    private fun replyAction(sessionId: String) = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send,
        context.getString(R.string.agent_perm_action_reply),
        PendingIntent.getBroadcast(
            context,
            sessionId.hashCode(),
            Intent(context, AgentPermissionActionReceiver::class.java)
                .setAction(AgentPermissionNotifier.ACTION_REPLY)
                .putExtra(AgentPermissionNotifier.EXTRA_SESSION_ID, sessionId),
            // RemoteInput 的输入由系统写回 Intent，必须用可变 PendingIntent。
            mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).addRemoteInput(
        RemoteInput.Builder(AgentPermissionNotifier.KEY_REPLY)
            .setLabel(context.getString(R.string.agent_perm_reply_hint))
            .build()
    ).setAllowGeneratedReplies(false).build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private val mutableFlag: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private companion object {
        const val TAG = "AgentCompleted"

        /** 与 AIEditorApp 里创建的完成通知渠道保持一致。 */
        const val CHANNEL_ID = "agent_complete"

        /** 与旧的 VM 常量取值一致，覆盖同一条「任务完成」通知而不是叠出两条。 */
        const val NOTIFICATION_ID = 100

        private const val REQUEST_OPEN_APP = 100000
    }
}
