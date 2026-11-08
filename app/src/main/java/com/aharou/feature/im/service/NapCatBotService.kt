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
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.napcat.NapCatRuntimeManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class NapCatBotService : Service() {
    @Inject @Named("qq_napcat") lateinit var bridge: ImBotBridge
    @Inject lateinit var runtime: NapCatRuntimeManager
    @Inject lateinit var settings: NapCatSettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val guard by lazy { ImForegroundGuard(this, NOTIFICATION_ID, CHANNEL_ID, TAG) }
    private var runtimeJob: Job? = null
    private var stopping = false
    private var promoted = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.napcat_service_title), NotificationManager.IMPORTANCE_LOW
        ))
        promoted = guard.promote { notification(getString(R.string.im_status_connecting)) }
        if (!promoted) return
        owner = this
        scope.launch {
            runtime.state.collectLatest { state ->
                val text = if (state.error.isNotBlank()) SecretMasker.mask(state.error)
                else when (state.phase) {
                    "running" -> getString(R.string.napcat_service_running)
                    "starting" -> getString(R.string.im_status_connecting)
                    else -> getString(R.string.napcat_service_stopped)
                }
                guard.refresh { notification(text) }
            }
        }
        scope.launch {
            settings.configFlow.map { it.enabled }.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled && !stopping && lastStartId > 0) stopRuntime(logout = false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted) return START_NOT_STICKY
        lastStartId = startId
        when (intent?.action) {
            ACTION_LOGOUT -> stopRuntime(logout = true)
            ACTION_STOP -> stopRuntime(logout = false)
            else -> launchRuntime()
        }
        return if (stopping) START_NOT_STICKY else START_STICKY
    }

    private fun launchRuntime() {
        if (stopping || runtimeJob?.isActive == true) return
        runtimeJob = scope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                if (owner !== this@NapCatBotService) return@withLock
                if (!settings.current().enabled) {
                    withContext(Dispatchers.Main.immediate) { guard.stop(lastStartId) }
                    return@withLock
                }
                try {
                    val phase = runtime.state.value.phase
                    if (phase != "running" && phase != "starting") settings.setAccountId("")
                    withContext(Dispatchers.Main.immediate) { bridge.start(scope) }
                    if (phase == "error") runtime.stop()
                    runtime.start()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FileLogger.w(TAG, "启动个人 QQ 失败：${SecretMasker.mask(error.message.orEmpty())}")
                    withContext(Dispatchers.Main.immediate) {
                        guard.refresh { notification(getString(R.string.napcat_service_start_failed)) }
                    }
                }
            }
        }
    }

    private fun stopRuntime(logout: Boolean) {
        stopping = true
        val previous = runtimeJob
        previous?.cancel()
        runtimeJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            lifecycleMutex.withLock {
                if (owner !== this@NapCatBotService) return@withLock
                withContext(Dispatchers.Main.immediate) { bridge.stop() }
                try {
                    if (logout) {
                        runtime.logout()
                        check(runtime.login.value.error.isBlank()) { runtime.login.value.error }
                    }
                    settings.setEnabled(false)
                    runtime.stop()
                    withContext(Dispatchers.Main.immediate) { guard.stop(lastStartId) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FileLogger.w(TAG, "停止或退出个人 QQ 失败：${SecretMasker.mask(error.message.orEmpty())}")
                    val enabled = settings.current().enabled
                    withContext(Dispatchers.Main.immediate) {
                        stopping = false
                        if (enabled) bridge.start(scope)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        val previous = runtimeJob
        scope.cancel()
        if (owner === this) owner = null
        // 旧实例的清理与新实例启动共用锁，不能停止新实例接管的运行时。
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            previous?.join()
            lifecycleMutex.withLock {
                if (owner != null) return@withLock
                withContext(Dispatchers.Main.immediate) { bridge.stop() }
                try {
                    runtime.stop()
                } catch (error: Exception) {
                    FileLogger.w(TAG, "清理个人 QQ 失败：${SecretMasker.mask(error.message.orEmpty())}")
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NapCatBotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.napcat_service_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.im_service_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "NapCatBotService"
        private const val CHANNEL_ID = "qq_napcat_service"
        private const val NOTIFICATION_ID = 18
        private const val ACTION_STOP = "com.aharou.feature.im.action.STOP_NAPCAT"
        private const val ACTION_LOGOUT = "com.aharou.feature.im.action.LOGOUT_NAPCAT"
        private val lifecycleMutex = Mutex()
        @Volatile private var owner: NapCatBotService? = null

        fun isRunning(): Boolean = owner != null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NapCatBotService::class.java))
        }

        fun logout(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NapCatBotService::class.java).setAction(ACTION_LOGOUT))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NapCatBotService::class.java))
        }
    }
}
