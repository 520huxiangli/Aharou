package com.aharou.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aharou.MainActivity
import com.aharou.core.util.FileLogger
import com.aharou.R
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 工作区路径 → 该工作区正在跑的会话数。用于在通知里区分是哪个工作区的任务。 */
    private val workspaceSessions = mutableMapOf<String, Int>()

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 前台保活期间持有的 Wi-Fi 锁：避免切后台/息屏后 Wi-Fi 进入省电使长连接被回收。 */
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wifiLocksInitialized = false

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // 进程被系统回收后 START_STICKY 重建：persistent 是内存态会丢失。
        // 从持久化开关恢复常驻前台，否则服务空转后 stopSelf，保活形同虚设。
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWifiLocks()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 开关开着就不动它：国产 ROM 的「清理 / 内存加速」同样会走这个回调，
        // 一律停掉等于让开关失效——而且这里会把 KeepaliveWorker 一并取消，
        // 进程被杀后系统不会再拉起，用户看到的就是「锁了后台还是掉」。
        if (persistent) {
            FileLogger.i(TAG, "Task removed, persistent keepalive kept")
            return
        }
        // 没开常驻：用户从最近任务划掉 app = 明确不要后台运行，停服务并取消周期兜底。
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                bumpWorkspace(intent.getStringExtra(EXTRA_WORKSPACE), +1)
                ensureForeground()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                bumpWorkspace(intent.getStringExtra(EXTRA_WORKSPACE), -1)
                if (sessionCount == 0 && !persistent) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Android 15(35) 起系统会对有运行时长上限的前台服务类型发超时回调；specialUse 不在时限列表内，
     * 这里仍做兑底——回调后只留几秒，不主动停就会抛 RemoteServiceException。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FileLogger.w(TAG, "Foreground service timed out (type=$fgsType), stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    /**
     * 进入前台并刷新通知；文案随「常驻保活 / 后台会话」组合变化。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.aharou.MainActivity] 恢复）。
     */
    /** 维护「每个工作区在跑几个任务」；路径为空（旧调用方）就只算总数。 */
    private fun bumpWorkspace(path: String?, delta: Int) {
        val key = path?.takeIf { it.isNotBlank() } ?: return
        val next = (workspaceSessions[key] ?: 0) + delta
        if (next <= 0) workspaceSessions.remove(key) else workspaceSessions[key] = next
    }

    /** 通知副文案里的工作区部分：只取末级目录名，多个用「、」连接。 */
    private fun workspaceLabel(): String = workspaceSessions.keys
        .filter { it.isNotBlank() }
        .map { it.trimEnd('/').substringAfterLast('/').ifBlank { it } }
        .distinct()
        .joinToString("、")

    private fun ensureForeground() {
        val label = workspaceLabel()
        val text = when {
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount) +
                    if (label.isBlank()) "" else " · $label"
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount) +
                    if (label.isBlank()) "" else " · $label"
            else -> getString(R.string.notification_keepalive_enabled)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onSuccess { acquireWifiLocks() }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * 获取 Wi-Fi 锁，尽量保持 Wi-Fi 不休眠。延后到首次进入前台时惰性创建：只有真正保活期间
     * 才需要，避免无谓占用。API 29+ 同时持低延迟锁（前台亮屏时生效）与兼容锁（后台/息屏时生效）。
     */
    private fun acquireWifiLocks() {
        runCatching {
            if (!wifiLocksInitialized) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Aharou:Keepalive:low-latency")
                }
                @Suppress("DEPRECATION")
                wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Aharou:Keepalive")
                wifiLocks.forEach { it.setReferenceCounted(false) }
                wifiLocksInitialized = true
            }
            wifiLocks.forEach { lock -> if (!lock.isHeld) lock.acquire() }
        }.onFailure { FileLogger.w(TAG, "acquireWifiLock failed", it) }
    }

    private fun releaseWifiLocks() {
        wifiLocks.forEach { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        const val ACTION_START_SESSION = "com.aharou.agent.action.START_SESSION"

        /** 本次会话属于哪个工作区（绝对路径）；旧调用方不传时只算总数。 */
        const val EXTRA_WORKSPACE = "com.aharou.agent.extra.WORKSPACE"
        const val ACTION_STOP_SESSION = "com.aharou.agent.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aharou.agent.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aharou.agent.action.DISABLE_PERSISTENT"

        /** 开启常驻保活（幂等）。 */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure {
                    // 后台冷启动时系统拒绝 startService（Android 8+ 后台执行限制），属预期场景，
                    // 保活会在 App 回到前台或 WorkManager 兜底时恢复，不必刷全栈吓人日志。
                    if (it.message?.contains("app is in background") == true) {
                        FileLogger.i(TAG, "enablePersistent skipped: app in background, keepalive deferred")
                    } else {
                        FileLogger.e(TAG, "enablePersistent startService failed", it)
                    }
                }
        }

        /** 关闭常驻保活（幂等）。仅在确曾开启过时调用，避免为关闭而凭空拉起 Service。 */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** 登记一个后台会话（不改变用户常驻开关）。由调用方按状态迁移保证不重复计数。 */
        fun startSession(context: Context, workspacePath: String? = null) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
                workspacePath?.let { putExtra(EXTRA_WORKSPACE, it) }
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "startSession startService failed", it) }
        }

        /** 结束一个后台会话。 */
        fun stopSession(context: Context, workspacePath: String? = null) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
                workspacePath?.let { putExtra(EXTRA_WORKSPACE, it) }
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** 判断保活服务当前是否在运行。WorkManager 兜底拉起前先探测，避免反复 startService。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}

/**
 * 在 [wakeLock] 到期前持续续期，直到 [shouldContinue] 返回 false（无运行中任务时收尾）。
 * 单次 acquire 的超时覆盖不了超长任务（子代理串行执行可超过一小时），过期后 CPU 可休眠、
 * 后台收包会停摆；调用方需自行取消返回协程以结束续期。
 */
internal suspend fun renewWakeLockUntilIdle(
    wakeLock: PowerManager.WakeLock,
    timeoutMs: Long,
    marginMs: Long,
    tag: String,
    shouldContinue: () -> Boolean
) {
    while (true) {
        delay(timeoutMs - marginMs)
        if (!shouldContinue()) break
        runCatching { wakeLock.acquire(timeoutMs) }
            .onFailure { FileLogger.e(tag, "renew wakeLock failed", it) }
    }
}
