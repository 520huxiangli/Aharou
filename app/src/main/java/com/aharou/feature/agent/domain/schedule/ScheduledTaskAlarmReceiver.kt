package com.aharou.feature.agent.domain.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aharou.core.util.FileLogger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 精确闹钟到点时的入口：直接走 [ScheduledTaskDispatcher] 认领任务并拉起执行服务。
 *
 * 这里只做「交接」，必须在毫秒级返回：goAsync() 只是把广播的存活窗口往后延一点点，
 * 真跑 agent 的是前台服务。OpenMinis 在同一处踩过——把整轮 agent 循环等在这个广播里，
 * 超时后系统把进程连同容器一起 ANR 杀掉。
 *
 * 选广播而不是 Worker 作为准点路径，是因为精确闹钟的广播带豁免，能直接启动前台服务；
 * Android 12+ 的后台限制下，从 WorkManager Worker 里起前台服务可能被拦。
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledTaskScheduler.ACTION_ALARM) return
        val appContext = context.applicationContext
        val pending = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext, ScheduledTaskEntryPoint::class.java
        )
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 硬顶：正常只需几十毫秒，万一数据库读卡住也不要把广播拖成 ANR。
                withTimeout(HANDOFF_TIMEOUT_MS) {
                    entryPoint.scheduledTaskDispatcher().dispatchDue()
                }
            } catch (t: Throwable) {
                // 任务的下一次触发在认领时已排好，这次失败会自愈。
                FileLogger.e(TAG, "闹钟派发失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ScheduledTaskAlarm"

        /** 清单里声明的接收器约有 10 秒后台预算，8 秒留出记录与收尾的余量。 */
        const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
