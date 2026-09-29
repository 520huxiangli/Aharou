package com.aharou.feature.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aharou.R
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.voice.call.VoiceCallService
import com.aharou.feature.voice.call.VoiceCallSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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
@AndroidEntryPoint
class FloatingToolService : Service() {

    companion object {
        private const val TAG = "FloatingToolService"
        private const val CHANNEL_ID = "aharou_overlay"
        private const val NOTIFICATION_ID = 4101

        private const val PREFS = "aharou_overlay_prefs"
        private const val KEY_ENABLED = "enabled"

        /**
         * 服务是否已在本进程内运行。
         *
         * 用于「开关开着但服务没跑」的自愈判断：装包或进程被杀后服务不会自动重建，
         * 用户看到的就是开关亮着却不生效。
         */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            runCatching {
                // 用 startForegroundService 而非 startService：调用方可能是广播接收器
                // （见 [OverlayRestartReceiver]），后台走 startService 在 Android 8+ 会抛异常。
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingToolService::class.java)
                )
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

    /** 通话状态要从服务里拿（跨进程级的单例）。 */
    @Inject
    internal lateinit var callSession: VoiceCallSession

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
        running = true
        ensureChannel()
        // targetSdk 34 起 startForeground 必须带上类型（与 manifest 的 foregroundServiceType 一致），
        // 否则抛 MissingForegroundServiceTypeException。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        overlay = FloatingToolOverlay(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        collectorJob = scope.launch {
            combine(
                AgentRuntimeStatus.state,
                appForeground,
                VoiceCallService.running,
                callSession.state,
                callSession.displayText,
            ) { status, foreground, callRunning, callState, displayText ->
                OverlaySnapshot(status, foreground, callRunning, callState, displayText)
            }.collect { snap ->
                if (!overlay.hasOverlayPermission()) {
                    overlay.hide()
                    return@collect
                }
                // 通话中常显（它就是通话的唯一界面）；否则只在本轮任务跑着且 App 不在前台时显示。
                // 两种状态共用同一枚胶囊：头像当通话开关，状态行改显它正在说的那句。
                val shouldShow = snap.callRunning ||
                    (snap.status.active && !snap.foreground)
                if (!shouldShow) {
                    overlay.hide()
                    return@collect
                }
                val title = if (snap.callRunning) getString(R.string.voice_call_title)
                else snap.status.toolName
                val statusText = when {
                    !snap.callRunning && snap.status.busy -> snap.status.statusText
                    !snap.callRunning -> getString(R.string.floating_thinking)
                    snap.displayText.isNotBlank() -> snap.displayText
                    else -> getString(callStateLabel(snap.callState))
                }
                overlay.show(
                    toolName = title,
                    statusText = statusText,
                    iconAction = ::toggleCall,
                    callActive = snap.callRunning,
                    expandable = snap.callRunning,
                )
            }
        }
    }

    /** 头像上的动作：通话开着就关，关着就开。 */
    private fun toggleCall() {
        if (VoiceCallService.isRunning()) VoiceCallService.stop(this)
        else VoiceCallService.start(this)
    }

    private fun callStateLabel(state: VoiceCallSession.State): Int = when (state) {
        VoiceCallSession.State.Listening -> R.string.voice_call_state_listening
        VoiceCallSession.State.Thinking -> R.string.voice_call_state_thinking
        VoiceCallSession.State.Speaking -> R.string.voice_call_state_speaking
        VoiceCallSession.State.Preparing, VoiceCallSession.State.Idle ->
            R.string.voice_call_state_preparing
    }

    /** 合并后的显示状态：同时装着 Agent 进度与通话状态。 */
    private data class OverlaySnapshot(
        val status: AgentRuntimeStatus.State,
        val foreground: Boolean,
        val callRunning: Boolean,
        val callState: VoiceCallSession.State,
        val displayText: String,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
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
