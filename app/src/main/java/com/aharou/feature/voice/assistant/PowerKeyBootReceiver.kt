package com.aharou.feature.voice.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.AssistantOverlaySettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机与升级后把电源键监听拉回来。
 *
 * 监听进程是 Shizuku 底下的 `getevent`，重启后就没了；服务本身虽然是 START_STICKY，
 * 但开机场景没有现成进程可恢复，只能在这里按开关重新拉起。
 */
class PowerKeyBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = EntryPointAccessors
                    .fromApplication(appContext, PowerKeyBootEntryPoint::class.java)
                    .overlaySettings()
                if (settings.getPowerKey()) PowerKeyWatcherService.start(appContext)
            } catch (error: Throwable) {
                FileLogger.w(TAG, "恢复电源键监听失败：${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PowerKeyBoot"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PowerKeyBootEntryPoint {
    fun overlaySettings(): AssistantOverlaySettingsRepository
}
