package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import com.aharou.R

/**
 * 任务上次结果的取值与文案。
 *
 * 取值必须与 `ScheduledTaskWorker` / `ScheduledTaskDispatcher` 写库时的字面量一致，
 * 改这里等于改数据格式，改前先搜那两处。
 *
 * 从 ScheduledTasksSection 拆出来：那个文件贴着行数门禁，新增触发类型后没地方放了。
 */
internal const val OUTCOME_SKIPPED = "skipped"
internal const val OUTCOME_SKIPPED_MISSED = "skipped_missed"
internal const val OUTCOME_SKIPPED_CONDITION = "skipped_condition"
internal const val OUTCOME_SKIPPED_DEBOUNCE = "skipped_debounce"
internal const val OUTCOME_SKIPPED_RUNNING = "skipped_running"
internal const val OUTCOME_SKIPPED_DUPLICATE = "skipped_duplicate"
internal const val OUTCOME_SKIPPED_DEDUP_FULL = "skipped_dedup_full"
internal const val OUTCOME_FAILED = "failed"

/** 上次结果里值得说一句的那几种；没说头的结果返回 null。 */
@StringRes
internal fun outcomeLabel(outcome: String?): Int? {
    if (outcome == null) return null
    return when {
        // 带原因后缀的取值见 ScheduledTaskWorker.outcomeOf；旧数据没有后缀，回落到通用文案
        outcome.startsWith("$OUTCOME_SKIPPED_CONDITION:") ->
            conditionReasonLabel(outcome.substringAfter(':'))
        outcome == OUTCOME_SKIPPED_CONDITION -> R.string.scheduled_tasks_skipped_condition
        outcome == OUTCOME_SKIPPED || outcome == OUTCOME_SKIPPED_MISSED -> R.string.scheduled_tasks_missed
        outcome == OUTCOME_SKIPPED_DEBOUNCE -> R.string.scheduled_tasks_skipped_debounce
        outcome == OUTCOME_SKIPPED_RUNNING -> R.string.scheduled_tasks_skipped_running
        outcome == OUTCOME_SKIPPED_DUPLICATE -> R.string.scheduled_tasks_skipped_duplicate
        outcome == OUTCOME_SKIPPED_DEDUP_FULL -> R.string.scheduled_tasks_skipped_dedup_full
        outcome == OUTCOME_FAILED -> R.string.scheduled_tasks_failed
        else -> null
    }
}

/** 具体是哪条条件没过；认不出的后缀回落到通用「条件不满足」。 */
@StringRes
internal fun conditionReasonLabel(reason: String): Int = when (reason) {
    "out_of_window" -> R.string.scheduled_tasks_skip_reason_window
    "not_charging" -> R.string.scheduled_tasks_skip_reason_charging
    "battery_too_low" -> R.string.scheduled_tasks_skip_reason_battery
    "state_unknown" -> R.string.scheduled_tasks_skip_reason_unknown
    else -> R.string.scheduled_tasks_skipped_condition
}
