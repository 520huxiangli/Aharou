/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/components/ScheduleConfigDialog.kt
 *        + screens/WorkflowDetailScreen.kt 的 trigger 分支
 * 改动：
 * - 上游调度是一个独立 AlertDialog，这里内联进节点底栏，改完随节点一起保存，少一层弹窗；
 * - 日期/时间不再用 DatePickerDialog + TimePickerDialog 两个系统弹窗，直接给可输入的时间文本
 *   （模板里本来就是字符串，手输比两级选择快）；
 * - triggerConfig 的键统一取自 WorkflowTriggers 常量，不再散落字符串字面量；
 * - 非调度类触发（tasker/intent/speech/app_open）用键值表编辑，不要求用户手写 JSON。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.WorkflowTriggers

private const val KEY_TRIGGER_TYPE_MANUAL = "manual"
private const val KEY_TRIGGER_TYPE_SCHEDULE = "schedule"

private val CronPresets = listOf(
    "0 0 * * *" to R.string.workflow_editor_cron_daily_midnight,
    "0 9 * * *" to R.string.workflow_editor_cron_daily_morning,
    "0 12 * * *" to R.string.workflow_editor_cron_daily_noon,
    "0 18 * * *" to R.string.workflow_editor_cron_daily_evening,
    "0 */2 * * *" to R.string.workflow_editor_cron_every_2_hours,
    "0 */6 * * *" to R.string.workflow_editor_cron_every_6_hours,
    "*/15 * * * *" to R.string.workflow_editor_cron_every_15_minutes,
    "*/30 * * * *" to R.string.workflow_editor_cron_every_30_minutes,
    "0 0 * * 1" to R.string.workflow_editor_cron_weekly_monday,
    "0 9 * * 1-5" to R.string.workflow_editor_cron_workday_morning,
)

