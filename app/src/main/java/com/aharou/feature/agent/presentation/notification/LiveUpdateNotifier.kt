package com.aharou.feature.agent.presentation.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一条灵动岛 / 流体云通知的内容。[ongoing] 为 true 时才是有资格被系统提升的进行中状态。 */
internal data class LiveUpdateContent(
    val title: String,
    val text: String,
    val ongoing: Boolean,
)

/**
 * Android 16 Live Update（promoted ongoing 通知）的构造与投递。
 *
 * 系统只负责把「够格的常驻通知」画成灵动岛胶囊，App 这边要凑齐五项资格：样式为
 * Standard/BigText/Call/Progress/Metric 之一（这里用 [NotificationCompat.BigTextStyle]）、
 * manifest 声明 POST_PROMOTED_NOTIFICATIONS、显式请求提升、setOngoing(true)、标题非空。
 *
 * 显式请求提升只能靠 extra：compileSdk 36 的框架与 androidx.core 1.16 都还没有
 * setRequestPromotedOngoing（那是 36.1 才补的框架 API），故按隐藏常量名直接塞进 extras。
 *
 * 系统不支持（SDK<36 或用户/厂商未开启）时退化成普通常驻通知，照常显示在通知栏。
 */
@Singleton
internal class LiveUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 上一次真正投出去的内容，内容不变就不再 notify——系统只有检测到变化才重渲染胶囊。 */
    private var lastKey: String? = null

    /** 构造并投递一条实时进度通知；[LiveUpdateContent.ongoing] 决定是否仍算进行中状态。 */
    fun update(content: LiveUpdateContent) {
        val key = "${content.ongoing}\u0000${content.title}\u0000${content.text}"
        if (key == lastKey) return
        if (!notificationsEnabled()) return
        lastKey = key
        ensureChannel()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, build(content)) }
            .onFailure { FileLogger.w(TAG, "发送实时进度通知失败", it) }
    }

    /** 收起实时进度通知（挂断、任务收尾且无后续状态时调用）。 */
    fun cancel() {
        if (lastKey == null) return
        lastKey = null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun build(content: LiveUpdateContent): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.text)
            // 必须用内置样式：自定义 RemoteViews 会直接失去被提升的资格。
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(openAppIntent())
            .setOngoing(content.ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(!content.ongoing)
        if (content.ongoing && promotionSupported) {
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        return builder.build()
    }

    /** 当前系统能否把常驻通知提升为灵动岛；不能就让调用方当作普通通知显示。 */
    private val promotionSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && canPostPromoted()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun canPostPromoted(): Boolean = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .canPostPromotedNotifications()
    }.getOrDefault(false)

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live_update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.live_update_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val TAG = "LiveUpdateNotifier"

        /** 实时进度通知渠道（IMPORTANCE_DEFAULT、无角标）。 */
        const val CHANNEL_ID = "aharou_live_update"

        /** 与桌宠(4102)/通话(4103)/完成通知(100) 全部错开。 */
        const val NOTIFICATION_ID = 4200

        const val REQUEST_OPEN_APP = 200000

        /** 框架隐藏常量：请求系统把这条常驻通知提升为 ongoing 状态。 */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
