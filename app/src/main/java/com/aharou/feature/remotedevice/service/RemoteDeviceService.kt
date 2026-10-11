package com.aharou.feature.remotedevice.service

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
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.server.RemoteMcpServer
import com.aharou.feature.remotedevice.server.RemoteServerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 持有「跨设备协作」服务端的前台服务。
 *
 * 用 specialUse 类型（同 [com.aharou.feature.terminal.domain.TerminalKeepaliveService]）：
 * Android 15(35) 起 dataSync 前台服务 24 小时累计只能跑 6 小时，超时会被系统强杀；
 * specialUse 面向「用户可见的自定义用途」，正对本场景（设置里手动开启、通知栏可见、可一键停止）。
 *
 * 服务活着期间进程不会被回收，这是远端审批能落到通知栏的前提：
 * [com.aharou.feature.agent.presentation.notification.AgentPermissionNotifier] 是进程内单例，
 * 进程没了通知与挂起的审批请求都会一起消失。
 */
@AndroidEntryPoint
class RemoteDeviceService : Service() {

    @Inject lateinit var server: RemoteMcpServer
    @Inject lateinit var settings: RemoteDeviceSettings

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "服务创建")
        ensureChannel()
        ensureForeground()
        serviceScope.launch {
            // START_STICKY 重建时配置可能已被用户关掉，这时不能再把服务拉起来——
            // 「开关是关的」在用户心里就等于「局域网里没人能连我」。
            if (!settings.current().enabled) {
                FileLogger.i(TAG, "配置已关闭，服务自停")
                ServiceCompat.stopForeground(this@RemoteDeviceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            server.start()
            server.state.collect { ensureForeground() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FileLogger.i(TAG, "收到停止指令")
                server.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> {
                ensureForeground()
                serviceScope.launch {
                    if (settings.current().enabled) server.start() else stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15 起前台服务超时会抛 RemoteServiceException，收到即收尾。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FileLogger.w(TAG, "前台服务超时（type=$fgsType），停止")
        server.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.remote_device_service_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** 后台启动可能抛 ForegroundServiceStartNotAllowedException，捕获仅记录。 */
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

    /**
     * 通知正文用 MCP 端点 URL（技术信息，无需翻译；用户一眼能看出该念给对端的是哪个地址）。
     * 标题、正文模板、停止按钮与渠道名见报告里列出的 remote_device_service_* 字符串键。
     */
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
            Intent(this, RemoteDeviceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.remote_device_service_title))
            .setContentText(getString(R.string.remote_device_service_text, stateText()))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.remote_device_service_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun stateText(): String = when (val state = server.state.value) {
        is RemoteServerState.Running -> state.urls.firstOrNull() ?: "0.0.0.0:${state.port}"
        is RemoteServerState.Failed -> state.message
        RemoteServerState.Stopped -> "0.0.0.0"
    }

    companion object {
        private const val TAG = "RemoteDeviceService"
        private const val NOTIFICATION_ID = 4
        private const val CHANNEL_ID = "remote_device_service"
        private const val ACTION_STOP = "com.aharou.feature.remotedevice.action.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RemoteDeviceService::class.java))
            }.onFailure { FileLogger.e(TAG, "启动服务失败", it) }
        }

        /** 停止服务并移除通知；服务没在跑时不会凭空拉起。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, RemoteDeviceService::class.java))
            }.onFailure { FileLogger.e(TAG, "停止服务失败", it) }
        }
    }
}
