package com.aharou.feature.settings.presentation.component

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.BatchDeleteConfirmDialog
import com.aharou.core.ui.SelectionAction
import com.aharou.core.ui.SelectionActionBar
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.ui.rememberListSelectionState
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.mcp.McpOAuthStatus
import com.aharou.feature.agent.domain.mcp.oauthStatusOf
import dagger.hilt.android.EntryPointAccessors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Download
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import com.aharou.R

/**
 * MCP 二级页：与提供商/默认模型一致的 iOS 分组列表。
 * 顶部是「浏览连接器」入口；白色分组卡片内每台 server 一行，两行布局（名称+状态 / 类型+摘要），支持左滑删除。
 */
@Composable
internal fun McpSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    reloading: Boolean,
    onReload: () -> Unit,
    onToggle: (String, Boolean, McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    onDelete: (String, McpScope) -> Unit,
    onBrowseDirectory: () -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit
) {
    val context = LocalContext.current
    val oauthController = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, McpOAuthEntryPoint::class.java)
            .mcpOAuthController()
    }
    val settingsViewModel = rememberSettingsViewModel()
    val selection = rememberListSelectionState()
    // 选中 id 带作用域前缀：同名 server 可能全局 / 项目各一份，只用名字会互相顶掉
    val selectionIds = entries.map(::mcpSelectionId)
    // 增删 server 或列表被外部改动后丢掉已不存在的选中，否则「已选 N 项」与可见行数对不上
    LaunchedEffect(selectionIds) { selection.retain(selectionIds) }
    var showBatchDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 长按的可能是列表深处的行，进多选后把操作栏所在的首项滚回视野，否则看不到「已选 N 项」与批量动作
    LaunchedEffect(selection.selectionMode) {
        if (selection.selectionMode) listState.animateScrollToItem(0)
    }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl)
    ) {
        // 多选态下顶部固定入口整组换成批量操作栏，退出多选即恢复
        if (selection.selectionMode) {
            item(key = "mcp_selection_bar", contentType = "mcp_selection_bar") {
                SelectionActionBar(
                    selectedCount = selection.selectedCount,
                    allSelected = selection.isAllSelected(selectionIds),
                    onToggleSelectAll = { selection.toggleSelectAll(selectionIds) },
                    onExit = { selection.exit() },
                    modifier = Modifier.padding(bottom = Spacing.sm),
                    actions = listOf(
                        SelectionAction(
                            icon = FeatherIcons.Trash2,
                            label = stringResource(R.string.common_delete),
                            onClick = { showBatchDelete = true },
                            destructive = true
                        )
                    )
                )
            }
        } else {
        // 顶部三个固定入口自成一卡：count = 1 时 LazyGroupItem 四角全圆，与 SettingsGroup 画出的卡片一致
        item(key = "mcp_actions", contentType = "mcp_actions") {
            LazyGroupItem(
                index = 0,
                count = 1,
                modifier = Modifier.padding(bottom = Spacing.sm)
            ) {
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.mcp_browse_directory),
                    subtitle = stringResource(R.string.mcp_browse_directory_hint),
                    onClick = onBrowseDirectory
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Download,
                    title = stringResource(R.string.mcp_import_json),
                    subtitle = stringResource(R.string.mcp_import_json_hint),
                    onClick = onImportJson
                )
                SettingsDivider()
                // 导出：把当前生效配置吐成标准 mcpServers JSON，方便分享给别人或备份
                SettingsRow(
                    icon = FeatherIcons.Upload,
                    title = stringResource(R.string.mcp_export_json),
                    subtitle = stringResource(R.string.mcp_export_json_hint),
                    onClick = onExportJson
                )
            }
        }
        }
        // 长按也能进多选，但不是可发现的入口（触屏/键盘都无从得知）；再给一个显式按钮，与其它列表一致
        if (!selection.selectionMode && entries.isNotEmpty()) {
            item(key = "mcp_batch_entry", contentType = "batch_entry") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { selection.enterSelectionMode() }) {
                        Icon(
                            imageVector = FeatherIcons.CheckSquare,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.provider_models_select))
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item(key = "mcp_empty", contentType = "mcp_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                FeatherIcons.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.mcp_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.mcp_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // 每台 server 一行、各自带 cardSurface 底色，首末行的圆角拼出整块卡片
            itemsIndexed(
                items = entries,
                key = { _, entry -> "mcp_server:${entry.scope.name}:${entry.server.name}" },
                contentType = { _, _ -> "mcp_server" }
            ) { index, entry ->
                LazyGroupItem(
                    index = index,
                    count = entries.size,
                    modifier = Modifier.padding(bottom = if (index == entries.lastIndex) Spacing.sm else 0.dp)
                ) {
                    if (index > 0) {
                        SettingsDivider()
                    }
                    McpServerRow(
                        server = entry.server,
                        scope = entry.scope,
                        status = statuses.firstOrNull { it.name == entry.server.name },
                        onClick = { onEdit(entry) },
                        onDelete = { onDelete(entry.server.name, entry.scope) },
                        onAuthorize = { oauthController.open(entry) },
                        selectionMode = selection.selectionMode,
                        selected = selection.isSelected(selectionIds[index]),
                        onToggleSelect = { selection.toggle(selectionIds[index]) },
                        // 长按进多选并当场选中这一行，与其余二级页一致
                        onLongClick = {
                            selection.enterSelectionMode()
                            selection.select(selectionIds[index])
                        }
                    )
                }
            }
        }
    }
    if (showBatchDelete) {
        val selectedEntries = entries.filter { mcpSelectionId(it) in selection.selectedIds }
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDelete = false
                selection.exit()
                // 配置按作用域整份读改写，跨作用域的选中必须分组分别提交，否则后写的会盖掉前面删的
                selectedEntries.groupBy { it.scope }.forEach { (scope, group) ->
                    settingsViewModel.deleteMcpServers(group.map { it.server.name }, scope)
                }
            },
            onDismiss = { showBatchDelete = false }
        )
    }
    McpOAuthHost(oauthController)
    }
}