@Composable
fun TriggerNodeSection(
    trigger: TriggerNode,
    onChange: (TriggerNode) -> Unit,
) {
    val config = trigger.triggerConfig
    val typeOptions = listOf(
        KEY_TRIGGER_TYPE_MANUAL to stringResource(R.string.workflow_editor_trigger_manual),
        KEY_TRIGGER_TYPE_SCHEDULE to stringResource(R.string.workflow_editor_trigger_schedule),
        "tasker" to stringResource(R.string.workflow_editor_trigger_tasker),
        "intent" to stringResource(R.string.workflow_editor_trigger_intent),
        "speech" to stringResource(R.string.workflow_editor_trigger_speech),
        "app_open" to stringResource(R.string.workflow_editor_trigger_app_open),
    )

    EditorSectionTitle(stringResource(R.string.workflow_editor_trigger_section))
    EditorPickerField(
        label = stringResource(R.string.workflow_editor_trigger_type),
        value = trigger.triggerType,
        options = typeOptions,
        onSelect = { newType ->
            onChange(
                trigger.copy(
                    triggerType = newType,
                    triggerConfig = when (newType) {
                        KEY_TRIGGER_TYPE_MANUAL -> emptyMap()
                        KEY_TRIGGER_TYPE_SCHEDULE -> config.takeIf {
                            it[WorkflowTriggers.KEY_SCHEDULE_TYPE] != null
                        } ?: mapOf(
                            WorkflowTriggers.KEY_SCHEDULE_TYPE to WorkflowTriggers.TYPE_INTERVAL,
                            WorkflowTriggers.KEY_INTERVAL_MS to DEFAULT_INTERVAL_MS,
                            WorkflowTriggers.KEY_REPEAT to "true",
                            WorkflowTriggers.KEY_ENABLED to "true",
                        )
                        else -> config
                    },
                )
            )
        },
    )

    when (trigger.triggerType) {
        KEY_TRIGGER_TYPE_SCHEDULE -> ScheduleFields(trigger = trigger, onChange = onChange)
        KEY_TRIGGER_TYPE_MANUAL -> Text(
            text = stringResource(R.string.workflow_editor_trigger_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            EditorSectionTitle(stringResource(R.string.workflow_editor_trigger_config_section))
            KeyValueEditors(
                entries = config.entries.map { it.key to it.value },
                onChange = { pairs ->
                    onChange(
                        trigger.copy(
                            triggerConfig = pairs
                                .filter { it.first.isNotBlank() }
                                .associate { it.first.trim() to it.second },
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun ScheduleFields(
    trigger: TriggerNode,
    onChange: (TriggerNode) -> Unit,
) {
    val config = trigger.triggerConfig
    val scheduleType = config[WorkflowTriggers.KEY_SCHEDULE_TYPE]
        ?.takeIf { it.isNotBlank() } ?: WorkflowTriggers.TYPE_INTERVAL

    fun write(key: String, value: String?) {
        val updated = config.toMutableMap()
        if (value == null) updated.remove(key) else updated[key] = value
        onChange(trigger.copy(triggerConfig = updated.toMap()))
    }

    EditorPickerField(
        label = stringResource(R.string.workflow_editor_schedule_type),
        value = scheduleType,
        options = listOf(
            WorkflowTriggers.TYPE_INTERVAL to stringResource(R.string.workflow_editor_schedule_interval),
            WorkflowTriggers.TYPE_SPECIFIC_TIME to stringResource(R.string.workflow_editor_schedule_specific_time),
            WorkflowTriggers.TYPE_CRON to stringResource(R.string.workflow_editor_schedule_cron),
        ),
        onSelect = { write(WorkflowTriggers.KEY_SCHEDULE_TYPE, it) },
    )

    when (scheduleType) {
        WorkflowTriggers.TYPE_INTERVAL -> {
            val minutes = WorkflowTriggers.intervalMillis(trigger) / 60_000L
            var unit by remember(trigger.id) { mutableStateOf(unitFor(minutes)) }
            var valueText by remember(trigger.id) { mutableStateOf(formatAmount(minutes.toDouble() / unitFactor(unit))) }
            AppTextField(
                value = valueText,
                onValueChange = { text ->
                    valueText = text
                    text.toDoubleOrNull()?.let { amount ->
                        write(
                            WorkflowTriggers.KEY_INTERVAL_MS,
                            (amount * unitFactor(unit) * 60_000L).toLong().toString(),
                        )
                    }
                },
                label = stringResource(R.string.workflow_editor_interval_value),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EditorPickerField(
                label = stringResource(R.string.workflow_editor_interval_unit),
                value = unit,
                options = listOf(
                    "minutes" to stringResource(R.string.workflow_editor_unit_minutes),
                    "hours" to stringResource(R.string.workflow_editor_unit_hours),
                    "days" to stringResource(R.string.workflow_editor_unit_days),
                ),
                onSelect = { newUnit ->
                    valueText = formatAmount(minutes.toDouble() / unitFactor(newUnit))
                    unit = newUnit
                },
            )
            Text(
                text = stringResource(R.string.workflow_editor_interval_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }

        WorkflowTriggers.TYPE_SPECIFIC_TIME -> {
            AppTextField(
                value = config[WorkflowTriggers.KEY_SPECIFIC_TIME].orEmpty(),
                onValueChange = { write(WorkflowTriggers.KEY_SPECIFIC_TIME, it.ifBlank { null }) },
                label = stringResource(R.string.workflow_editor_specific_time),
                placeholder = "2026-11-24 08:30",
                singleLine = true,
            )
        }

        WorkflowTriggers.TYPE_CRON -> {
            AppTextField(
                value = config[WorkflowTriggers.KEY_CRON_EXPRESSION].orEmpty(),
                onValueChange = { write(WorkflowTriggers.KEY_CRON_EXPRESSION, it.ifBlank { null }) },
                label = stringResource(R.string.workflow_editor_cron_expression),
                placeholder = "0 9 * * *",
                singleLine = true,
            )
            EditorPickerField(
                label = stringResource(R.string.workflow_editor_cron_preset),
                value = "",
                options = CronPresets.map { (expression, labelRes) -> expression to stringResource(labelRes) },
                onSelect = { write(WorkflowTriggers.KEY_CRON_EXPRESSION, it) },
            )
            Text(
                text = stringResource(R.string.workflow_editor_cron_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
        EditorSwitchRow(
            label = stringResource(R.string.workflow_editor_schedule_repeat),
            checked = WorkflowTriggers.repeats(trigger),
            onCheckedChange = { write(WorkflowTriggers.KEY_REPEAT, it.toString()) },
        )
        EditorSwitchRow(
            label = stringResource(R.string.workflow_editor_schedule_enabled),
            checked = WorkflowTriggers.isEnabled(trigger),
            onCheckedChange = { write(WorkflowTriggers.KEY_ENABLED, it.toString()) },
        )
    }
}

private const val DEFAULT_INTERVAL_MS = "900000"

private fun unitFor(minutes: Long): String = when {
    minutes > 0 && minutes % (24 * 60) == 0L -> "days"
    minutes > 0 && minutes % 60 == 0L -> "hours"
    else -> "minutes"
}

private fun unitFactor(unit: String): Double = when (unit) {
    "days" -> 24.0 * 60.0
    "hours" -> 60.0
    else -> 1.0
}

private fun formatAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
