package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
import com.aharou.feature.settings.presentation.ScheduledTasksViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Plus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 值得在列表里提示的 [ScheduledTaskEntity.lastOutcome] 取值；其余（如 dispatched）不打扰用户。 */
private const val OUTCOME_SKIPPED = "skipped"
private const val OUTCOME_SKIPPED_MISSED = "skipped_missed"
private const val OUTCOME_SKIPPED_CONDITION = "skipped_condition"
private const val OUTCOME_SKIPPED_DEBOUNCE = "skipped_debounce"
private const val OUTCOME_SKIPPED_RUNNING = "skipped_running"
private const val OUTCOME_FAILED = "failed"

private const val MAX_SESSION_CHOICES = 30

/** 新建任务时防抖窗口的默认分钟数，与 [ScheduledTaskEntity.debounceMinutes] 的默认值一致。 */
private const val DEFAULT_DEBOUNCE_MINUTES = 10

@Composable
internal fun ScheduledTasksSection(viewModel: ScheduledTasksViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    // 下次运行文案里的倒计时需要随时间推进，不需要秒级精度，半分钟刷新一次就够。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_scheduled_tasks))
        SettingsGroup {
            Text(
                text = stringResource(
                    R.string.scheduled_tasks_intro,
                    ScheduledTaskScheduler.MIN_INTERVAL_MINUTES
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.scheduled_tasks_list_header))
        if (tasks.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.scheduled_tasks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp)
                )
            }
        } else {
            SettingsGroup {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) SettingsDivider()
                    ScheduledTaskRow(
                        task = task,
                        now = now,
                        onToggle = { viewModel.setEnabled(task.id, it) },
                        onClick = {
                            editing = task
                            showEditor = true
                        },
                        onDelete = { deleting = task }
                    )
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.scheduled_tasks_add),
                onClick = {
                    editing = null
                    showEditor = true
                }
            )
        }
    }

    if (showEditor) {
        ScheduledTaskEditorSheet(
            initial = editing,
            sessions = sessions,
            onDismiss = { showEditor = false },
            onSave = { draft ->
                viewModel.save(
                    existing = editing,
                    name = draft.name,
                    prompt = draft.prompt,
                    targetSessionId = draft.targetSessionId,
                    workspacePath = draft.workspacePath,
                    intervalMinutes = draft.intervalMinutes,
                    maxRuns = draft.maxRuns,
                    enabled = draft.enabled,
                    scheduleType = draft.scheduleType,
                    dailyMinuteOfDay = draft.dailyMinuteOfDay,
                    onceAtMillis = draft.onceAtMillis,
                    triggerType = draft.triggerType,
                    triggerPackage = draft.triggerPackage,
                    triggerBatteryBelow = draft.triggerBatteryBelow,
                    triggerOnCharging = draft.triggerOnCharging,
                    triggerSsid = draft.triggerSsid,
                    conditionWindowFrom = draft.conditionWindowFrom,
                    conditionWindowTo = draft.conditionWindowTo,
                    condRequireCharging = draft.condRequireCharging,
                    condMinBattery = draft.condMinBattery,
                    debounceMinutes = draft.debounceMinutes
                )
                showEditor = false
            }
        )
    }

    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.scheduled_tasks_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_tasks_delete_message, task.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(task.id)
                        deleting = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun ScheduledTaskRow(
    task: ScheduledTaskEntity,
    now: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface
    val limitText = if (task.maxRuns == 0) {
        stringResource(R.string.scheduled_tasks_limit_unlimited)
    } else {
        stringResource(R.string.scheduled_tasks_limit, task.maxRuns)
    }
    val nextText = when {
        !task.enabled -> stringResource(R.string.scheduled_tasks_disabled)
        // 事件类任务静息时 nextRunAt 停在远期值，只有被事件投递进来才会到点。
        !TriggerType.isTimeTrigger(task) && task.nextRunAt > now ->
            stringResource(R.string.scheduled_tasks_waiting_event)
        task.nextRunAt <= now -> stringResource(R.string.scheduled_tasks_due)
        else -> stringResource(
            R.string.scheduled_tasks_next_at,
            formatTaskTime(task.nextRunAt),
            ((task.nextRunAt - now) / 60_000L).coerceAtLeast(1L)
        )
    }

    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.lg, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Clock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = taskDescription(task, limitText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 条件设了却不展示，用户看到「条件不满足」时无从对照
                conditionSummary(task)?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = nextText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                outcomeLabel(task.lastOutcome)?.let { label ->
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.lastOutcome == OUTCOME_SKIPPED ||
                            task.lastOutcome == OUTCOME_SKIPPED_MISSED
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            AppSwitch(
                checked = task.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

/** 列表第二行的「怎么跑」：时间类看运行方式，事件类看触发方式与触发参数。 */
@Composable
private fun taskDescription(task: ScheduledTaskEntity, limitText: String): String =
    if (TriggerType.isTimeTrigger(task)) {
        timeDescription(task, limitText)
    } else {
        eventDescription(task, limitText)
    }

@Composable
private fun timeDescription(task: ScheduledTaskEntity, limitText: String): String = when (task.scheduleType) {
    ScheduledTaskEntity.TYPE_DAILY -> stringResource(
        R.string.scheduled_tasks_desc_daily,
        formatMinuteOfDay(task.dailyMinuteOfDay),
        task.runCount,
        limitText
    )
    ScheduledTaskEntity.TYPE_ONCE -> stringResource(
        R.string.scheduled_tasks_desc_once,
        formatDateTime(task.onceAtMillis),
        task.runCount,
        limitText
    )
    else -> stringResource(
        R.string.scheduled_tasks_cycle,
        task.intervalMinutes,
        task.runCount,
        limitText
    )
}

@Composable
private fun eventDescription(task: ScheduledTaskEntity, limitText: String): String = when (task.triggerType) {
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

    else -> stringResource(R.string.scheduled_tasks_desc_boot, task.runCount, limitText)
}

/** 上次结果里值得说一句的那几种；没说头的结果返回 null。 */
@StringRes
private fun outcomeLabel(outcome: String?): Int? {
    if (outcome == null) return null
    return when {
        // 带原因后缀的取值见 ScheduledTaskWorker.outcomeOf；旧数据没有后缀，回落到通用文案
        outcome.startsWith("$OUTCOME_SKIPPED_CONDITION:") ->
            conditionReasonLabel(outcome.substringAfter(':'))
        outcome == OUTCOME_SKIPPED_CONDITION -> R.string.scheduled_tasks_skipped_condition
        outcome == OUTCOME_SKIPPED || outcome == OUTCOME_SKIPPED_MISSED -> R.string.scheduled_tasks_missed
        outcome == OUTCOME_SKIPPED_DEBOUNCE -> R.string.scheduled_tasks_skipped_debounce
        outcome == OUTCOME_SKIPPED_RUNNING -> R.string.scheduled_tasks_skipped_running
        outcome == OUTCOME_FAILED -> R.string.scheduled_tasks_failed
        else -> null
    }
}

/** 具体是哪条条件没过；认不出的后缀回落到通用「条件不满足」。 */
@StringRes
private fun conditionReasonLabel(reason: String): Int = when (reason) {
    "out_of_window" -> R.string.scheduled_tasks_skip_reason_window
    "not_charging" -> R.string.scheduled_tasks_skip_reason_charging
    "battery_too_low" -> R.string.scheduled_tasks_skip_reason_battery
    "state_unknown" -> R.string.scheduled_tasks_skip_reason_unknown
    else -> R.string.scheduled_tasks_skipped_condition
}

@StringRes
private fun triggerLabel(type: String): Int = when (type) {
    ScheduledTaskEntity.TYPE_NOTIFICATION -> R.string.scheduled_tasks_trigger_notification
    ScheduledTaskEntity.TYPE_BATTERY -> R.string.scheduled_tasks_trigger_battery
    ScheduledTaskEntity.TYPE_NETWORK -> R.string.scheduled_tasks_trigger_network
    ScheduledTaskEntity.TYPE_BOOT -> R.string.scheduled_tasks_trigger_boot
    ScheduledTaskEntity.TYPE_APP_START -> R.string.scheduled_tasks_trigger_app_start
    else -> R.string.scheduled_tasks_trigger_time
}

private data class ScheduledTaskDraft(
    val name: String,
    val prompt: String,
    val targetSessionId: String?,
    val workspacePath: String?,
    val intervalMinutes: Int,
    val maxRuns: Int,
    val enabled: Boolean,
    val scheduleType: String,
    val dailyMinuteOfDay: Int,
    val onceAtMillis: Long,
    val triggerType: String,
    val triggerPackage: String,
    val triggerBatteryBelow: Int,
    val triggerOnCharging: Boolean,
    val triggerSsid: String,
    val conditionWindowFrom: Int,
    val conditionWindowTo: Int,
    val condRequireCharging: Boolean,
    val condMinBattery: Int,
    val debounceMinutes: Int
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTaskEditorSheet(
    initial: ScheduledTaskEntity?,
    sessions: List<ChatSessionEntity>,
    onDismiss: () -> Unit,
    onSave: (ScheduledTaskDraft) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var intervalText by remember {
        mutableStateOf((initial?.intervalMinutes ?: ScheduledTaskScheduler.MIN_INTERVAL_MINUTES).toString())
    }
    var kindType by remember { mutableStateOf(initial?.scheduleType ?: ScheduledTaskEntity.TYPE_INTERVAL) }
    var dailyText by remember { mutableStateOf(formatMinuteOfDay(initial?.dailyMinuteOfDay ?: 9 * 60)) }
    var onceText by remember {
        mutableStateOf(initial?.onceAtMillis?.takeIf { it > 0L }?.let { formatDateTime(it) }.orEmpty())
    }
    var maxRunsText by remember { mutableStateOf((initial?.maxRuns ?: 0).toString()) }
    var workspacePath by remember { mutableStateOf(initial?.workspacePath.orEmpty()) }
    var useExisting by remember { mutableStateOf(initial?.targetSessionId != null) }
    var selectedSessionId by remember { mutableStateOf(initial?.targetSessionId) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var triggerType by remember { mutableStateOf(initial?.triggerType ?: ScheduledTaskEntity.TYPE_TIME) }
    var triggerPackage by remember { mutableStateOf(initial?.triggerPackage.orEmpty()) }
    var chargingStart by remember { mutableStateOf(initial?.triggerOnCharging ?: false) }
    var ssid by remember { mutableStateOf(initial?.triggerSsid.orEmpty()) }
    var requireCharging by remember { mutableStateOf(initial?.condRequireCharging ?: false) }
    var debounceText by remember {
        mutableStateOf((initial?.debounceMinutes ?: DEFAULT_DEBOUNCE_MINUTES).toString())
    }
    // 数值型参数在表单里用空串表示「不限」：-1 这类哨兵值不摆到界面上给用户看。
    var batteryBelowText by remember { mutableStateOf(percentText(initial?.triggerBatteryBelow)) }
    var minBatteryText by remember { mutableStateOf(percentText(initial?.condMinBattery)) }
    var windowFromText by remember { mutableStateOf(minuteText(initial?.conditionWindowFrom)) }
    var windowToText by remember { mutableStateOf(minuteText(initial?.conditionWindowTo)) }

    val isTimeTrigger = TriggerType.isTimeTrigger(triggerType)
    val minInterval = ScheduledTaskScheduler.MIN_INTERVAL_MINUTES
    val intervalValue = intervalText.toIntOrNull() ?: 0
    val maxRunsValue = maxRunsText.toIntOrNull() ?: 0
    val dailyMinute = parseMinuteOfDay(dailyText)
    val onceMillis = parseDateTime(onceText)
    val batteryBelow = batteryBelowText.toIntOrNull() ?: -1
    val minBattery = minBatteryText.toIntOrNull() ?: -1
    val windowFrom = parseMinuteOfDay(windowFromText)
    val windowTo = parseMinuteOfDay(windowToText)
    val debounceMinutes = debounceText.toIntOrNull() ?: 0
    val batteryBelowInvalid = batteryBelowText.isNotBlank() && batteryBelow !in 0..100
    val minBatteryInvalid = minBatteryText.isNotBlank() && minBattery !in 0..100
    val windowFromInvalid = windowFromText.isNotBlank() && windowFrom == null
    val windowToInvalid = windowToText.isNotBlank() && windowTo == null
    val scheduleReady = when {
        // 事件类触发不看到点时间，时间参数空着也照样能存。
        !isTimeTrigger -> true
        kindType == ScheduledTaskEntity.TYPE_DAILY -> dailyMinute != null
        kindType == ScheduledTaskEntity.TYPE_ONCE -> onceMillis != null
        else -> intervalValue > 0
    }
    val canSave = name.isNotBlank() && prompt.isNotBlank() && scheduleReady &&
        !batteryBelowInvalid && !minBatteryInvalid && !windowFromInvalid && !windowToInvalid &&
        (!useExisting || selectedSessionId != null)

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.scheduled_tasks_editor_new
                    else R.string.scheduled_tasks_editor_edit
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.md)
            )

            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.scheduled_tasks_name)
            )

            AppTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = stringResource(R.string.scheduled_tasks_prompt),
                singleLine = false,
                minLines = 3
            )

            Text(
                text = stringResource(R.string.scheduled_tasks_trigger),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                TriggerType.ALL.forEach { type ->
                    TargetChoiceRow(
                        title = stringResource(triggerLabel(type)),
                        selected = triggerType == type,
                        onClick = { triggerType = type }
                    )
                }
            }

            // 运行方式与时间输入只对「按时间」有意义；选了事件就走各自的参数输入。
            if (isTimeTrigger) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_kind),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    TargetChoiceRow(
                        title = stringResource(R.string.scheduled_tasks_kind_interval),
                        selected = kindType == ScheduledTaskEntity.TYPE_INTERVAL,
                        onClick = { kindType = ScheduledTaskEntity.TYPE_INTERVAL }
                    )
                    TargetChoiceRow(
                        title = stringResource(R.string.scheduled_tasks_kind_daily),
                        selected = kindType == ScheduledTaskEntity.TYPE_DAILY,
                        onClick = { kindType = ScheduledTaskEntity.TYPE_DAILY }
                    )
                    TargetChoiceRow(
                        title = stringResource(R.string.scheduled_tasks_kind_once),
                        selected = kindType == ScheduledTaskEntity.TYPE_ONCE,
                        onClick = { kindType = ScheduledTaskEntity.TYPE_ONCE }
                    )
                }

                when (kindType) {
                    ScheduledTaskEntity.TYPE_DAILY -> AppTextField(
                        value = dailyText,
                        onValueChange = { dailyText = it },
                        label = stringResource(R.string.scheduled_tasks_daily_time),
                        isError = dailyMinute == null,
                        supportingText = { Text(stringResource(R.string.scheduled_tasks_daily_time_hint)) }
                    )

                    ScheduledTaskEntity.TYPE_ONCE -> AppTextField(
                        value = onceText,
                        onValueChange = { onceText = it },
                        label = stringResource(R.string.scheduled_tasks_once_time),
                        isError = onceMillis == null,
                        supportingText = { Text(stringResource(R.string.scheduled_tasks_once_time_hint)) }
                    )

                    else -> AppTextField(
                        value = intervalText,
                        onValueChange = { input -> intervalText = input.filter { it.isDigit() } },
                        label = stringResource(R.string.scheduled_tasks_interval),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = intervalValue in 1 until minInterval,
                        supportingText = {
                            Text(stringResource(R.string.scheduled_tasks_interval_hint, minInterval))
                        }
                    )
                }
            } else {
                when (triggerType) {
                    ScheduledTaskEntity.TYPE_NOTIFICATION -> AppTextField(
                        value = triggerPackage,
                        onValueChange = { triggerPackage = it },
                        label = stringResource(R.string.scheduled_tasks_trigger_package),
                        supportingText = {
                            Text(stringResource(R.string.scheduled_tasks_trigger_package_hint))
                        }
                    )

                    ScheduledTaskEntity.TYPE_BATTERY -> {
                        AppTextField(
                            value = batteryBelowText,
                            onValueChange = { input -> batteryBelowText = input.filter { it.isDigit() } },
                            label = stringResource(R.string.scheduled_tasks_trigger_battery_below),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = batteryBelowInvalid,
                            supportingText = {
                                Text(stringResource(R.string.scheduled_tasks_trigger_battery_below_hint))
                            }
                        )
                        SwitchRow(
                            title = stringResource(R.string.scheduled_tasks_trigger_on_charging),
                            checked = chargingStart,
                            onCheckedChange = { chargingStart = it }
                        )
                    }

                    ScheduledTaskEntity.TYPE_NETWORK -> AppTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = stringResource(R.string.scheduled_tasks_trigger_ssid),
                        supportingText = {
                            Text(stringResource(R.string.scheduled_tasks_trigger_ssid_hint))
                        }
                    )

                    ScheduledTaskEntity.TYPE_APP_START -> Text(
                        text = stringResource(R.string.scheduled_tasks_trigger_app_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> Text(
                        text = stringResource(R.string.scheduled_tasks_trigger_boot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.scheduled_tasks_target),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                TargetChoiceRow(
                    title = stringResource(R.string.scheduled_tasks_target_new),
                    selected = !useExisting,
                    onClick = { useExisting = false }
                )
                TargetChoiceRow(
                    title = stringResource(R.string.scheduled_tasks_target_existing),
                    selected = useExisting,
                    onClick = { useExisting = true }
                )
            }

            if (useExisting) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_sessions_header),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.scheduled_tasks_sessions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    sessions.take(MAX_SESSION_CHOICES).forEach { session ->
                        SessionChoiceRow(
                            title = session.title.ifBlank { stringResource(R.string.scheduled_tasks_unnamed) },
                            subtitle = session.workspacePath,
                            selected = session.id == selectedSessionId,
                            onClick = { selectedSessionId = session.id }
                        )
                    }
                }
            } else {
                AppTextField(
                    value = workspacePath,
                    onValueChange = { workspacePath = it },
                    label = stringResource(R.string.scheduled_tasks_workspace),
                    supportingText = {
                        Text(stringResource(R.string.scheduled_tasks_workspace_hint))
                    }
                )
            }

            AppTextField(
                value = maxRunsText,
                onValueChange = { input -> maxRunsText = input.filter { it.isDigit() } },
                label = stringResource(R.string.scheduled_tasks_max_runs),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(stringResource(R.string.scheduled_tasks_max_runs_hint))
                }
            )

            Text(
                text = stringResource(R.string.scheduled_tasks_conditions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppTextField(
                    value = windowFromText,
                    onValueChange = { windowFromText = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.scheduled_tasks_condition_window_from),
                    isError = windowFromInvalid
                )
                AppTextField(
                    value = windowToText,
                    onValueChange = { windowToText = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.scheduled_tasks_condition_window_to),
                    isError = windowToInvalid
                )
            }
            Text(
                text = stringResource(R.string.scheduled_tasks_condition_window_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow(
                title = stringResource(R.string.scheduled_tasks_condition_charging),
                checked = requireCharging,
                onCheckedChange = { requireCharging = it }
            )
            AppTextField(
                value = minBatteryText,
                onValueChange = { input -> minBatteryText = input.filter { it.isDigit() } },
                label = stringResource(R.string.scheduled_tasks_condition_min_battery),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = minBatteryInvalid,
                supportingText = {
                    Text(stringResource(R.string.scheduled_tasks_condition_min_battery_hint))
                }
            )

            // 防抖只对事件触发生效：时间类任务的节奏由周期决定，摆出来只会误导。
            if (!isTimeTrigger) {
                AppTextField(
                    value = debounceText,
                    onValueChange = { input -> debounceText = input.filter { it.isDigit() } },
                    label = stringResource(R.string.scheduled_tasks_debounce),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.scheduled_tasks_debounce_hint)) }
                )
            }

            SwitchRow(
                title = stringResource(R.string.scheduled_tasks_enabled),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                TextButton(
                    enabled = canSave,
                    onClick = {
                        onSave(
                            ScheduledTaskDraft(
                                name = name.trim(),
                                prompt = prompt.trim(),
                                targetSessionId = if (useExisting) selectedSessionId else null,
                                workspacePath = if (useExisting) null else workspacePath.trim().ifBlank { null },
                                intervalMinutes = intervalValue,
                                maxRuns = maxRunsValue,
                                enabled = enabled,
                                scheduleType = kindType,
                                dailyMinuteOfDay = dailyMinute ?: (9 * 60),
                                onceAtMillis = onceMillis ?: 0L,
                                triggerType = triggerType,
                                triggerPackage = triggerPackage.trim(),
                                triggerBatteryBelow = batteryBelow.coerceIn(-1, 100),
                                triggerOnCharging = chargingStart,
                                triggerSsid = ssid.trim(),
                                conditionWindowFrom = windowFrom ?: -1,
                                conditionWindowTo = windowTo ?: -1,
                                condRequireCharging = requireCharging,
                                condMinBattery = minBattery.coerceIn(-1, 100),
                                debounceMinutes = debounceMinutes.coerceAtLeast(0)
                            )
                        )
                    }
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun TargetChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SessionChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 分钟数型参数：-1（不限）在表单里显示为空串。 */
private fun minuteText(minuteOfDay: Int?): String =
    minuteOfDay?.takeIf { it >= 0 }?.let { formatMinuteOfDay(it) }.orEmpty()

/** 百分比型参数：-1（不限）在表单里显示为空串。 */
private fun percentText(percent: Int?): String = percent?.takeIf { it >= 0 }?.toString().orEmpty()

private val taskTimeFormat = SimpleDateFormat("M-d HH:mm", Locale.getDefault())

private fun formatTaskTime(epochMs: Long): String = taskTimeFormat.format(Date(epochMs))

private val taskDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatDateTime(epochMs: Long): String = taskDateTimeFormat.format(Date(epochMs))

private fun formatMinuteOfDay(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** 解析「HH:mm」；非法输入返回 null，由表单据此禁用保存。 */
private fun parseMinuteOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private val taskDateTimePatterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")

/** 解析「yyyy-MM-dd HH:mm」（秒与纯日期也接受）；非法输入返回 null。 */
private fun parseDateTime(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    for (pattern in taskDateTimePatterns) {
        val millis = runCatching {
            SimpleDateFormat(pattern, Locale.getDefault())
                .apply { isLenient = false }
                .parse(trimmed)
                ?.time
        }.getOrNull()
        if (millis != null) return millis
    }
    return null
}
