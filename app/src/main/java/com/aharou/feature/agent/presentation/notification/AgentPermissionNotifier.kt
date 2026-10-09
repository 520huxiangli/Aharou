package com.aharou.feature.agent.presentation.notification

import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 用户在授权通知里「直接回复」的文本，按会话投递。 */
data class AgentPermissionReply(val sessionId: String, val text: String)

/**
 * 工具授权通知：某会话正在等待工具授权时，在通知栏展示一条可「允许 / 拒绝 / 直接回复」的通知，
 * 用户无需回到 App 即可决策。批准/拒绝后该会话离开等待集合，[sync] 会自动把通知撤下。
 *
 * 状态来自 [ToolPermissionManager]（进程内单例），因此通知动作不依赖界面是否在前台、也不依赖
 * 任何 Composable——只要进程还活着（等待授权期间前台保活服务会拉住进程），通知栏就能直接决策。
 * 动作统一由 [AgentPermissionActionReceiver] 广播回填，走与 App 内弹窗完全相同的
 * `resolve` 入口，避免另起一套逻辑导致状态分叉。
 *
 * 通知权限被拒时 [NotificationManagerCompat.notify] 会抛 SecurityException，这里静默吞掉，
 * 不影响原授权流程。
 */
@Singleton
class AgentPermissionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolPermissionManager: ToolPermissionManager,
    private val sessionUseCase: SessionUseCase
) {
    /** 通知栏「直接回复」的文本流：由 receiver 投递，会话层消费后按会话投递到对话链路。 */
    private val _replies = MutableSharedFlow<AgentPermissionReply>(extraBufferCapacity = 8)
    val replies: SharedFlow<AgentPermissionReply> = _replies.asSharedFlow()

    /** 已在通知栏展示的通知：会话 id → 通知 id。 */
    private val shown = mutableMapOf<String, Int>()

    /** 每个会话当前展示所需的请求与标题，供「直接回复」后重发以清空输入框。 */
    private val cache = mutableMapOf<String, Pair<PendingToolPermission, String>>()

    /** 同步等待授权的会话集合：为每个会话展示/更新通知，撤下已解决会话的通知。 */
    suspend fun sync(sessionIds: Set<String>) {
        ensureChannel()
        val active = sessionIds.filter { it.isNotBlank() }
        (shown.keys - active.toSet()).forEach { sid ->
            runCatching { NotificationManagerCompat.from(context).cancel(shown.getValue(sid)) }
            shown.remove(sid)
            cache.remove(sid)
        }
        active.forEach { sid ->
            val request = toolPermissionManager.pendingForSession(sid) ?: return@forEach
            // 请求未变化就不重发，避免其它会话进入等待时把已在展示的通知重复弹一次。
            if (cache[sid]?.first?.id == request.id) return@forEach
            shown.remove(sid)?.let { old ->
                runCatching { NotificationManagerCompat.from(context).cancel(old) }
            }
            val title = sessionTitle(sid)
            cache[sid] = request to title
            post(sid, request, title)
        }
    }

    /** 通知栏「直接回复」提交后重发一次通知，清空输入框里残留的文本。 */
    fun refresh(sessionId: String) {
        val entry = cache[sessionId] ?: return
        post(sessionId, entry.first, entry.second)
    }

    /** 通知栏「直接回复」的文本：由 receiver 投递，交给会话层消费。 */
    fun emitReply(sessionId: String, text: String) {
        if (sessionId.isBlank() || text.isBlank()) return
        if (!_replies.tryEmit(AgentPermissionReply(sessionId, text))) {
            FileLogger.w(TAG, "授权通知回复投递失败（缓冲已满）: sid=$sessionId")
        }
    }

    private suspend fun sessionTitle(sessionId: String): String =
        sessionUseCase.getSessionById(sessionId)?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.agent_perm_session_fallback)

    private fun post(sessionId: String, request: PendingToolPermission, sessionTitle: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_perm_notification_title, sessionTitle))
            .setContentText("${request.toolName} · ${request.title}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildString {
                append(request.summary)
                if (request.argsPreview.isNotBlank()) append('\n').append(request.argsPreview)
            }))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 渠道 importance 已是 HIGH，但通知不带「提醒」时多数 ROM 只静默入通知栏、不弹横幅。
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(replyAction(sessionId))
            .addAction(rejectAction(sessionId, request.id))
            .addAction(allowAction(sessionId, request.id))
            .build()
        val id = notificationId(sessionId, request.id)
        shown[sessionId] = id
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { FileLogger.w(TAG, "发送工具授权通知失败: sid=$sessionId") }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionIntent(sessionId: String, requestId: String, action: String): Intent =
        Intent(context, AgentPermissionActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_REQUEST_ID, requestId)

    private fun allowAction(sessionId: String, requestId: String) = NotificationCompat.Action(
        android.R.drawable.ic_menu_save,
        context.getString(R.string.agent_perm_action_allow),
        PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, requestId, ACTION_ALLOW),
            actionIntent(sessionId, requestId, ACTION_ALLOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun rejectAction(sessionId: String, requestId: String) = NotificationCompat.Action(
        android.R.drawable.ic_menu_close_clear_cancel,
        context.getString(R.string.agent_perm_action_reject),
        PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, requestId, ACTION_REJECT),
            actionIntent(sessionId, requestId, ACTION_REJECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun replyAction(sessionId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(context.getString(R.string.agent_perm_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.agent_perm_action_reply),
            PendingIntent.getBroadcast(
                context,
                requestCode(sessionId, "", ACTION_REPLY),
                Intent(context, AgentPermissionActionReceiver::class.java)
                    .setAction(ACTION_REPLY)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
                // RemoteInput 结果由系统写回 Intent，必须用可变的 PendingIntent。
                mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** 每个动作各自的 requestCode：同一会话的三种动作互不相同，不同会话也不撞。 */
    private fun requestCode(sessionId: String, requestId: String, action: String): Int =
        "$action|$sessionId|$requestId".hashCode()

    private fun notificationId(sessionId: String, requestId: String): Int =
        NOTIFICATION_BASE + ("$sessionId|$requestId".hashCode() and 0x7fffffff) % 100000

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_perm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.agent_perm_channel_desc)
                setShowBadge(true)
            }
        )
    }

    private val mutableFlag: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    companion object {
        const val CHANNEL_ID = "agent_permission"
        const val EXTRA_SESSION_ID = "com.aharou.extra.PERMISSION_SESSION_ID"
        const val EXTRA_REQUEST_ID = "com.aharou.extra.PERMISSION_REQUEST_ID"
        const val ACTION_ALLOW = "com.aharou.action.PERMISSION_ALLOW"
        const val ACTION_REJECT = "com.aharou.action.PERMISSION_REJECT"
        const val ACTION_REPLY = "com.aharou.action.PERMISSION_REPLY"
        const val KEY_REPLY = "com.aharou.extra.PERMISSION_REPLY_TEXT"
        private const val TAG = "AgentPermission"
        private const val NOTIFICATION_BASE = 210000
        private const val REQUEST_OPEN_APP = 210000
    }
}
