package com.aharou.feature.voice.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aharou.MainActivity
import com.aharou.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音通话的前台服务：持有悬浮窗与通话会话，进程活着通话才在跑。
 *
 * 用 microphone 类型的前台服务，因为麦克风只在有可见界面时才能启动（Android 14 起），
 * 所以通话必须由用户手点发起——这也正是设计要的。
 *
 * 不用 START_STICKY：进程被系统回收后自动拉起一个「通话」没有意义，麦克风也不会自己回来。
 */
@AndroidEntryPoint
internal class VoiceCallService : Service() {

    companion object {
        private const val TAG = "VoiceCallService"
        private const val CHANNEL_ID = "aharou_voice_call"
        private const val NOTIFICATION_ID = 4102

        private val _running = MutableStateFlow(false)

        /** 通话是否在跑。输入栏的麦克风按钮订阅它来显示当前状态。 */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun isRunning(): Boolean = _running.value

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VoiceCallService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, VoiceCallService::class.java))
            }
        }
    }

    @Inject
    lateinit var session: VoiceCallSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _running.value = true
        ensureChannel()
        // targetSdk 34 起 startForeground 必须带类型，且要与 manifest 的 foregroundServiceType 一致
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // 界面由 [FloatingToolService] 那枚常驻胶囊承担（头像 = 通话开关），这里只管通话本身
        session.start("")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        _running.value = false
        session.stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_call_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_call_notification_title))
            .setContentText(getString(R.string.voice_call_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
