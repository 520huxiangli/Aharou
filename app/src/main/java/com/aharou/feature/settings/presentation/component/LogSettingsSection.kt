package com.aharou.feature.settings.presentation.component

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.LogLevelColors
import com.aharou.core.theme.semanticColors
import com.aharou.core.util.LogLevel
import com.aharou.feature.settings.presentation.LogViewerUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 日志合并页：日志等级（点击弹底部面板）+ 日志文件切换 + 搜索/复制 + 结构化彩色日志列表。
 * 页面布局对齐设置页分组风格；日志行按 logcat 风格渲染（等级徽章 + 时间 + tag + 消息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogSection(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit
) {
    var showLevelSheet by remember { mutableStateOf(false) }
    var showFileSheet by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val entries = remember(state.content) { parseLogLines(state.content) }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.raw.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // ── 日志等级 + 日志文件：同一卡片两行 ──
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.log_level),
                onClick = { showLevelSheet = true },
                trailing = {
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = logLevelColor(current).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(logLevelColor(current), RoundedCornerShape(Radius.pill))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = current.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = logLevelColor(current)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.log_files),
                subtitle = state.selectedFileName
                    ?.removePrefix("log-")
                    ?.removeSuffix(".txt")
                    ?: stringResource(R.string.settings_log_no_files),
                onClick = { showFileSheet = true },
                enabled = state.files.isNotEmpty()
            )
        }

        // ── 搜索 + 复制 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.log_search_hint),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            CopyLogButton(
                text = filtered.joinToString("\n") { it.raw },
                enabled = filtered.isNotEmpty()
            )
        }

        // ── MCP 过滤徽章（从 MCP 弹窗进入时显示，可清除）──
        state.filterServerName?.let { server ->
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_mcp_prefix, server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = onClearFilter,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = stringResource(R.string.log_clear_filter),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // ── 日志内容 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(Radius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogContent(
                state = state,
                entries = filtered,
                searchActive = query.isNotBlank(),
                onRefresh = onRefresh
            )
        }
    }

    if (showLevelSheet) {
        LogLevelSheet(
            current = current,
            onSelect = {
                onSelect(it)
                showLevelSheet = false
            },
            onDismiss = { showLevelSheet = false }
        )
    }

    if (showFileSheet) {
        LogFileSheet(
            files = state.files,
            selectedFileName = state.selectedFileName,
            onSelect = {
                onSelectFile(it)
                showFileSheet = false
            },
            onDismiss = { showFileSheet = false }
        )
    }
}

/** 日志内容卡片：loading / 错误 / 空态 / 统计行 + 结构化彩色列表；实时滚动开启时每 3 秒静默刷新并跟随最新日志。 */
@Composable
private fun LogContent(
    state: LogViewerUiState,
    entries: List<LogEntry>,
    searchActive: Boolean,
    onRefresh: () -> Unit
) {
    when {
        state.loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }

        state.error != null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        entries.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    if (searchActive || state.filterServerName != null) {
                        R.string.log_no_match
                    } else {
                        R.string.settings_log_no_files
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> {
            val listState = rememberLazyListState()
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            var copiedIndex by remember { mutableStateOf<Int?>(null) }
            var menuIndex by remember { mutableStateOf<Int?>(null) }
            var followLatest by remember { mutableStateOf(true) }

            // 实时滚动：开启时每 3 秒静默刷新，跟随最新日志
            LaunchedEffect(followLatest, state.selectedFileName, state.filterServerName) {
                if (followLatest) {
                    while (true) {
                        delay(3000)
                        onRefresh()
                    }
                }
            }

            LaunchedEffect(entries.size, state.selectedFileName, state.filterServerName, followLatest) {
                if (followLatest && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
            }
            LaunchedEffect(copiedIndex) {
                if (copiedIndex != null) {
                    delay(1500)
                    copiedIndex = null
                }
            }

            fun copyLog(text: String) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val context = LocalContext.current
                val summary = if (searchActive) {
                    context.getString(R.string.log_search_results, entries.size)
                } else if (state.totalLines > state.shownLines) {
                    context.getString(R.string.log_show_last_lines, entries.size, state.totalLines)
                } else {
                    context.getString(R.string.log_show_lines, entries.size)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.log_follow_latest),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    AppSwitch(
                        checked = followLatest,
                        onCheckedChange = { followLatest = it }
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(entries) { index, entry ->
                        LogLine(
                            entry = entry,
                            copied = copiedIndex == index,
                            onClick = {
                                copyLog(entry.raw)
                                copiedIndex = index
                            },
                            onLongClick = { menuIndex = index }
                        )
                    }
                }
            }

            // 长按自定义复制：本条 / 本条及堆栈 / 消息内容
            menuIndex?.let { index ->
                val entry = entries.getOrNull(index) ?: return@let
                val stackLines = entries.drop(index + 1).takeWhile { it.level == null }
                LogLineActionsSheet(
                    hasStack = stackLines.isNotEmpty(),
                    onCopyLine = {
                        copyLog(entry.raw)
                        copiedIndex = index
                        menuIndex = null
                    },
                    onCopyWithStack = {
                        copyLog((listOf(entry) + stackLines).joinToString("\n") { it.raw })
                        copiedIndex = index
                        menuIndex = null
                    },
                    onCopyMessage = {
                        copyLog(entry.message)
                        copiedIndex = index
                        menuIndex = null
                    },
                    onDismiss = { menuIndex = null }
                )
            }
        }
    }
}

