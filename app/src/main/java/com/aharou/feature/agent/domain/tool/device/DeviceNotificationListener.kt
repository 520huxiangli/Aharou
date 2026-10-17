package com.aharou.feature.agent.domain.tool.device

import android.app.Notification
import android.content.Context
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
        synchronized(LOCK) {
            buffer.addFirst(
                Entry(
                    packageName = sbn.packageName,
                    title = title,
                    text = text.ifEmpty { sub },
                    postedAt = sbn.postTime,
                    ongoing = sbn.isOngoing,
                )
            )
            while (buffer.size > MAX_KEPT) buffer.removeLast()
        }
        // 投给自动化事件总线：只投递、立即返回，查库与排调度在总线自己的协程里做。
        // 本回调跑在主线程上，拖住它会连带拖慢整机通知，所以这里绝不做重活。
        eventBus.postNotification(sbn.packageName, title, text.ifEmpty { sub })
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
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val ongoing: Boolean,
    )

    companion object {
        private const val MAX_KEPT = 50
        private val LOCK = Any()
        private val buffer = ArrayDeque<Entry>()

        @Volatile
        private var connected = false

        /** 用户有没有在系统里勾选本应用的通知使用权。 */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** 最近通知，新的在前；[onlyPackage] 非空时只看该应用的。 */
        fun snapshot(limit: Int, onlyPackage: String? = null): List<Entry> = synchronized(LOCK) {
            buffer.asSequence()
                .filter { onlyPackage == null || it.packageName.equals(onlyPackage, ignoreCase = true) }
                .take(limit.coerceIn(1, MAX_KEPT))
                .toList()
        }

        /** 服务是否已连上（用户开了权限但服务被杀时可能为 false）。 */
        fun isConnected(): Boolean = connected
    }
}