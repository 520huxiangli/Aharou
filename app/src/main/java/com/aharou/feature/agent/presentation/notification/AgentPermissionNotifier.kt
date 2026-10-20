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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aharou.feature.agent.domain.tool.question.PendingUserQuestion
import com.aharou.feature.widget.WidgetActions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
 * 另外两类待批事件——`askUserQuestion` 提问与退出 PLAN 的计划审批——必须回界面作答，
 * 这里只做后台提示（[syncPrompts]），点通知回到对应会话，不给动作按钮。
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

    /** 每个会话当前展示所需的条目 id 与标题，供「直接回复」后重发以清空输入框。 */
    private val cache = mutableMapOf<String, Cached>()

    private data class Cached(val id: String, val title: String)

    /** 等待用户「回界面处理」的两类待批事件（提问 / 计划审批）：通知只提示，不做动作。 */
    private var pendingQuestion: PendingUserQuestion? = null
    private var pendingPlanApproval: PlanApprovalRequest? = null

    /** 提示类通知的状态保护：状态同步与生命周期回调可能来自不同线程。 */
    private val promptLock = Any()

    /** 已展示的提示类通知：kind → (请求标识, 通知 id)。 */
    private val shownPrompts = mutableMapOf<String, Pair<String, Int>>()

    private val promptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一张提示类通知的内容快照。 */
    private data class PromptNotice(
        val kind: String,
        val sessionId: String,
        val requestId: String,
        val titleRes: Int,
        val body: String,
        val bigText: String
    )

    init {
        // 只在后台提示：前台不打扰，退回后台时补发，回到前台立即撤下。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_STOP -> applyPromptNotices()
                    Lifecycle.Event.ON_START -> cancelPromptNotices()
                    else -> Unit
                }
            }
        })
    }

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
            val batch = toolPermissionManager.pendingBatchForSession(sid)
            if (batch != null) {
                if (cache[sid]?.id == batch.id) return@forEach
                shown.remove(sid)?.let { old ->
                    runCatching { NotificationManagerCompat.from(context).cancel(old) }
                }
                val title = sessionTitle(sid)
                cache[sid] = Cached(batch.id, title)
                postBatch(sid, batch, title)
                return@forEach
            }
            val request = toolPermissionManager.pendingForSession(sid) ?: return@forEach
            // 请求未变化就不重发，避免其它会话进入等待时把已在展示的通知重复弹一次。
            if (cache[sid]?.id == request.id) return@forEach
            shown.remove(sid)?.let { old ->
                runCatching { NotificationManagerCompat.from(context).cancel(old) }
            }
            val title = sessionTitle(sid)
            cache[sid] = Cached(request.id, title)
            post(sid, request, title)
        }
    }

    /**
     * 同步「等待用户处理」的两类待批事件：askUserQuestion 提问与退出 PLAN 的计划审批。
     *
     * 这两类必须在界面里作答（选选项 / 批准计划），通知不提供动作按钮，只做提示并让点击回到
     * 对应会话；仅在 App 处于后台时展示，用户处理完（状态清空）或回到前台立即撤销。
     */
    fun syncPrompts(question: PendingUserQuestion?, planApproval: PlanApprovalRequest?) {
        pendingQuestion = question
        pendingPlanApproval = planApproval
        applyPromptNotices()
    }

    /** 通知栏「直接回复」提交后重发一次通知，清空输入框里残留的文本。 */
    fun refresh(sessionId: String) {
        val cached = cache[sessionId] ?: return
        val batch = toolPermissionManager.pendingBatchForSession(sessionId)
        if (batch != null && batch.id == cached.id) {
            postBatch(sessionId, batch, cached.title)
            return
        }
        val request = toolPermissionManager.pendingForSession(sessionId) ?: return
        if (request.id == cached.id) post(sessionId, request, cached.title)
    }

    /** 通知栏「直接回复」的文本：由 receiver 投递，交给会话层消费。 */
    fun emitReply(sessionId: String, text: String) {
        if (sessionId.isBlank() || text.isBlank()) return
        if (!_replies.tryEmit(AgentPermissionReply(sessionId, text))) {
            FileLogger.w(TAG, "授权通知回复投递失败（缓冲已满）: sid=$sessionId")
        }
    }

    /** 按当前状态与前后台情况重算提示类通知：该展示的补发，已不需要的撤掉。 */
    private fun applyPromptNotices() {
        if (inForeground()) {
            cancelPromptNotices()
            return
        }
        ensureChannel()
        val notices = buildPromptNotices()
        val kinds = notices.mapTo(HashSet()) { it.kind }
        val staleIds = synchronized(promptLock) {
            shownPrompts.keys.filter { it !in kinds }.mapNotNull { shownPrompts.remove(it)?.second }
        }
        staleIds.forEach { id -> runCatching { NotificationManagerCompat.from(context).cancel(id) } }
        notices.forEach { notice -> promptScope.launch { postPromptNotice(notice) } }
    }

    private fun buildPromptNotices(): List<PromptNotice> {
        val notices = mutableListOf<PromptNotice>()
        pendingQuestion?.takeIf { it.sessionId.isNotBlank() }?.let { question ->
            val first = question.questions.firstOrNull()?.question.orEmpty()
            notices += PromptNotice(
                kind = KIND_QUESTION,
                sessionId = question.sessionId,
                requestId = question.id,
                titleRes = R.string.agent_prompt_question_title,
                body = if (question.questions.size <= 1) first
                else context.getString(R.string.agent_prompt_question_body, question.questions.size, first),
                bigText = question.questions.joinToString("\n") { "· ${it.question}" }
            )
        }
        pendingPlanApproval?.takeIf { it.sessionId.isNotBlank() }?.let { plan ->
            val reason = plan.reason.trim()
            notices += PromptNotice(
                kind = KIND_PLAN,
                sessionId = plan.sessionId,
                requestId = reason.ifBlank { KIND_PLAN },
                titleRes = R.string.agent_prompt_plan_title,
                body = context.getString(R.string.agent_prompt_plan_body),
                bigText = reason
            )
        }
        return notices
    }

    private suspend fun postPromptNotice(notice: PromptNotice) {
        val id = notificationId(notice.sessionId, notice.kind)
        var previousId: Int? = null
        val alreadyShown = synchronized(promptLock) {
            val current = shownPrompts[notice.kind]
            when {
                current == null -> {
                    shownPrompts[notice.kind] = notice.requestId to id
                    false
                }
                current.first == notice.requestId -> true
                else -> {
                    if (current.second != id) previousId = current.second
                    shownPrompts[notice.kind] = notice.requestId to id
                    false
                }
            }
        }
        // 换了请求（乃至换了会话）：先把上一条撤掉，避免留下过期通知条。
        previousId?.let { old -> runCatching { NotificationManagerCompat.from(context).cancel(old) } }
        // 请求未变化就不重发，避免其它会话进入等待时把已在展示的通知重复弹一次。
        if (alreadyShown) return
        val title = sessionTitle(notice.sessionId)
        // 取标题期间状态可能已清空或被前台撤销：确认仍需要展示再发。
        val stillNeeded = synchronized(promptLock) { shownPrompts[notice.kind]?.first == notice.requestId }
        if (!stillNeeded || inForeground()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(notice.titleRes, title))
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.bigText.ifBlank { notice.body }))
            .setContentIntent(openSessionIntent(notice.sessionId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { FileLogger.w(TAG, "发送待处理通知失败: sid=${notice.sessionId} kind=${notice.kind}") }
    }

    /** 撤掉提示类通知（回到前台 / 状态已清空），同时清缓存，下次进入后台可重新提示。 */
    private fun cancelPromptNotices() {
        val ids = synchronized(promptLock) {
            val ids = shownPrompts.values.map { it.second }
            shownPrompts.clear()
            ids
        }
        ids.forEach { id -> runCatching { NotificationManagerCompat.from(context).cancel(id) } }
    }

    /**
     * 点击提示类通知：回到该会话。复用桌面组件已建立的 OPEN_CHAT 协议
     * （[WidgetActions.ACTION_OPEN_CHAT] + 会话 id extra），MainActivity 收到后切会话并进聊天页。
     */
    private fun openSessionIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_SESSION + (sessionId.hashCode() and 0xffff),
        Intent(context, MainActivity::class.java).apply {
            action = WidgetActions.ACTION_OPEN_CHAT
            putExtra(WidgetActions.EXTRA_SESSION_ID, sessionId)
            // 与桌面组件一致：从通知点进来要回到已有任务栈，再经 onNewIntent 切会话。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

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

    /**
     * 批量授权通知：文案改为「N 项待授权」，动作仍为允许/拒绝/直接回复；不渲染 diff（刻意取舍）。
     * 允许/拒绝携带批次 id 与 isBatch 标记，receiver 据此走 [ToolPermissionManager.resolveBatch]。
     */
    private fun postBatch(sessionId: String, batch: PendingPermissionBatch, sessionTitle: String) {
        val count = batch.items.size
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_perm_notification_batch_title, sessionTitle, count))
            .setContentText(context.getString(R.string.agent_perm_notification_batch_text, count))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                batch.items.joinToString("\n") { "· ${it.toolName} · ${it.summary}" }
            ))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(replyAction(sessionId))
            .addAction(rejectAction(sessionId, batch.id, isBatch = true))
            .addAction(allowAction(sessionId, batch.id, isBatch = true))
            .build()
        val id = notificationId(sessionId, batch.id)
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

    private fun actionIntent(sessionId: String, requestId: String, action: String, isBatch: Boolean): Intent =
        Intent(context, AgentPermissionActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_IS_BATCH, isBatch)

    private fun allowAction(sessionId: String, requestId: String, isBatch: Boolean = false) = NotificationCompat.Action(
        android.R.drawable.ic_menu_save,
        context.getString(R.string.agent_perm_action_allow),
        PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, requestId, ACTION_ALLOW),
            actionIntent(sessionId, requestId, ACTION_ALLOW, isBatch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun rejectAction(sessionId: String, requestId: String, isBatch: Boolean = false) = NotificationCompat.Action(
        android.R.drawable.ic_menu_close_clear_cancel,
        context.getString(R.string.agent_perm_action_reject),
        PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, requestId, ACTION_REJECT),
            actionIntent(sessionId, requestId, ACTION_REJECT, isBatch),
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
        const val EXTRA_IS_BATCH = "com.aharou.extra.PERMISSION_IS_BATCH"
        const val ACTION_ALLOW = "com.aharou.action.PERMISSION_ALLOW"
        const val ACTION_REJECT = "com.aharou.action.PERMISSION_REJECT"
        const val ACTION_REPLY = "com.aharou.action.PERMISSION_REPLY"
        const val KEY_REPLY = "com.aharou.extra.PERMISSION_REPLY_TEXT"

        /** 提示类通知的两种类型，同时用作「同一类型只保留最新一条」的键。 */
        private const val KIND_QUESTION = "question"
        private const val KIND_PLAN = "plan"
        private const val TAG = "AgentPermission"
        private const val NOTIFICATION_BASE = 210000
        private const val REQUEST_OPEN_APP = 210000
        private const val REQUEST_OPEN_SESSION = 220000
    }
}