/** 单条日志行：等级徽章（定宽对齐）+ 时间/tag/消息分色拼接，非日志行（堆栈续行/文件头标记）整行灰色。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogLine(
    entry: LogEntry,
    copied: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.md, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (entry.level != null) {
            LogBadge(level = entry.level)
        } else {
            Spacer(Modifier.width(56.dp))
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = entry.displayText(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp
            ),
            color = if (entry.level == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        if (copied) {
            Icon(
                FeatherIcons.Check,
                contentDescription = stringResource(R.string.log_copied),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = Spacing.xs, top = 2.dp)
                    .size(14.dp)
            )
        }
    }
}

/** 等级徽章：固定宽度、等级色浅底 + 深色文字，logcat 风格。 */
@Composable
private fun LogBadge(level: LogLevel) {
    val color = logLevelColor(level)
    Box(
        modifier = Modifier
            .width(56.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = level.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = color,
            maxLines = 1
        )
    }
}

/** 复制按钮：胶囊样式对齐搜索框，点击复制后短暂切换为对勾。 */
@Composable
private fun CopyLogButton(text: String, enabled: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val idleColor = MaterialTheme.semanticColors.capsuleSurface
    Surface(
        onClick = {
            if (enabled) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
                    copied = true
                }
            }
        },
        shape = RoundedCornerShape(50),
        color = when {
            copied -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            enabled -> idleColor
            else -> idleColor.copy(alpha = 0.5f)
        }
    ) {
        Icon(
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = stringResource(if (copied) R.string.log_copied else R.string.log_copy),
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(12.dp)
                .size(18.dp)
        )
    }
}

/** 日志等级底部面板：6 级单选列表，选中项右侧对勾，底部附文件位置说明。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLevelSheet(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_level),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_level_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Spacer(Modifier.height(Spacing.sm))
            LogLevel.entries.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(logLevelColor(level), RoundedCornerShape(Radius.pill))
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = level.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (level == current) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.log_file_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = Spacing.xl)
            )
        }
    }
}

/** 日志文件底部面板：日期列表可滚动选择，选中项右侧对勾。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileSheet(
    files: List<String>,
    selectedFileName: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.log_files),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            files.forEach { fileName ->
                val selected = fileName == selectedFileName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(fileName) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fileName.removePrefix("log-").removeSuffix(".txt"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 日志行长按操作面板：自定义复制范围（本条 / 本条及堆栈 / 仅消息内容）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLineActionsSheet(
    hasStack: Boolean,
    onCopyLine: () -> Unit,
    onCopyWithStack: () -> Unit,
    onCopyMessage: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_copy_menu_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            LogActionRow(
                icon = FeatherIcons.Copy,
                title = stringResource(R.string.log_copy_line),
                onClick = onCopyLine
            )
            if (hasStack) {
                LogActionRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.log_copy_with_stack),
                    onClick = onCopyWithStack
                )
            }
            LogActionRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.log_copy_message),
                onClick = onCopyMessage
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 操作面板行：左侧图标 + 标题，整行可点击。 */
@Composable
private fun LogActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 等级主题色：统一使用 LogLevelColors 调色板。 */
private fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> LogLevelColors.Verbose
    LogLevel.DEBUG -> LogLevelColors.Debug
    LogLevel.INFO -> LogLevelColors.Info
    LogLevel.WARN -> LogLevelColors.Warn
    LogLevel.ERROR -> LogLevelColors.Error
    LogLevel.NONE -> LogLevelColors.None
}

/** 解析后的一条日志。level 为 null 表示非标准日志行（堆栈续行、文件头重置标记等）。 */
private data class LogEntry(
    val level: LogLevel?,
    val time: String?,
    val tag: String?,
    val message: String,
    val raw: String
)

/** 与 [com.aharou.core.util.FileLogger] 落盘格式匹配：`yyyy-MM-dd HH:mm:ss.SSS LEVEL [TAG] message`。 */
private val LOG_LINE_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (VERBOSE|DEBUG|INFO|WARN|ERROR)(?: \[([^\]]*)\])? (.*)$"""
)

/** 把日志文本解析为行列表：标准日志行拆出等级/时间/tag/消息，其余行原样保留（堆栈续行、重置标记）。 */
private fun parseLogLines(content: String): List<LogEntry> {
    val result = ArrayList<LogEntry>()
    for (line in content.lineSequence()) {
        val match = LOG_LINE_REGEX.matchEntire(line)
        if (match != null) {
            result.add(
                LogEntry(
                    level = LogLevel.valueOf(match.groupValues[2]),
                    time = match.groupValues[1],
                    tag = match.groupValues[3].takeIf { it.isNotEmpty() },
                    message = match.groupValues[4],
                    raw = line
                )
            )
        } else {
            result.add(LogEntry(level = null, time = null, tag = null, message = line, raw = line))
        }
    }
    return result
}

/** 日志行显示文本：时间灰、tag 蓝灰、消息主题色；非标准行整行原样。 */
private fun LogEntry.displayText(): AnnotatedString {
    val level = level ?: return AnnotatedString(raw)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Color(0xFF9CA3AF))) {
            append(time ?: "")
            append(" ")
        }
        if (tag != null) {
            withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                append("[$tag] ")
            }
        }
        append(message)
    }
}
