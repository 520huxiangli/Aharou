package com.aharou.feature.im.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
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
 * 持有钉钉 Stream 长连接协程的前台服务。
 *
 * 与 [QqBotService] 同构：用 specialUse 类型（Android 15 起 dataSync 前台服务
 * 24 小时累计只能跑 6 小时，长连接保活形同虚设），通知栏给一个「停止」按钮。
 * 真正的连接逻辑在 [ImBotBridge]，这里只管进程存活与状态展示。
 */
@AndroidEntryPoint
class DingTalkBotService : Service() {

    @Inject @Named("dingtalk") lateinit var bridge: ImBotBridge

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val foregroundGuard by lazy { ImForegroundGuard(this, NOTIFICATION_ID, CHANNEL_ID, TAG) }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 提升失败必须停：系统那笔「数秒内进前台」的账已经无法清掉，继续留着只会在几秒后抛
        // RemoteServiceException 崩掉整个进程。
        if (!foregroundGuard.promote { buildNotification(getString(R.string.im_dingtalk_service_running), null) }) {
            stopSelf()
            return
        }
        bridge.start(serviceScope)
        serviceScope.launch {
            bridge.state.collect { state -> runCatching { updateNotification(state) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            foregroundGuard.stop(startId)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.im_dingtalk_service_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun updateNotification(state: ImChannelState) {
        val text = when (state) {
            ImChannelState.Connecting -> getString(R.string.im_status_connecting)
            is ImChannelState.Connected -> getString(R.string.im_dingtalk_service_connected, state.identity)
            is ImChannelState.Error -> getString(R.string.im_dingtalk_service_error, state.message)
            ImChannelState.Stopped -> getString(R.string.im_dingtalk_service_stopped)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, state))
    }

    private fun buildNotification(text: String, state: ImChannelState?): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DingTalkBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.im_dingtalk_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.im_service_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "DingTalkBotService"
        private const val NOTIFICATION_ID = 8
        private const val CHANNEL_ID = "dingtalk_bot_service"
        private const val ACTION_STOP = "com.aharou.feature.im.action.STOP_DINGTALK_BOT"

        /** 启动长连接（幂等）。UI 侧开关打开后调用。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DingTalkBotService::class.java)
                )
            }.onFailure { FileLogger.e(TAG, "启动服务失败", it) }
        }

        /** 停止长连接并移除通知。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DingTalkBotService::class.java)) }
                .onFailure { FileLogger.e(TAG, "停止服务失败", it) }
        }
    }
}
