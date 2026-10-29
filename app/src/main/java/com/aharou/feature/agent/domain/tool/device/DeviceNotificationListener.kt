package com.aharou.feature.agent.domain.tool.device

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.aharou.feature.agent.domain.schedule.trigger.AutomationEventEntryPoint
import com.aharou.feature.agent.domain.schedule.trigger.EventTriggerBus
import dagger.hilt.android.EntryPointAccessors

/**
 * 通知读取：把用户的通知缓存最近若干条供 Agent 查询。
 *
 * 通知是**语音/对话场景里最需要的一块上下文**（「刚才谁给我发消息了」），而它必须由
 * 用户在系统的「通知使用权」里手动开启——应用无法自行申请，也不该假装能申请。
 * 未开启时工具会返回明确的开启指引。
 *
 * 缓存只放内存、只保留最近 [MAX_KEPT] 条、进程结束即消失，不落盘。
 */
class DeviceNotificationListener : NotificationListenerService() {

    /**
     * 本服务由系统构造、不在 Hilt 的注入图里，依赖得经 EntryPoint 取
     * （同 [com.aharou.feature.agent.domain.schedule.ScheduledTaskWorker] 的做法）。
     */
    private val eventBus: EventTriggerBus by lazy {
        EntryPointAccessors.fromApplication(applicationContext, AutomationEventEntryPoint::class.java)
            .eventTriggerBus()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val replyAction = findReplyAction(notification)
        synchronized(LOCK) {
            buffer.addFirst(
                Entry(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = title,
                    text = text.ifEmpty { sub },
                    postedAt = sbn.postTime,
                    ongoing = sbn.isOngoing,
                )
            )
            liveKeys.add(sbn.key)
            // 同一 key 的通知会被应用反复更新（新消息合并进同一条），回复动作只认最新一次的；
            // 新一次没有回复动作时也要清掉旧的，否则会往一个已失效的 PendingIntent 里发。
            if (replyAction != null) {
                replyTargets[sbn.key] = ReplyTarget(replyAction, sbn.packageName, title)
            } else {
                replyTargets.remove(sbn.key)
            }
            while (buffer.size > MAX_KEPT) {
                val evicted = buffer.removeLast()
                if (buffer.none { it.key == evicted.key }) {
                    replyTargets.remove(evicted.key)
                    liveKeys.remove(evicted.key)
                }
            }
        }
        // 投给自动化事件总线：只投递、立即返回，查库与排调度在总线自己的协程里做。
        // 本回调跑在主线程上，拖住它会连带拖慢整机通知，所以这里绝不做重活。
        eventBus.postNotification(sbn.packageName, title, text.ifEmpty { sub })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        // 只删回复动作、不删 buffer 里的文字：用户划掉通知后仍可能问「刚才谁发了什么」。
        synchronized(LOCK) {
            replyTargets.remove(key)
            liveKeys.remove(key)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        synchronized(LOCK) { connected = true }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        synchronized(LOCK) { connected = false }
    }

    data class Entry(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val ongoing: Boolean,
        val canReply: Boolean = false,
    )

    private class ReplyTarget(
        val action: Notification.Action,
        val packageName: String,
        val title: String,
    )

    sealed interface ReplyResult {
        data class Sent(val packageName: String, val title: String) : ReplyResult
        data object Gone : ReplyResult
        data object NoReply : ReplyResult
        data class Failed(val reason: String) : ReplyResult
    }

    companion object {
        private const val MAX_KEPT = 50
        private val LOCK = Any()
        private val buffer = ArrayDeque<Entry>()
        private val replyTargets = HashMap<String, ReplyTarget>()

        // 缓存里还有文字、但没有回复动作时，靠它区分「通知还在只是不支持回复」与「已被划掉」。
        private val liveKeys = HashSet<String>()

        @Volatile
        private var connected = false

        /** 用户有没有在系统里勾选本应用的通知使用权。 */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** 最近通知，新的在前；[onlyPackage] 非空时只看该应用的。 */
        fun snapshot(limit: Int, onlyPackage: String? = null): List<Entry> = synchronized(LOCK) {
            // canReply 按读取时刻算：通知被划掉或更新成不可回复后，旧条目也要如实显示不能回。
            buffer.asSequence()
                .filter { onlyPackage == null || it.packageName.equals(onlyPackage, ignoreCase = true) }
                .take(limit.coerceIn(1, MAX_KEPT))
                .map { it.copy(canReply = replyTargets.containsKey(it.key)) }
                .toList()
        }

        /** 往 [key] 对应通知的回复框里填 [text] 并发出，等价于用户在通知栏里直接回复。 */
        fun reply(context: Context, key: String, text: String): ReplyResult {
            val target = synchronized(LOCK) {
                replyTargets[key] ?: return if (key in liveKeys) ReplyResult.NoReply else ReplyResult.Gone
            }
            val inputs = target.action.remoteInputs.orEmpty()
            val freeForm = inputs.filter { it.allowFreeFormInput }
            if (freeForm.isEmpty()) return ReplyResult.NoReply
            val intent = Intent()
            val results = Bundle().apply { freeForm.forEach { putCharSequence(it.resultKey, text) } }
            // 只交自由输入的那几个 RemoteInput：把选项型的也塞进去会让部分应用把空值当成选了某个快捷回复。
            RemoteInput.addResultsToIntent(freeForm.toTypedArray(), intent, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            return try {
                target.action.actionIntent.send(context, 0, intent)
                ReplyResult.Sent(target.packageName, target.title)
            } catch (e: PendingIntent.CanceledException) {
                synchronized(LOCK) { replyTargets.remove(key) }
                ReplyResult.Failed("回复入口已被对方应用撤销（通知可能已过期）")
            }
        }

        /** 带允许自由输入的 RemoteInput 的动作才算「回复」；微信这类把它放在 WearableExtender 里。 */
        private fun findReplyAction(notification: Notification): Notification.Action? {
            fun Notification.Action.isReply() =
                actionIntent != null && remoteInputs?.any { it.allowFreeFormInput } == true
            notification.actions?.firstOrNull { it.isReply() }?.let { return it }
            return Notification.WearableExtender(notification).actions.firstOrNull { it.isReply() }
        }

        /** 服务是否已连上（用户开了权限但服务被杀时可能为 false）。 */
        fun isConnected(): Boolean = connected
    }
}