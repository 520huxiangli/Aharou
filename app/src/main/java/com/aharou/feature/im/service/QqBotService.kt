package com.aharou.feature.im.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.ImBotBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 持有QQ iLink 长轮询协程的前台服务。
 *
 * 与 [TelegramBotService] 同构：用 specialUse 类型（Android 15 起 dataSync 前台服务
 * 24 小时累计只能跑 6 小时，长轮询保活形同虚设），通知栏给一个「停止」按钮。
 * 真正的轮询逻辑在 [ImBotBridge]，这里只管进程存活与状态展示。
 */
@AndroidEntryPoint
class QqBotService : Service() {

    @Inject @Named("qq") lateinit var bridge: ImBotBridge

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundWithNotification()
        bridge.start(serviceScope)
        serviceScope.launch {
            bridge.state.collect { state -> runCatching { updateNotification(state) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 8+ 要求通知频道先建后用，否则 startForeground 抛 Bad notification 直接崩。 */
    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.im_qq_service_title),
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundWithNotification() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.im_qq_service_running), null),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { FileLogger.e(TAG, "启动前台通知失败", it) }
    }

    private fun updateNotification(state: com.aharou.feature.im.domain.ImChannelState) {
        val text = when (state) {
            com.aharou.feature.im.domain.ImChannelState.Connecting ->
                getString(R.string.im_status_connecting)
            is com.aharou.feature.im.domain.ImChannelState.Connected ->
                getString(R.string.im_qq_service_connected, state.identity)
            is com.aharou.feature.im.domain.ImChannelState.Error ->
                getString(R.string.im_qq_service_error, state.message)
            com.aharou.feature.im.domain.ImChannelState.Stopped ->
                getString(R.string.im_qq_service_stopped)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, state))
    }

    private fun buildNotification(text: String, state: com.aharou.feature.im.domain.ImChannelState?): android.app.Notification {
        val tapIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = android.app.PendingIntent.getService(
            this,
            1,
            Intent(this, QqBotService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.im_qq_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.im_service_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "QqBotService"
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL_ID = "qq_bot_service"
        private const val ACTION_STOP = "com.aharou.feature.im.action.STOP_QQ_BOT"

        /** 启动轮询（幂等）。UI 侧开关打开后调用。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, QqBotService::class.java)
                )
            }.onFailure { FileLogger.e(TAG, "启动服务失败", it) }
        }

        /** 停止轮询并移除通知。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, QqBotService::class.java)) }
                .onFailure { FileLogger.e(TAG, "停止服务失败", it) }
        }
    }
}