/** 选中 id：「作用域:名称」——同名 server 可能全局与项目各一份。 */
private fun mcpSelectionId(entry: McpServerEntry): String = "${entry.scope.name}:${entry.server.name}"

/**
 * 取 `SettingsScreen` 用的那个 [SettingsViewModel]（MainActivity 顶部创建的 Activity 级实例）。
 *
 * 本目录各区块都在 `composable("settings")` 里，直接 `hiltViewModel()` 会按 NavBackStackEntry 再建一个
 * 实例：不仅重复跑 init 与多路 flow 订阅，批量改完那份实例的列表状态也不会回流到设置页。
 * 故沿 Context 找到宿主 Activity，从它的 ViewModelStore 取同一个实例。
 */
@Composable
internal fun rememberSettingsViewModel(): SettingsViewModel {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    return if (activity != null) hiltViewModel(viewModelStoreOwner = activity) else hiltViewModel()
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * 单个 MCP server 行：分组内白底行，图标 + 名称/状态 + 类型/摘要 + 右箭头，左滑删除。
 */
@Composable
internal fun McpServerRow(
    server: McpServerConfig,
    scope: McpScope,
    status: McpServerStatus?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onAuthorize: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val isConnected = server.enabled && status?.state == McpServerStatus.State.CONNECTED
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val statusText = when {
        !server.enabled -> stringResource(R.string.mcp_disabled)
        status == null -> stringResource(R.string.mcp_not_connected)
        else -> when (status.state) {
            McpServerStatus.State.CONNECTED -> stringResource(R.string.mcp_connected)
            McpServerStatus.State.CONNECTING -> stringResource(R.string.mcp_connecting)
            McpServerStatus.State.FAILED -> stringResource(R.string.mcp_connection_failed)
            McpServerStatus.State.DISABLED -> stringResource(R.string.mcp_disabled)
        }
    }

    val statusColor = when {
        !server.enabled || status == null || status.state == McpServerStatus.State.DISABLED ->
            MaterialTheme.colorScheme.outline
        status.state == McpServerStatus.State.CONNECTED ->
            MaterialTheme.colorScheme.tertiary
        status.state == McpServerStatus.State.CONNECTING ->
            MaterialTheme.colorScheme.primary
        else ->
            MaterialTheme.colorScheme.error
    }

    val statusBgColor = statusColor.copy(alpha = 0.12f)

    val typeText = if (server.isStdio) stringResource(R.string.mcp_type_stdio) else "HTTP"
    // HTTP 未连接时不再展示完整地址：URL 很长，在列表里只能看到一截断的头，占位而无信息量。
    val infoText = when {
        isConnected -> stringResource(R.string.mcp_tools_count, status?.toolCount ?: 0)
        server.isStdio -> server.command.orEmpty().ifEmpty { "stdio" }
        else -> null
    }

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        onLongClick = onLongClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧容器图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = FeatherIcons.Box,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 类型 + 摘要
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McpPill(
                        text = if (scope == McpScope.PROJECT) stringResource(R.string.mcp_scope_project) else stringResource(R.string.mcp_scope_global),
                        textColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    McpPill(
                        text = typeText,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    // OAuth server：状态胶囊可点，直接打开授权面板（未授权 / 已授权 / 已过期）。
                    if (server.oauth != null) {
                        val oauthStatus = oauthStatusOf(server.oauth)
                        val oauthColor = when (oauthStatus) {
                            McpOAuthStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
                            McpOAuthStatus.EXPIRED -> MaterialTheme.colorScheme.error
                            McpOAuthStatus.NOT_AUTHORIZED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        McpPill(
                            text = stringResource(R.string.mcp_oauth_badge) + " " +
                                stringResource(oauthPillStatusRes(oauthStatus)),
                            textColor = oauthColor,
                            backgroundColor = oauthColor.copy(alpha = 0.12f),
                            // 多选态下胶囊不再接手点击：否则点胶囊会去授权而不是切换选中
                            modifier = if (selectionMode) Modifier else Modifier.clickable { onAuthorize() }
                        )
                    }
                    if (infoText != null) {
                        McpPill(
                            text = infoText,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 状态 pill 与右箭头垂直居中，与整行中心对齐
            McpPill(
                text = statusText,
                textColor = statusColor,
                backgroundColor = statusBgColor
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.mcp_details),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** OAuth 状态胶囊文案（与授权面板共用同一组状态字符串）。 */
private fun oauthPillStatusRes(status: McpOAuthStatus): Int = when (status) {
    McpOAuthStatus.NOT_AUTHORIZED -> R.string.mcp_oauth_status_not_authorized
    McpOAuthStatus.AUTHORIZED -> R.string.mcp_oauth_status_authorized
    McpOAuthStatus.EXPIRED -> R.string.mcp_oauth_status_expired
}

/** 紧凑 pill 标签：胶囊背景 + 小字，用于状态/类型/摘要。 */
@Composable
internal fun McpPill(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
