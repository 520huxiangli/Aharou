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
import com.aharou.feature.plugin.presentation.PluginDrawerEntries
import com.aharou.feature.plugin.presentation.PluginHostState
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
import compose.icons.feathericons.Lock
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
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    workspaces: List<Workspace> = emptyList(),
    currentWorkspacePath: String = "",
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String> = emptySet(),
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDeleteSessions: ((Set<String>) -> Unit)? = null,
    onRename: (ChatSession, String) -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onExport: (ChatSession) -> Unit,
    subSessionsByParent: Map<String, List<ChatSession>> = emptyMap(),
    browseState: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String? = null,
    fileOpPaths: Set<String> = emptySet(),
    clipboard: BrowseClipboard? = null,
    pasteConflict: Pair<String, String>? = null,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onAddToInput: (String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onPasteOverwrite: () -> Unit,
    onCancelPasteOverwrite: () -> Unit,
    onClearClipboard: () -> Unit,
    onNavigateToBrowser: (() -> Unit)? = null,
    /** 插件入口（`drawer.footer` 槽位）；null 表示不展示。 */
    pluginHost: PluginHostState? = null,
    onNavigateToSettings: () -> Unit,
    searchQuery: String,
    searchState: ChatSearchState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    val fileSearchViewModel: FileSearchViewModel = hiltViewModel()
    val fileSearchQuery by fileSearchViewModel.query.collectAsState()
    val fileSearchState by fileSearchViewModel.state.collectAsState()

    // tab 与展开状态进 saveable：大屏下侧栏收起后整棵子树会离开组合（见 MainActivity 的
    // SaveableStateProvider），用 remember 存会让每次回到聊天页都重置回「会话」页。
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedSessionIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    val listState = rememberLazyListState()

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedSessionIds.clear()
    }

    // 点击会话/重开侧边栏保持原滚动位置；仅当同一会话的最后回复时间变化（发消息/收到回复）时滚回顶部。
    var lastTouched by remember { mutableStateOf<Pair<String?, Long?>?>(null) }
    val currentUpdatedAt = sessions.firstOrNull { it.id == currentSessionId }?.updatedAt
    LaunchedEffect(currentSessionId, currentUpdatedAt) {
        val cur = currentSessionId to currentUpdatedAt
        val prev = lastTouched
        lastTouched = cur
        if (prev != null && prev.first == cur.first && prev.second != cur.second) {
            listState.scrollToItem(0)
        }
    }

    val imeInset = rememberImeBottomInset()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(settingsPageBackground())
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            .padding(horizontal = Spacing.lg)
            .padding(bottom = imeInset + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 顶部 Tab 切换
        SegmentedTabs(
            selected = selectedTab,
            labels = listOf(
                stringResource(R.string.subagent_tab_sessions),
                stringResource(R.string.drawer_tab_files),
                stringResource(R.string.drawer_tab_workflow)
            ),
            onSelect = { selectedTab = it }
        )

        // 聊天记录搜索框放在 Tab 栏正下方，仅「会话」Tab 可见。
        if (selectedTab == 0 && isSelectionMode) {
            Surface(
                color = MaterialTheme.semanticColors.cardSurface,
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            isSelectionMode = false
                            selectedSessionIds.clear()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.common_cancel),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.chat_sessions_selected_count, selectedSessionIds.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    val allSessionIds: List<String> = remember(sessions, subSessionsByParent) {
                        val ids = mutableListOf<String>()
                        sessions.forEach { s ->
                            ids.add(s.id)
                            subSessionsByParent[s.id]?.forEach { sub -> ids.add(sub.id) }
                        }
                        ids
                    }
                    val allSelected = allSessionIds.isNotEmpty() && selectedSessionIds.size == allSessionIds.size
                    TextButton(onClick = {
                        if (allSelected) {
                            selectedSessionIds.clear()
                        } else {
                            selectedSessionIds.clear()
                            selectedSessionIds.addAll(allSessionIds)
                        }
                    }) {
                        Text(stringResource(if (allSelected) R.string.provider_models_deselect_all else R.string.provider_models_select_all))
                    }
                    IconButton(
                        onClick = { showBatchDeleteConfirm = true },
                        enabled = selectedSessionIds.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Trash2,
                            contentDescription = stringResource(R.string.chat_batch_delete_title),
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedSessionIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        } else if (selectedTab == 0) {
            DrawerSearchField(
                query = searchQuery,
                placeholder = stringResource(R.string.chat_search_hint),
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch
            )
        } else if (selectedTab == 1) {
            DrawerSearchField(
                query = fileSearchQuery,
                placeholder = stringResource(R.string.drawer_file_search_hint),
                onQueryChange = fileSearchViewModel::updateQuery,
                onClear = fileSearchViewModel::clear
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SessionListTab(
                    sessions = sessions,
                    workspaces = workspaces,
                    currentWorkspacePath = currentWorkspacePath,
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    awaitingPermissionSessionIds = awaitingPermissionSessionIds,
                    subSessionsByParent = subSessionsByParent,
                    listState = listState,
                    searchQuery = searchQuery,
                    searchState = searchState,
                    selectionMode = isSelectionMode,
                    selectedSessionIds = selectedSessionIds,
                    onToggleSelectSession = { s ->
                        if (s.id in selectedSessionIds) {
                            selectedSessionIds.remove(s.id)
                        } else {
                            selectedSessionIds.add(s.id)
                        }
                    },
                    onSelect = onSelect,
                    onLongClick = { menuSession = it },
                    onOpenSearchHit = onOpenSearchHit
                )
                2 -> WorkflowTab(sessions, subSessionsByParent, agentStates, currentSessionId, onSelect)
                1 -> if (fileSearchQuery.isNotBlank()) {
                    // 命中行点击：先把要定位的行号交给打开动作，再走与文件树同一条打开链路。
                    FileSearchResults(
                        state = fileSearchState,
                        onOpenHit = { hit ->
                            DrawerSearchOpenRequest.request(hit.path, hit.line)
                            onOpenFile(hit.path)
                        }
                    )
                } else FileBrowserTab(
                    state = browseState,
                    expandedPaths = expandedPaths,
                    expandingPath = expandingPath,
                    fileOpPaths = fileOpPaths,
                    clipboard = clipboard,
                    onToggleExpand = onToggleExpand,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshBrowse,
                    onCreateFile = onCreateFile,
                    onCreateFolder = onCreateFolder,
                    onRenameEntry = onRenameEntry,
                    onDeleteEntry = onDeleteEntry,
                    onCopyEntry = onCopyEntry,
                    onCutEntry = onCutEntry,
                    onAddToInput = onAddToInput,
                    onPasteEntry = onPasteEntry,
                    onClearClipboard = onClearClipboard
                )
            }
        }

        PluginDrawerEntries(pluginHost)

        SettingsGroup {
            if (onNavigateToBrowser != null) {
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.browser_title),
                    onClick = onNavigateToBrowser
                )
                SettingsDivider()
            }
            SettingsRow(
                icon = FeatherIcons.Settings,
                title = stringResource(R.string.chat_settings),
                onClick = onNavigateToSettings,
                modifier = Modifier.onboardingTarget(OnboardingStep.ENTER_SETTINGS)
            )
        }
    }

    pasteConflict?.let { (_, targetPath) ->
        AlertDialog(
            onDismissRequest = onCancelPasteOverwrite,
            title = { Text(stringResource(R.string.file_browser_paste_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.file_browser_paste_conflict_message,
                        targetPath.substringAfterLast('/')
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onPasteOverwrite) { Text(stringResource(R.string.common_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelPasteOverwrite) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onTogglePin = {
                menuSession = null
                onTogglePin(session)
            },
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onBatchSelect = {
                menuSession = null
                isSelectionMode = true
                selectedSessionIds.clear()
                selectedSessionIds.add(session.id)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            onDismiss = { menuSession = null }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_batch_delete_title)) },
            text = {
                Text(stringResource(R.string.chat_batch_delete_confirm, selectedSessionIds.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    val toDelete = selectedSessionIds.toSet()
                    selectedSessionIds.clear()
                    isSelectionMode = false
                    if (onDeleteSessions != null) {
                        onDeleteSessions(toDelete)
                    } else {
                        toDelete.forEach { sid: String ->
                            sessions.firstOrNull { it.id == sid }?.let { onDelete(it) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Tab0：会话列表。搜索词非空时列表区切换为跨会话搜索结果。
 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    workspaces: List<Workspace>,
    currentWorkspacePath: String,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    searchQuery: String,
    searchState: ChatSearchState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit
) {
    if (searchQuery.isBlank()) {
        SessionListContent(
            sessions = sessions,
            workspaces = workspaces,
            currentWorkspacePath = currentWorkspacePath,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            listState = listState,
            selectionMode = selectionMode,
            selectedSessionIds = selectedSessionIds,
            onToggleSelectSession = onToggleSelectSession,
            onSelect = onSelect,
            onLongClick = onLongClick
        )
    } else {
        ChatSearchResults(state = searchState, onOpenHit = onOpenSearchHit)
    }
}

/** Tab0 的会话列表本体（按最后回复时间分组）。带子代理的会话行尾有展开箭头，展开后在其下方缩进列出子代理。 */
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    workspaces: List<Workspace>,
    currentWorkspacePath: String,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_no_sessions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        return
    }
    var expandedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    // 文件夹 = 工作区：按「当前所在优先、其次最近活跃」排序，组内是 DAO 的置顶优先 + 更新时间倒序。
    val folders = remember(sessions, workspaces, currentWorkspacePath) {
        val names = workspaces.associate { it.path to it.name }
        sessions.groupBy { it.workspacePath }
            .map { (path, list) ->
                FolderGroup(
                    path = path,
                    name = names[path] ?: path.substringAfterLast('/'),
                    sessions = list,
                    latestAt = list.maxOfOrNull { it.updatedAt } ?: 0L
                )
            }
            .sortedWith(
                compareByDescending<FolderGroup> { it.path == currentWorkspacePath }
                    .thenByDescending { it.latestAt }
            )
    }
    // 只记住「用户主动展开的」文件夹：默认仅当前文件夹展开，切工作区时自动展开新的那个。
    var expandedFolders by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(setOf(currentWorkspacePath)) }
    LaunchedEffect(currentWorkspacePath) {
        if (currentWorkspacePath.isNotBlank()) {
            expandedFolders = expandedFolders + currentWorkspacePath
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // 同上：文件夹头与会话行各自成 item，组内 forEach 会让 LazyColumn 失去惰性。
        folders.forEach { folder ->
            val folderExpanded = folder.path in expandedFolders
            item(key = "folder-${folder.path}", contentType = "folder-header") {
                FolderHeader(
                    name = folder.name,
                    sessionCount = folder.sessions.size,
                    isCurrent = folder.path == currentWorkspacePath,
                    expanded = folderExpanded,
                    onToggle = {
                        expandedFolders = if (folderExpanded) {
                            expandedFolders - folder.path
                        } else {
                            expandedFolders + folder.path
                        }
                    }
                )
            }
            if (!folderExpanded) return@forEach
            itemsIndexed(
                items = folder.sessions,
                key = { _, session -> session.id },
                contentType = { _, _ -> "session" }
            ) { index, session ->
                Column {
                    if (index > 0) SettingsDivider()
                    val state = agentStates[session.id]
                    val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                    val subSessions = subSessionsByParent[session.id].orEmpty()
                    val expanded = session.id in expandedIds
                    ChatSessionRow(
                        session = session,
                        selected = if (selectionMode) false else session.id == currentSessionId,
                        isExecuting = isExecuting,
                        awaitingPermission = session.id in awaitingPermissionSessionIds,
                        pinned = session.isPinned,
                        selectionMode = selectionMode,
                        checked = session.id in selectedSessionIds,
                        onCheckedChange = { onToggleSelectSession(session) },
                        onClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onSelect(session)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onLongClick(session)
                            }
                        },
                        trailing = if (subSessions.isEmpty()) null else {
                            {
                                SubAgentExpandToggle(
                                    expanded = expanded,
                                    count = subSessions.size,
                                    onToggle = {
                                        expandedIds = if (expanded) {
                                            expandedIds - session.id
                                        } else {
                                            expandedIds + session.id
                                        }
                                    }
                                )
                            }
                        }
                    )
                    if (expanded) {
                        subSessions.forEach { sub ->
                            val subState = agentStates[sub.id]
                            Row(modifier = Modifier.padding(start = Spacing.lg)) {
                                ChatSessionRow(
                                    session = sub,
                                    selected = if (selectionMode) false else sub.id == currentSessionId,
                                    isExecuting = subState is AgentUIState.Loading ||
                                        subState is AgentUIState.Streaming,
                                    awaitingPermission = sub.id in awaitingPermissionSessionIds,
                                    pinned = false,
                                    selectionMode = selectionMode,
                                    checked = sub.id in selectedSessionIds,
                                    onCheckedChange = { onToggleSelectSession(sub) },
                                    onClick = {
                                        if (selectionMode) {
                                            onToggleSelectSession(sub)
                                        } else {
                                            onSelect(sub)
                                        }
                                    },
                                    onLongClick = {
                                        if (selectionMode) {
                                            onToggleSelectSession(sub)
                                        } else {
                                            onLongClick(sub)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果区：加载中 / 无结果 / 命中列表三态。 */
@Composable
private fun ChatSearchResults(
    state: ChatSearchState,
    onOpenHit: (ChatSearchHit) -> Unit
) {
    when {
        state.hits.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.hits.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_search_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits, key = { it.messageId }) { hit ->
                    ChatSearchResultRow(
                        hit = hit,
                        query = state.query,
                        onClick = { onOpenHit(hit) }
                    )
                    SettingsDivider()
                }
            }
        }
    }
}

/** 单条搜索结果行：会话标题 + 命中片段（关键词高亮），点击跳转并定位。 */
@Composable
private fun ChatSearchResultRow(
    hit: ChatSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Text(
            text = hit.sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = highlightSnippet(
                snippet = hit.snippet,
                query = query,
                highlight = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 会话行尾的子代理展开开关：显示数量与箭头，自己消费点击，不触发整行选中。 */
@Composable
private fun SubAgentExpandToggle(
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
            contentDescription = stringResource(
                if (expanded) R.string.drawer_collapse_subagents else R.string.drawer_expand_subagents
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}



/**
 * 会话行长按弹出的功能菜单：置顶 / 重命名 / 导出 / 删除。底部 sheet 样式参照 git 分支的 RefActionSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onExport()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.CheckSquare,
                label = stringResource(R.string.chat_batch_select),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onBatchSelect()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
internal fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** 侧边栏会话分组：同一时间组（今天 / 昨天 / 7天内 / 30天内 / 月份）内的会话，按最后回复时间降序。 */
internal data class SessionGroup(
    val groupKey: String,
    val sessions: List<ChatSession>
)

/**
 * 侧边栏里的一个文件夹（= 一个工作区 = 一个仓库）。文件夹内的会话共享同一套工作区与容器，
 * 文件夹之间相互隔离；文件夹名取工作区名，工作区已不存在时退回目录名。
 */
internal data class FolderGroup(
    val path: String,
    val name: String,
    val sessions: List<ChatSession>,
    val latestAt: Long
)

/**
 * 按最后回复时间（updatedAt）降序的会话列表分组：今天 / 昨天 / 7天内 / 30天内 / 更早按月。
 */
internal fun buildSessionGroups(sessions: List<ChatSession>, now: Long): List<SessionGroup> {
    val groups = mutableListOf<SessionGroup>()
    for (session in sessions) {
        val groupKey = sessionGroupKey(session.updatedAt, now)
        val lastIndex = groups.lastIndex
        if (lastIndex >= 0 && groups[lastIndex].groupKey == groupKey) {
            groups[lastIndex] = groups[lastIndex].copy(sessions = groups[lastIndex].sessions + session)
        } else {
            groups += SessionGroup(groupKey, listOf(session))
        }
    }
    return groups
}

/** 返回会话所属分组 key；月份分组为 ISO 年月（如 2026-05），其余为固定字面量。 */
internal fun sessionGroupKey(updatedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days <= 7L -> "7d"
        days <= 30L -> "30d"
        else -> YearMonth.from(day).toString()
    }
}

/**
 * 文件夹行：展开箭头 + 文件夹名 + 会话数。当前所在的文件夹用主色高亮，
 * 与 `ChatSessionRow` 的选中态呼应，让人一眼看出「现在在哪」。
 */
@Composable
private fun FolderHeader(
    name: String,
    sessionCount: Int,
    isCurrent: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val tint = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            imageVector = FeatherIcons.Folder,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = sessionCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
