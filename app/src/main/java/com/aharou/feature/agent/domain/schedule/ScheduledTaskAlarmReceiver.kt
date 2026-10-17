package com.aharou.feature.agent.domain.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 精确闹钟到点时被系统唤醒，立刻派一次检查 Worker。
 *
 * 只做「催一次」，不含任何业务逻辑：认领与派发仍由 [ScheduledTaskWorker] 走同一套流程，
 * 这样精确触发与周期兜底的行为不可能分叉。事件投递走的也是同一个催醒入口。
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledTaskScheduler.ACTION_ALARM) return
        ScheduledTaskScheduler.scheduleNow(context)
    }
}