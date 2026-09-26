package com.aharou.feature.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aharou.R
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务（自 上游项目 的 AgentForegroundService overlay 部分移植·裁剪）。
 *
 * 持有 [FloatingToolOverlay]；按 AND 门控显隐：**App 不在前台 && Agent 有工具在跑 && 有权限**。
 * 由设置页「悬浮窗」开关启停；进程内全局运行状态来自 [AgentRuntimeStatus]。
 */
class FloatingToolService : Service() {

    companion object {
        private const val TAG = "FloatingToolService"
        private const val CHANNEL_ID = "aharou_overlay"
        private const val NOTIFICATION_ID = 4101

        private const val PREFS = "aharou_overlay_prefs"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, FloatingToolService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingToolService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectorJob: Job? = null
    private lateinit var overlay: FloatingToolOverlay
    private val appForeground = MutableStateFlow(true)

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground.value = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        overlay = FloatingToolOverlay(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        collectorJob = scope.launch {
            combine(AgentRuntimeStatus.state, appForeground) { status, foreground ->
                Triple(status, foreground, overlay.hasOverlayPermission())
            }.collect { (status, foreground, hasPermission) ->
                // 整轮门控：任务在跑 && App 不在前台 && 有权限 —— 任务期间常显（只更新文字），
                // 不再按「单个工具的执行窗口」开关，避免一连串快工具导致胶囊闪烁。
                val shouldShow = status.active && !foreground && hasPermission
                if (shouldShow) {
                    val statusText = if (status.busy) status.statusText
                    else getString(R.string.floating_thinking)
                    overlay.show(status.toolName, statusText)
                } else {
                    overlay.hide()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        collectorJob?.cancel()
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver) }
        runCatching { overlay.hide() }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.floating_title),
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_title))
            .setContentText(getString(R.string.floating_enable))
            .setOngoing(true)
            .build()
    }
}
