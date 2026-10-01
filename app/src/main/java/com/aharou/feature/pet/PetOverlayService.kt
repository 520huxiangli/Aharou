package com.aharou.feature.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aharou.R

/**
 * 桌宠悬浮窗服务。
 *
 * 持有 [PetOverlay] 并维持前台通知。与 [com.aharou.feature.overlay.FloatingToolService]
 * 刻意分开：那个是「Agent 干活时的状态胶囊」，由 Agent 运行状态与 App 前后台门控显隐；
 * 桌宠是用户开着就该一直在的陪伴，两者显隐规则完全不同，混在一个服务里会互相干扰。
 *
 * 开关状态存 `aharou_pet_prefs`，设置页与 [com.aharou.feature.overlay.OverlayRestartReceiver]
 * 都以它为准。
 */
class PetOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "aharou_pet"
        private const val NOTIFICATION_ID = 4102
        private const val PREFS = "aharou_pet_prefs"
        private const val KEY_ENABLED = "enabled"

        private const val ACTION_SET_SIZE = "com.aharou.feature.pet.action.SET_SIZE"
        private const val EXTRA_SIZE = "size"

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
                // 用 startForegroundService：调用方可能是广播接收器（见 OverlayRestartReceiver），
                // 后台走 startService 在 Android 8+ 会抛异常。
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PetOverlayService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PetOverlayService::class.java))
            }
        }

        /** 改尺寸：先落盘（服务没跑时下次生效），服务在跑就顺带通知它换图。 */
        fun applySize(context: Context, size: PetOverlay.Size) {
            PetOverlay.writeSize(context, size)
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, PetOverlayService::class.java)
                        .setAction(ACTION_SET_SIZE)
                        .putExtra(EXTRA_SIZE, size.key)
                )
            }
        }
    }

    private lateinit var overlay: PetOverlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel()
        // targetSdk 34 起 startForeground 必须带类型（与 manifest 一致），否则抛
        // MissingForegroundServiceTypeException。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        overlay = PetOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_SIZE) {
            val key = intent.getStringExtra(EXTRA_SIZE)
            overlay.setSize(PetOverlay.Size.from(key))
        } else if (isEnabled(this)) {
            overlay.show()
        } else {
            // 开关被关掉但服务还在（例如被系统重建）——收干净。
            stopSelf()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { overlay.onConfigurationChanged(newConfig) }
    }

    override fun onDestroy() {
        running = false
        runCatching { overlay.release() }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pet_notification_channel),
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
            .setContentTitle(getString(R.string.pet_title))
            .setContentText(getString(R.string.pet_notification_text))
            .setOngoing(true)
            .build()
    }
}
