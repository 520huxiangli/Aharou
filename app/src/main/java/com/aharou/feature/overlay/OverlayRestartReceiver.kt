package com.aharou.feature.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger

/**
 * 开机与应用被更新后把悬浮窗服务拉回来。
 *
 * [FloatingToolService] 的 onStartCommand 返回 START_STICKY，但那只覆盖「被系统查杀」——
 * 装新包（`pm install -r`）替换进程和设备重启都不会自动拉起服务，用户看到的现象就是
 * 「开关明明还开着，却要先关掉再打开才生效」。
 *
 * 只处理这两个系统广播；收不到就不动，避免被伪造的 action 拉起前台服务。
 */
class OverlayRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit

            else -> return
        }
        if (!FloatingToolService.isEnabled(context)) return
        runCatching {
            // 必须走 startForegroundService：广播里没有前台界面，Android 8+ 起
            // 从后台调用 startService 会抛 IllegalStateException。
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingToolService::class.java)
            )
            FileLogger.i(TAG, "已重启悬浮窗服务（${intent.action}）")
        }.onFailure { FileLogger.w(TAG, "重启悬浮窗服务失败: ${it.message}") }
    }

    private companion object {
        const val TAG = "OverlayRestart"
    }
}
