package com.aharou.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Aharou 桌面小组件。
 *
 * 刷新策略（无常驻服务）：
 * - 系统在「组件被添加 / 设备重启 / 应用更新 / 到达 updatePeriodMillis（30 分钟）」时调用 [onUpdate]；
 * - [onUpdate] 里临时起一个 IO 协程读数据并刷新视图，用 goAsync 把广播的存活期延到协程结束。
 *
 * 降级：读取最近会话失败时只渲染顶部入口（空会话列表）；配色解析失败时回退默认预设，
 * 因此刷新不会因数据层异常而整片空白。
 *
 * 依赖（Hilt）通过 [WidgetEntryPoint] 取（见 [WidgetContent]），本类不持有任何常驻状态。
 */
class AharouAppWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                val snapshot = WidgetSnapshot(
                    sessions = runCatching { WidgetContent.loadSessions(appContext) }
                        .onFailure { FileLogger.w(TAG, "读取最近会话失败，降级为仅显示入口", it) }
                        .getOrDefault(emptyList()),
                    palette = WidgetContent.palette(appContext)
                )
                appWidgetIds.forEach { id ->
                    runCatching {
                        appWidgetManager.updateAppWidget(
                            id,
                            WidgetRenderer.build(appContext, id, snapshot)
                        )
                    }.onFailure { FileLogger.w(TAG, "更新小组件失败: id=$id", it) }
                }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "刷新小组件失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AharouAppWidget"

        /** 组件刷新用的短生命周期作用域，进程存活期间复用，无需取消。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
