package com.aharou.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.SegmentedTabs
import com.aharou.core.ui.rememberImeBottomInset
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.presentation.AgentUIState
import com.aharou.feature.agent.presentation.ChatSearchHit
import com.aharou.feature.agent.presentation.ChatSearchState
import com.aharou.feature.agent.presentation.BrowseClipboard
import com.aharou.feature.agent.presentation.FileBrowseState
import com.aharou.feature.agent.presentation.FileSearchState
import com.aharou.feature.agent.presentation.FileSearchViewModel
import com.aharou.feature.agent.presentation.FileTreeNode
import com.aharou.feature.agent.presentation.displayName
import com.aharou.feature.workspace.domain.isValidFileEntryName
import com.aharou.feature.workspace.domain.model.Workspace
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Globe
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import androidx.compose.ui.res.stringResource
import com.aharou.R
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * 侧边栏内容：顶部 Tab 切换「会话」/「文件」，底部「设置」入口卡片。
 * Tab0 为根会话列表，带子代理的会话行可就地展开；Tab1 为当前工作区的文件树。
 */

/**
 * Tab1：当前工作区的文件树。从工作区根就地展开，缩进表示层级；
 * 每行共享同一横向滚动，路径过深时左右滑动查看完整名称，不再截断成省略号。
 * 新建文件/文件夹通过长按目录（含工作区根）行的菜单发起；文件与目录都可在同一菜单里「加入输入栏」。
 */
