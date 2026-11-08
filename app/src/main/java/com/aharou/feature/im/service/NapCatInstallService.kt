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
import com.aharou.core.util.SecretMasker
import com.aharou.feature.im.domain.napcat.NapCatInstallStage
import com.aharou.feature.im.domain.napcat.NapCatRuntimeManager
import com.aharou.feature.im.domain.napcat.NapCatRuntimeState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NapCatInstallService : Service() {
    @Inject lateinit var runtime: NapCatRuntimeManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val guard by lazy { ImForegroundGuard(this, NOTIFICATION_ID, CHANNEL_ID, TAG) }
    private var observer: Job? = null
    private var cancellation: Job? = null
    private var promoted = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.napcat_install), NotificationManager.IMPORTANCE_LOW)
        )
        // 安装可能先于服务创建结束，仍须先偿还系统的前台提升请求。
        promoted = guard.promote { notification(runtime.state.value) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!promoted) return START_NOT_STICKY
        // 同一实例可能在停止请求后又收到启动请求，必须重新发布前台通知。
        guard.refresh { notification(runtime.state.value) }
        if (runtime.state.value.phase != "installing") {
            guard.stop(lastStartId)
            return START_NOT_STICKY
        }
        if (observer?.isActive != true) {
            observer = scope.launch {
                runtime.state.collect { state ->
                    if (state.phase == "installing") {
                        guard.refresh { notification(state) }
                    } else {
                        guard.stop(lastStartId)
                    }
                }
            }
        }
        if (intent?.action == ACTION_CANCEL && cancellation?.isActive != true) {
            cancellation = scope.launch(Dispatchers.IO) {
                try {
                    runtime.cancelInstallation()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FileLogger.w(TAG, "取消 NapCat 安装失败：${SecretMasker.mask(error.message.orEmpty())}")
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: NapCatRuntimeState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, NapCatInstallService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.napcat_install))
            .setContentText(getString(NapCatInstallStage.label(state.stage)))
            .setContentIntent(open)
            .setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), !NapCatInstallStage.isDownload(state.stage))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.common_cancel), cancel)
            .build()
    }

    companion object {
        private const val TAG = "NapCatInstallService"
        private const val CHANNEL_ID = "qq_napcat_install"
        private const val NOTIFICATION_ID = 19
        private const val ACTION_CANCEL = "com.aharou.feature.im.action.CANCEL_NAPCAT_INSTALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NapCatInstallService::class.java))
        }
    }
}
