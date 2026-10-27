package com.aharou.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.settings.presentation.DistillOutcome
import com.aharou.feature.settings.presentation.MemoryActionError
import com.aharou.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Search
import compose.icons.feathericons.Star
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 设置页「记忆」分区。
 *
 * 三块内容：开关与维护动作、按作用域浏览的记忆条目（可编辑）、归档旧版本（可恢复）。
 * 条目与归档都走 [SettingsViewModel] 的数据源（全局 + 项目两个作用域），
 * 只在这一层做展示与编辑——文件路径、frontmatter 都由记忆层负责，界面不直接碰文件。
 */
@Composable
internal fun MemorySection(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val store = remember { AharouMemoryStore(context.applicationContext) }
    val trace = remember { MemoryTraceLog(context.applicationContext) }

    val scope by viewModel.memoryScope.collectAsStateWithLifecycle()
    val entries by viewModel.memoryEntries.collectAsStateWithLifecycle()
    val archives by viewModel.memoryArchives.collectAsStateWithLifecycle()
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()
    val compactResult by viewModel.compactResult.collectAsStateWithLifecycle()
    val distilling by viewModel.distilling.collectAsStateWithLifecycle()
    val distillResult by viewModel.distillResult.collectAsStateWithLifecycle()
    val actionError by viewModel.memoryActionError.collectAsStateWithLifecycle()
    val recallTesting by viewModel.memoryRecallTesting.collectAsStateWithLifecycle()
    val recallReport by viewModel.memoryRecallReport.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(store.isMemoryEnabled()) }
    var records by remember { mutableStateOf(trace.recent(TRACE_DISPLAY_LIMIT)) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }
    var showRecallTest by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 项目作用域是否有工作区可用；没有时入口不给点，免得点进去是一片空白。
    val projectAvailable = viewModel.memoryProjectRoot() != null

    LifecycleResumeEffect(Unit) {
        viewModel.refreshMemory()
        records = trace.recent(TRACE_DISPLAY_LIMIT)
        enabled = store.isMemoryEnabled()
        onPauseOrDispose { }
    }

    LaunchedEffect(distillResult) {
        val result = distillResult ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(
                if (result == DistillOutcome.WRITTEN) R.string.memory_distill_written
                else R.string.memory_distill_nothing
            ),
            Toast.LENGTH_LONG,
        ).show()
        viewModel.consumeDistillResult()
    }

    LaunchedEffect(compactResult) {
        val result = compactResult ?: return@LaunchedEffect
        val text = if (result.merged > 0) {
            context.getString(R.string.memory_compact_merged, result.merged, result.archived)
        } else {
            context.getString(R.string.memory_compact_nothing)
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consumeCompactResult()
    }

    // 写操作（保存 / 删除 / 恢复）的失败提示由 ViewModel 统一给类型，界面只负责取文案并消费掉。
    LaunchedEffect(actionError) {
        val error = actionError ?: return@LaunchedEffect
        val messageRes = when (error) {
            MemoryActionError.NO_WORKSPACE -> R.string.memory_error_no_workspace
            MemoryActionError.SAVE_FAILED -> R.string.memory_error_save_failed
            MemoryActionError.DELETE_FAILED -> R.string.memory_error_delete_failed
            MemoryActionError.RESTORE_FAILED -> R.string.memory_error_restore_failed
        }
        Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        viewModel.consumeMemoryActionError()
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!writing) deleteTarget = null },
            title = { Text(stringResource(R.string.memory_delete_title, target.name)) },
            text = { Text(stringResource(R.string.memory_delete_body)) },
            confirmButton = {
                TextButton(enabled = !writing, onClick = {
                    writing = true
                    coroutineScope.launch {
                        try {
                            if (viewModel.deleteMemoryEntry(target.name)) {
                                deleteTarget = null
                                editing = null
                            }
                        } finally { writing = false }
                    }
                }) { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !writing, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val editingMemory = editing
    if (showRecallTest) {
        MemoryRecallTestSheet(
            testing = recallTesting,
            report = recallReport,
            onRun = viewModel::testMemoryRecall,
            onDismiss = {
                showRecallTest = false
                viewModel.clearMemoryRecallReport()
            },
        )
    }
    if (showProfile) {
        MemoryProfileSheet(
            memories = entries,
            writing = writing,
            onEdit = { memory ->
                // 完整编辑器会接管整块分区，弹层要先收起，否则会盖在编辑器上面。
                showProfile = false
                editing = memory
            },
            onDelete = { memory -> deleteTarget = memory },
            onAddFact = { text ->
                writing = true
                try {
                    viewModel.saveMemoryEntry(
                        name = "profile-${System.currentTimeMillis()}",
                        description = text.lineSequence().firstOrNull().orEmpty()
                            .trim()
                            .take(PROFILE_FACT_DESC_CHARS),
                        content = text,
                        type = MemoryType.PREFERENCE,
                        pending = false,
                    )
                } finally {
                    writing = false
                }
            },
            onDismiss = { showProfile = false },
        )
    }
    if (editingMemory != null) {
        MemoryEditorPane(
            memory = editingMemory,
            writing = writing,
            onBack = { if (!writing) editing = null },
            onSave = { description, content, confirm ->
                writing = true
                coroutineScope.launch {
                    try {
                        val saved = viewModel.saveMemoryEntry(
                            editingMemory.name, description, content, editingMemory.type,
                            pending = if (confirm) false else editingMemory.pending
                        )
                        if (saved) editing = null
                    } finally { writing = false }
                }
            },
            onDelete = { if (!writing) deleteTarget = editingMemory },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.memory_settings_title))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(R.string.memory_profile_title),
                subtitle = stringResource(R.string.memory_profile_subtitle),
                onClick = { showProfile = true }
            )
        }
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Star,
                title = stringResource(R.string.memory_toggle_title),
                subtitle = stringResource(R.string.memory_toggle_subtitle),
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            store.setMemoryEnabled(on)
                        },
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_compact_title),
                subtitle = stringResource(R.string.memory_compact_subtitle),
                trailing = {
                    if (compacting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                onClick = { if (!compacting) viewModel.compactMemory() },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_distill_title),
                subtitle = stringResource(R.string.memory_distill_subtitle),
                trailing = {
                    if (distilling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                onClick = { if (!distilling) viewModel.distillNow() },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Search,
                title = stringResource(R.string.memory_recall_title),
                subtitle = stringResource(R.string.memory_recall_subtitle),
                onClick = { showRecallTest = true },
            )
        }

        MemorySyncSection()

        SettingsGroupHeader(text = stringResource(R.string.memory_entries_header))
        SettingsGroup {
            MemoryScopeSelector(
                current = scope,
                projectAvailable = projectAvailable,
                onSelect = viewModel::setMemoryScope,
            )
        }
        if (entries.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(
                        if (scope == MemoryScope.PROJECT) R.string.memory_entries_empty_project
                        else R.string.memory_entries_empty
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                entries.forEachIndexed { index, memory ->
                    if (index > 0) SettingsDivider()
                    MemoryEntryRow(memory = memory, onClick = { editing = memory })
                }
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_archive_header))
        if (archives.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.memory_archive_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                archives.take(ARCHIVE_DISPLAY_LIMIT).forEachIndexed { index, info ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = info.fileName,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${formatBytes(info.sizeBytes)} · ${formatDate(info.modifiedAt)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(enabled = !writing, onClick = {
                            writing = true
                            coroutineScope.launch {
                                try {
                                    viewModel.restoreMemoryArchive(info.fileName)
                                } finally { writing = false }
                            }
                        }) {
                            Text(stringResource(R.string.memory_archive_restore))
                        }
                    }
                }
            }
        }

        // 写入流水：某条记忆是被覆盖、被跳过还是整理压根没跑，看这里而不是翻文件。
        SettingsGroupHeader(text = stringResource(R.string.memory_trace_header))
        if (records.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.memory_trace_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                records.forEachIndexed { index, record ->
                    if (index > 0) SettingsDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = memoryActionLabel(record.action),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = record.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            )
                            Text(
                                text = formatDate(record.at),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val detail = record.detail
                        if (detail != null) {
                            Text(
                                text = detail,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

}

/** 作用域切换：全局 / 项目。项目无工作区时置灰但仍展示，让用户知道有这么一档。 */
@Composable
private fun MemoryScopeSelector(
    current: MemoryScope,
    projectAvailable: Boolean,
    onSelect: (MemoryScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryScopeTab(
            label = stringResource(R.string.memory_scope_global),
            selected = current == MemoryScope.GLOBAL,
            enabled = true,
            onClick = { onSelect(MemoryScope.GLOBAL) },
        )
        MemoryScopeTab(
            label = stringResource(R.string.memory_scope_project),
            selected = current == MemoryScope.PROJECT,
            enabled = projectAvailable,
            onClick = { onSelect(MemoryScope.PROJECT) },
        )
        if (!projectAvailable) {
            Text(
                text = stringResource(R.string.memory_scope_no_workspace),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryScopeTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 4.dp),
    )
}

/** 一条记忆：名字 + 摘要。分类只对「事件」标注——它不进注入清单，名字本身看不出这点。 */
@Composable
private fun MemoryEntryRow(memory: Memory, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = memory.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (memory.pending) {
                    Text(
                        text = stringResource(R.string.memory_entry_pending),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            Text(
                text = memory.description.ifBlank { stringResource(R.string.memory_entry_no_description) },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val modified = memory.file?.lastModified() ?: 0L
        if (modified > 0L) {
            Text(
                text = formatDate(modified),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 记忆编辑器：摘要与正文各一个输入框，保存即整体覆盖写回（旧版由记忆层归档）。 */
@Composable
private fun MemoryEditorPane(
    memory: Memory,
    writing: Boolean,
    onBack: () -> Unit,
    onSave: (description: String, content: String, confirm: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember(memory.name) { mutableStateOf(memory.description) }
    var content by remember(memory.name) { mutableStateOf(memory.content) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.ArrowLeft,
                title = stringResource(R.string.memory_back),
                onClick = onBack,
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Trash2,
                title = stringResource(R.string.memory_delete),
                onClick = onDelete,
            )
        }

        SettingsGroupHeader(text = memory.name)
        SettingsGroup {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)) {
                AppTextField(
                    value = description,
                    onValueChange = { if (!writing) description = it },
                    label = stringResource(R.string.memory_edit_description),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                AppTextField(
                    value = content,
                    onValueChange = { if (!writing) content = it },
                    label = stringResource(R.string.memory_edit_content),
                    singleLine = false,
                    maxLines = 30,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                Button(
                    onClick = { onSave(description, content, false) },
                    enabled = !writing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.memory_edit_save))
                }
                if (memory.pending) {
                    Text(stringResource(R.string.memory_pending_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !writing, onClick = { onSave(description, content, true) }) {
                        Text(stringResource(R.string.memory_confirm_entry))
                    }
                }
            }
        }
    }
}

/** 把流水里的动作码翻成给用户看的文案。 */
@Composable
private fun memoryActionLabel(action: String): String = stringResource(
    when (action) {
        MemoryTraceLog.ACTION_SAVE -> R.string.memory_trace_action_save
        MemoryTraceLog.ACTION_NEW -> R.string.memory_trace_action_new
        MemoryTraceLog.ACTION_EDIT -> R.string.memory_trace_action_edit
        MemoryTraceLog.ACTION_DELETE -> R.string.memory_trace_action_delete
        MemoryTraceLog.ACTION_ARCHIVE -> R.string.memory_trace_action_archive
        MemoryTraceLog.ACTION_MERGE -> R.string.memory_trace_action_merge
        MemoryTraceLog.ACTION_CONFLICT -> R.string.memory_trace_action_conflict
        MemoryTraceLog.ACTION_SKIP -> R.string.memory_trace_action_skip
        else -> R.string.memory_trace_action_unknown
    }
)

/** 「最近记忆操作」展示条数：够看出最近发生了什么，又不至于把设置页拉得很长。 */
private const val TRACE_DISPLAY_LIMIT = 20

/** 归档展示条数上限：归档会越积越多，界面只给最近的，再旧的靠文件管理器看。 */
private const val ARCHIVE_DISPLAY_LIMIT = 20

/** 画像弹层里「添加一条事实」自动生成的摘要长度：取输入首行的前这么多字。 */
private const val PROFILE_FACT_DESC_CHARS = 60

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
