package com.aharou.feature.voice.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import com.aharou.feature.voice.assistant.presentation.AssistantOverlayActivity
import dagger.hilt.android.AndroidEntryPoint
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 长按电源键唤起助手：应用侧监听本地端口，Shizuku 侧把 `getevent` 的实时事件流推过来。
 *
 * 为什么是 socket：
 * - 文件：Android 11 起 FUSE 按 owner 判定，shell 写的文件应用读不了（`chmod 666` 也无效）；
 * - `am broadcast`：每条按键起一个 JVM，实测延迟 1~2 秒，等卡片出来系统长按页已经弹了。
 * 直连 socket 只受管道本身延迟影响，实测可做到按下后几百毫秒内判定。
 *
 * 只监听不拦截：系统在把电源键交给窗口之前就处理完了。按住超过 [LONG_PRESS_MS] 就抢先弹浮层，
 * 用户看到卡片松手时还不到系统长按时限；没到阈值就松手的短按照常熄屏。
 */
@AndroidEntryPoint
class PowerKeyWatcherService : Service() {

    @Inject
    lateinit var shizukuManager: ShizukuManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        watchJob = scope.launch { runWatcher() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watchJob?.cancel()
        scope.launch { stopPipeline() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runWatcher() {
        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            FileLogger.w(TAG, "Shizuku 未就绪：$state")
            stopSelf()
            return
        }

        val server = runCatching {
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            FileLogger.w(TAG, "监听端口失败：${it.message}")
            stopSelf()
            return
        }

        try {
            while (scope.isActive) {
                if (!startPipeline()) {
                    delay(1_000)
                    continue
                }
                val client = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { server.accept() }
                if (client == null) {
                    FileLogger.w(TAG, "等待事件流超时，重试")
                    continue
                }
                FileLogger.i(TAG, "事件流已连接")
                client.use { socket ->
                    socket.getInputStream().bufferedReader().forEachLine { line ->
                        onLine(line)
                    }
                }
                // 流断了（应用被杀 / getevent 退出）就先收掉残留进程再重连。
                delay(300)
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private var pressJob: Job? = null

    private fun onLine(line: String) {
        when {
            !line.contains("KEY_POWER") -> Unit
            line.contains("DOWN") -> {
                FileLogger.i(TAG, "收到 DOWN $line")
                pressJob?.cancel()
                pressJob = scope.launch {
                    delay(LONG_PRESS_MS)
                    launchOverlay()
                }
            }
            line.contains("UP") -> {
                FileLogger.i(TAG, "收到 UP")
                pressJob?.cancel()
                pressJob = null
            }
        }
    }

    private fun launchOverlay() {
        FileLogger.i(TAG, "开始拉起浮层")
        val intent = Intent(this, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantOverlayActivity.EXTRA_FROM_TILE, true)
        runCatching { startActivity(intent) }
            .onFailure { FileLogger.w(TAG, "电源键唤起浮层失败：${it.message}") }
    }

    /**
     * 拉起 `getevent | nc`。
     *
     * 清理和启动分两次调用：同一条命令里既有 pkill 又有 getevent 命令时，
     * pkill 的正则会命中自己所在的 shell 并把它杀掉（退出码 143）。
     */
    private suspend fun startPipeline(): Boolean {
        runCatching { shizukuManager.runCommand("pkill -f 'geteven[t] -lt $INPUT_DEVICE'", 3_000) }
        val command = "nohup sh -c 'getevent -lt $INPUT_DEVICE | nc 127.0.0.1 $PORT' >/dev/null 2>&1 &"
        val result = runCatching { shizukuManager.runCommand(command, 5_000) }.getOrNull()
        if (result == null || result.exitCode != 0) {
            FileLogger.w(TAG, "拉起事件流失败：${result?.exitCode}")
            return false
        }
        return true
    }

    private suspend fun stopPipeline() {
        runCatching { shizukuManager.runCommand("pkill -f 'geteven[t] -lt $INPUT_DEVICE'", 3_000) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.assistant_power_watcher_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AssistantOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.assistant_power_watcher_title))
            .setContentText(getString(R.string.assistant_power_watcher_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "PowerKeyWatcher"
        private const val CHANNEL_ID = "aharou_power_key"
        private const val NOTIFICATION_ID = 4711
        private const val INPUT_DEVICE = "/dev/input/event1"
        private const val PORT = 39171
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** 按住多久算长按。要小于系统长按时限（约 2 秒），也要大于普通短按（<200ms）。 */
        private const val LONG_PRESS_MS = 300L

        fun start(context: Context) {
            val intent = Intent(context, PowerKeyWatcherService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PowerKeyWatcherService::class.java)) }
        }
    }
}
