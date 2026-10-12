package com.aharou.feature.remotedevice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「远端设备传话」的横幅通知。
 *
 * 复用 agent 完成通知的渠道（同为屏幕上方横幅级别），但用独立的 notification id：
 * 完成通知会被下一条覆盖，传话不该跟着消失。
 */
@Singleton
class RemoteMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun notify(deviceName: String, text: String) {
        val title = context.getString(R.string.remote_device_message_title, deviceName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送远端传话通知失败", it) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private companion object {
        const val TAG = "RemoteMessage"

        /** 与 AIEditorApp 里创建的 agent 完成通知渠道保持一致，同为高优先级横幅。 */
        const val CHANNEL_ID = "agent_complete"

        const val NOTIFICATION_ID = 101
        private const val REQUEST_OPEN_APP = 100001
    }
}
