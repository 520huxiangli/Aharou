package com.aharou.feature.plugin.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aharou.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** App 冷启动时拉起插件运行时：注册 AI 工具，并派发 `app.started`。 */
internal fun startPluginRuntime(
    scope: CoroutineScope,
    toolManager: PluginToolManager,
    hookDispatcher: PluginHookDispatcher,
    packageManager: PackageManager,
    packageName: String
) {
    toolManager.start(scope)
    scope.launch {
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        hookDispatcher.dispatchAppStarted(appVersion)
    }
}

/** 插件通知渠道：由插件进程通过 host.notify 发起，默认优先级，可单独关闭。 */
internal fun createPluginNotificationChannel(context: Context, manager: NotificationManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        "plugin_events",
        context.getString(R.string.notification_channel_plugin_events),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = context.getString(R.string.notification_channel_plugin_events_desc)
        setShowBadge(true)
    }
    manager.createNotificationChannel(channel)
}
