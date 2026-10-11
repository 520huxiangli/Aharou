package com.aharou.feature.im.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.TelegramBotBridge
import com.aharou.feature.im.domain.model.TelegramState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 持有 Telegram 长轮询协程的前台服务。
 *
 * 用 specialUse 类型（同 [com.aharou.feature.terminal.domain.TerminalKeepaliveService]）：Android 15(35)
 * 起 dataSync 前台服务 24 小时累计只能跑 6 小时，超时会被系统强杀，长轮询保活形同虚设。
 * 通知栏给一个「停止」按钮，一键停掉轮询。
 */
@AndroidEntryPoint
class TelegramBotService : Service() {

    @Inject lateinit var bridge: TelegramBotBridge

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "服务创建")
        ensureChannel()
        ensureForeground()
        bridge.start(serviceScope)
        serviceScope.launch {
            bridge.state.collect { ensureForeground() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FileLogger.i(TAG, "收到停止指令")
                bridge.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> {
                ensureForeground()
                bridge.start(serviceScope)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15 起前台服务超时会抛 RemoteServiceException，收到即收尾。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FileLogger.w(TAG, "前台服务超时（type=$fgsType），停止")
        bridge.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.im_telegram_service_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** 进入前台并刷新通知文案。后台启动可能抛 ForegroundServiceStartNotAllowedException，捕获仅记录。 */
    private fun ensureForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { FileLogger.e(TAG, "startForeground 失败", it) }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TelegramBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.im_telegram_service_title))
            .setContentText(stateText())
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.im_telegram_service_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun stateText(): String = when (val state = bridge.state.value) {
        is TelegramState.Error -> state.message
        is TelegramState.Connected -> getString(R.string.im_telegram_service_running)
        else -> getString(R.string.im_telegram_service_connecting)
    }

    companion object {
        private const val TAG = "TelegramBotService"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "telegram_bot_service"
        private const val ACTION_STOP = "com.aharou.feature.im.action.STOP_TELEGRAM_BOT"

        /** 启动轮询（幂等）。UI 侧开关打开后调用。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TelegramBotService::class.java)
                )
            }.onFailure { FileLogger.e(TAG, "启动服务失败", it) }
        }

        /** 停止轮询并移除通知。走 stopService，服务没在跑时不会把它凭空拉起来。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TelegramBotService::class.java)) }
                .onFailure { FileLogger.e(TAG, "停止服务失败", it) }
        }

        /** 判断服务当前是否在运行（设置界面切开关前探测，避免重复启动）。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val name = TelegramBotService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == name }
        }
    }
}
