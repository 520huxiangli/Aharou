package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 通知的读（靠 [DeviceNotificationListener] 的缓存）与发。 */
@Singleton
class NotificationHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "NotificationHandler"
        const val CHANNEL_ID = "aharou_agent_notes"
        const val CHANNEL_NAME = "AI 提醒"
        const val DEFAULT_LIMIT = 20

        const val LISTENER_HINT =
            "还没开通知使用权：到「设置 → 通知 → 通知使用权（部分机型叫通知读取权限）」里勾选 Aharou，" +
                "开启后才能读到通知内容。应用无法自己开这个权限。"

        const val NOTIFY_PERMISSION_HINT =
            "没有通知权限：到系统设置给 Aharou 开「通知」后再试。"
    }

    private val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun read(limitRaw: Int?, packageFilter: String?): ToolResult {
        if (!DeviceNotificationListener.isEnabled(context)) {
            return ToolResult.Error(LISTENER_HINT, "NOTIFICATION_ACCESS_MISSING")
        }
        val limit = limitRaw ?: DEFAULT_LIMIT
        val entries = DeviceNotificationListener.snapshot(limit, packageFilter)
        return ToolResult.Success(buildJsonObject {
            put("count", entries.size)
            put("connected", DeviceNotificationListener.isConnected())
            if (entries.isEmpty()) {
                put(
                    "note",
                    "缓存里还没有通知。注意：只有开启通知使用权之后收到的通知才会被记下，之前的读不到。",
                )
            }
            put("notifications", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("app", entry.packageName)
                        put("title", entry.title)
                        put("text", entry.text)
                        put("time", formatter.format(Date(entry.postedAt)))
                        put("ongoing", entry.ongoing)
                    })
                }
            })
        })
    }

    fun send(title: String, text: String): ToolResult {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return ToolResult.Error(NOTIFY_PERMISSION_HINT, "NOTIFICATIONS_DISABLED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Error(NOTIFY_PERMISSION_HINT, "NOTIFICATIONS_DISABLED")
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.aharou.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        }.fold(
            onSuccess = { ToolResult.Success(buildJsonObject { put("message", "通知已发出：$title") }) },
            onFailure = { e ->
                FileLogger.e(TAG, "发通知失败", e)
                ToolResult.Error("发通知失败：${e.message}", "NOTIFY_FAILED")
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "AI 主动发出的提醒"
            }
        )
    }
}