@Composable
internal fun FileBrowserTab(
    state: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onAddToInput: (String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit
) {
    var creating by remember { mutableStateOf<CreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    val hScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.detail ?: stringResource(R.string.file_browser_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }

            is FileBrowseState.Success -> {
                val listState = rememberLazyListState()
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                // 预算最宽一行的内容宽度，供所有行取统一宽度以实现整树横向平移（而非每行各自滚动）。
                // 逐个 measure 在大目录下会在组合期同步跑上千次文本测量，故先按「缩进 + 字符宽度权重」
                // 挑出最宽那行只测一次；估算不精确，但这里只用来给横向滚动留够宽度。
                val maxContentPx = remember(state.nodes, textStyle) {
                    with(density) {
                        val widest = state.nodes.maxByOrNull { node ->
                            node.depth * 4 + node.displayName.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }
                        } ?: return@with 0
                        val textW = textMeasurer.measure(widest.displayName, textStyle).size.width
                        (FILE_TREE_ROW_OVERHEAD.toPx() + (Spacing.lg * widest.depth).toPx() + textW).toInt()
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                    val rowWidth = with(density) { rowWidthPx.toDp() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(hScroll)
                    ) {
                        items(state.nodes, key = { it.path }) { node ->
                            FileTreeRow(
                                node = node,
                                rowWidth = rowWidth,
                                expanding = node.entry.isDirectory && node.path == expandingPath,
                                busy = node.path in fileOpPaths,
                                onClick = {
                                    if (node.entry.isDirectory) onToggleExpand(node.path)
                                    else onOpenFile(node.path)
                                },
                                onLongClick = { menuNode = node }
                            )
                        }
                    }
                }
            }
        }

        // 右上角操作排：剪贴板指示器（有背景、有边框，与无背景的刷新图标区分）+ 刷新按钮。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            clipboard?.let { clip ->
                ClipboardIndicator(
                    clipboard = clip,
                    onClear = onClearClipboard,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            if (fileOpPaths.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = Spacing.xs).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = stringResource(
                if (target.kind == CreateKind.FILE) R.string.file_browser_new_file
                else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (target.kind == CreateKind.FILE) onCreateFile(target.parent, name)
                else onCreateFolder(target.parent, name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onNewFile = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onCopy = {
                menuNode = null
                onCopyEntry(node.path, node.entry.name)
            },
            onCut = {
                menuNode = null
                onCutEntry(node.path, node.entry.name)
            },
            onAddToInput = {
                val path = node.path
                menuNode = null
                onAddToInput(path)
            },
            onPasteHere = {
                val target = node.path
                menuNode = null
                onPasteEntry(target) { /* 结果由调用方决定是否提示，这里保持静默 */ }
            },
            onDismiss = { menuNode = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = node.entry.name,
            onConfirm = { name ->
                onRenameEntry(node.path, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (node.entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        node.entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(node.path)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 新建对象类型，决定确认后调创建文件还是创建文件夹。 */
private enum class CreateKind { FILE, FOLDER }

/** 新建目标：在哪个目录（[parent]）下新建，以及新建文件还是文件夹（[kind]）。 */
private data class CreateTarget(val parent: String, val kind: CreateKind)

/** 新建 / 重命名共用的名称输入弹窗：名称非法或与原名相同时禁用确认。 */
@Composable
internal fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 剪切板指示器：右上角刷新按钮旁的主题色图标，显示复制/剪切状态，点击清空。 */
@Composable
internal fun ClipboardIndicator(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClear,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (clipboard.isCut) Icons.Filled.ContentCut else FeatherIcons.Copy,
            contentDescription = stringResource(
                if (clipboard.isCut) R.string.file_browser_clipboard_cut
                else R.string.file_browser_clipboard_copy
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** 文件树节点长按弹出的功能菜单：文件与目录均可「加入输入栏」，目录（含工作区根）可新建，
 * 非根节点可复制/剪切/重命名/删除，剪切板非空时目录可「粘贴到此处」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onAddToInput: () -> Unit,
    onPasteHere: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            // 剪切板非空且当前节点是目录 / 根：优先提供「粘贴到此处」。
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile
                )
                SheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder
                )
            }
            if (node.root == null) {
                SheetActionRow(
                    icon = FeatherIcons.MessageSquare,
                    label = stringResource(R.string.common_add_to_input),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onAddToInput
                )
                SheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy
                )
                SheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut
                )
                SheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename
                )
                SheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

/** 文件树的一行：按 [FileTreeNode.depth] 缩进，目录带展开箭头。
 * 所有行取统一的 [rowWidth]（与外层 horizontalScroll 配合），横向滑动时整树一起平移，
 * 名称不截断、不换行。着色优先级：读取失败 > .gitignore 命中（橙）> dotfile（弱化）> 常规。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    expanding: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 展开加载态加最小延迟：本地模式列目录极快，避免每次展开都闪一下转圈。
    var showExpanding by remember { mutableStateOf(false) }
    LaunchedEffect(expanding) {
        if (expanding) {
            delay(180)
            showExpanding = true
        } else {
            showExpanding = false
        }
    }
    val isDotfile = node.root == null && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> MaterialTheme.colorScheme.error
        node.ignored -> FileTreeIgnoredColor
        isDotfile -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> null
    }
    Row(
        modifier = Modifier
            .width(rowWidth)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.xs + 2.dp)
            .padding(start = Spacing.sm, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(Spacing.lg * node.depth))
        if (node.entry.isDirectory) {
            if (showExpanding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = if (node.isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = stringResource(
                        if (node.isExpanded) R.string.file_browser_collapse else R.string.file_browser_expand
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(Spacing.xs))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FileTreeIcon(node = node, decorationColor = decorationColor)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 文件树行的类型图标：目录用文件夹，文件按扩展名挑选单色线性图标。容器根用「容器与镜像」列表同款图标。
 * [decorationColor] 非空时统一染色（.gitignore/dotfile 弱化）。 */
@Composable
internal fun FileTreeIcon(node: FileTreeNode, decorationColor: Color?) {
    if (FileTreeRootIcon(node, decorationColor ?: MaterialTheme.colorScheme.primary)) return
    val vector = if (node.entry.isDirectory) {
        FeatherIcons.Folder
    } else {
        fileTypeIconFor(node.entry.name)
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (node.entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** .gitignore 命中条目的弱化色（橙，类 VSCode）。 */
internal val FileTreeIgnoredColor = Color(0xFFCC8844)

/** 文件树一行除缩进与文本外的固定宽度开销（start 8 + 箭头 16 + 4 + 图标 20 + 8 + end 12），供预算整树统一行宽。 */
internal val FILE_TREE_ROW_OVERHEAD = 68.dp
