package com.aharou.feature.settings.presentation.component

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
import com.aharou.feature.settings.presentation.ScheduledTasksViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Plus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 上次错过未补跑的结果标记，与 [ScheduledTaskEntity.lastOutcome] 的取值一致。 */
private const val OUTCOME_SKIPPED = "skipped"

private const val MAX_SESSION_CHOICES = 30

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
                    enabled = draft.enabled
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
                    text = stringResource(
                        R.string.scheduled_tasks_cycle,
                        task.intervalMinutes,
                        task.runCount,
                        limitText
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nextText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.lastOutcome == OUTCOME_SKIPPED) {
                    Text(
                        text = stringResource(R.string.scheduled_tasks_missed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
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

private data class ScheduledTaskDraft(
    val name: String,
    val prompt: String,
    val targetSessionId: String?,
    val workspacePath: String?,
    val intervalMinutes: Int,
    val maxRuns: Int,
    val enabled: Boolean
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
    var maxRunsText by remember { mutableStateOf((initial?.maxRuns ?: 0).toString()) }
    var workspacePath by remember { mutableStateOf(initial?.workspacePath.orEmpty()) }
    var useExisting by remember { mutableStateOf(initial?.targetSessionId != null) }
    var selectedSessionId by remember { mutableStateOf(initial?.targetSessionId) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    val minInterval = ScheduledTaskScheduler.MIN_INTERVAL_MINUTES
    val intervalValue = intervalText.toIntOrNull() ?: 0
    val maxRunsValue = maxRunsText.toIntOrNull() ?: 0
    val canSave = name.isNotBlank() && prompt.isNotBlank() && intervalValue > 0 &&
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

            AppTextField(
                value = intervalText,
                onValueChange = { input -> intervalText = input.filter { it.isDigit() } },
                label = stringResource(R.string.scheduled_tasks_interval),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = intervalValue in 1 until minInterval,
                supportingText = {
                    Text(stringResource(R.string.scheduled_tasks_interval_hint, minInterval))
                }
            )

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }

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
                                enabled = enabled
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

private val taskTimeFormat = SimpleDateFormat("M-d HH:mm", Locale.getDefault())

private fun formatTaskTime(epochMs: Long): String = taskTimeFormat.format(Date(epochMs))
