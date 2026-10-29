package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity

/**
 * 任务卡片上的条件摘要：把用户设过的时段 / 仅充电 / 最低电量拼成一行。
 *
 * 没设条件时返回 null（不占行）；两端都是 00:00 的窗口在求值上等价于不限，这里同样不显示，
 * 免得用户对着一条其实不生效的条件发慌。
 */
@Composable
internal fun conditionSummary(task: ScheduledTaskEntity): String? {
    val parts = ArrayList<String>()
    val unlimitedWindow = task.conditionWindowFrom == 0 && task.conditionWindowTo == 0
    if (!unlimitedWindow && (task.conditionWindowFrom >= 0 || task.conditionWindowTo >= 0)) {
        val from = if (task.conditionWindowFrom >= 0) formatMinute(task.conditionWindowFrom) else "00:00"
        val to = if (task.conditionWindowTo >= 0) formatMinute(task.conditionWindowTo) else "24:00"
        parts.add(stringResource(R.string.scheduled_tasks_condition_window_range, from, to))
    }
    if (task.condRequireCharging) {
        parts.add(stringResource(R.string.scheduled_tasks_condition_charging_only))
    }
    if (task.condMinBattery >= 0) {
        parts.add(stringResource(R.string.scheduled_tasks_condition_battery_only, task.condMinBattery))
    }
    if (parts.isEmpty()) return null
    return stringResource(R.string.scheduled_tasks_conditions_summary, parts.joinToString(" · "))
}

private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
