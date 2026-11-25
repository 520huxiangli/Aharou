package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity

/**
 * 任务卡片上那行「触发方式 · 已运行 N 次」。
 *
 * 从 ScheduledTasksSection 拆出来：那个文件已经贴着 1000 行门禁，新增一种触发类型的文案
 * 没地方放。纯展示函数，不持有任何状态，搬出来没有副作用。
 */
@Composable
internal fun eventDescription(task: ScheduledTaskEntity, limitText: String): String = when (task.triggerType) {
    ScheduledTaskEntity.TYPE_NOTIFICATION -> if (task.triggerPackage.isBlank()) {
        stringResource(R.string.scheduled_tasks_desc_notification, task.runCount, limitText)
    } else {
        stringResource(
            R.string.scheduled_tasks_desc_notification_app,
            task.triggerPackage,
            task.runCount,
            limitText
        )
    }

    ScheduledTaskEntity.TYPE_BATTERY -> when {
        task.triggerBatteryBelow >= 0 -> stringResource(
            R.string.scheduled_tasks_desc_battery_below,
            task.triggerBatteryBelow,
            task.runCount,
            limitText
        )
        task.triggerOnCharging -> stringResource(
            R.string.scheduled_tasks_desc_battery_charging,
            task.runCount,
            limitText
        )
        else -> stringResource(R.string.scheduled_tasks_desc_battery, task.runCount, limitText)
    }

    ScheduledTaskEntity.TYPE_NETWORK -> if (task.triggerSsid.isBlank()) {
        stringResource(R.string.scheduled_tasks_desc_network, task.runCount, limitText)
    } else {
        stringResource(
            R.string.scheduled_tasks_desc_network_ssid,
            task.triggerSsid,
            task.runCount,
            limitText
        )
    }

    ScheduledTaskEntity.TYPE_APP_START -> stringResource(
        R.string.scheduled_tasks_desc_app_start,
        task.runCount,
        limitText
    )

    ScheduledTaskEntity.TYPE_LOCATION -> stringResource(
        R.string.scheduled_tasks_desc_location,
        task.runCount,
        limitText
    )

    ScheduledTaskEntity.TYPE_MCP_RESOURCE -> stringResource(
        R.string.scheduled_tasks_desc_mcp_resource,
        task.runCount,
        limitText
    )

    else -> stringResource(R.string.scheduled_tasks_desc_boot, task.runCount, limitText)
}

/** 触发方式的中文名，供触发方式单选列表使用。 */
@StringRes
internal fun triggerLabel(type: String): Int = when (type) {
    ScheduledTaskEntity.TYPE_NOTIFICATION -> R.string.scheduled_tasks_trigger_notification
    ScheduledTaskEntity.TYPE_BATTERY -> R.string.scheduled_tasks_trigger_battery
    ScheduledTaskEntity.TYPE_NETWORK -> R.string.scheduled_tasks_trigger_network
    ScheduledTaskEntity.TYPE_BOOT -> R.string.scheduled_tasks_trigger_boot
    ScheduledTaskEntity.TYPE_APP_START -> R.string.scheduled_tasks_trigger_app_start
    ScheduledTaskEntity.TYPE_LOCATION -> R.string.scheduled_tasks_trigger_location
    ScheduledTaskEntity.TYPE_MCP_RESOURCE -> R.string.scheduled_tasks_trigger_mcp_resource
    else -> R.string.scheduled_tasks_trigger_time
}
