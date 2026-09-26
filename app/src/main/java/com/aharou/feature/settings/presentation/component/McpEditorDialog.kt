package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpToolDescriptor
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.FileText
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Tool
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.res.stringResource
import com.aharou.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpServerEditDialog(
    initial: McpServerConfig?,
    initialScope: McpScope? = null,
    tools: List<McpToolDescriptor> = emptyList(),
    onRefreshTools: () -> Unit = {},
    onOpenLogs: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig, McpScope) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 基础设置, 1: 工具

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var isStdio by remember { mutableStateOf(initial?.isStdio ?: false) }
    // 作用域：新增默认当前项目，编辑保持原作用域。
    var scope by remember { mutableStateOf(initialScope ?: McpScope.PROJECT) }

    // HTTP 形态字段
    var url by remember { mutableStateOf(initial?.url ?: "") }
    val headers = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.headers?.toList() ?: emptyList())
        }
    }

    // stdio 形态字段
    var command by remember { mutableStateOf(initial?.command ?: "") }
    val argsList = remember {
        mutableStateListOf<String>().apply { addAll(initial?.args ?: emptyList()) }
    }
    val envList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.env?.toList() ?: emptyList())
        }
    }

    // 工具权限字段 (disabledTools)
    val disabledToolsSet = remember {
        mutableStateListOf<String>().apply { addAll(initial?.disabledTools ?: emptyList()) }
    }

    // 名称会拼进 `mcp__<名称>__<工具名>` 送给模型，必须符合 function-calling 命名规范。
    val nameError = name.isNotBlank() && !McpServerConfig.isValidName(name)

    val canSave = name.isNotBlank() && !nameError && if (isStdio) command.isNotBlank() else url.isNotBlank()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
                .imePadding()
        ) {
                // ── Top Bar：标题绝对居中，右侧按钮浮动，避免按钮数量不同导致标题偏移 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (initial == null) stringResource(R.string.mcp_add) else stringResource(R.string.mcp_edit),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onOpenLogs != null) {
                            IconButton(
                                onClick = onOpenLogs,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    FeatherIcons.FileText,
                                    contentDescription = stringResource(R.string.mcp_view_logs),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(
                            onClick = onRefreshTools,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.mcp_refresh_tools),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // ── Tab Segmented Control（与侧边栏共用同一份滑块分段控件）──
                SegmentedTabs(
                    selected = selectedTab,
                    labels = listOf(
                        stringResource(R.string.mcp_basic_settings),
                        stringResource(R.string.common_tool)
                    ),
                    onSelect = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ── Tab Content Area ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(flingFix)
                ) {
                    if (selectedTab == 0) {
                        // Tab 0: 基础设置
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 作用域选择（全局 / 当前项目）
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.mcp_scope_label),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val scopes = listOf(McpScope.GLOBAL, McpScope.PROJECT)
                                SegmentedTabs(
                                    selected = scopes.indexOf(scope).coerceAtLeast(0),
                                    labels = listOf(
                                        stringResource(R.string.mcp_scope_global),
                                        stringResource(R.string.mcp_scope_project)
                                    ),
                                    onSelect = { scope = scopes[it] }
                                )
                            }

                            // 是否启用 Card
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.mcp_enabled),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AppSwitch(
                                        checked = enabled,
                                        onCheckedChange = { enabled = it }
                                    )
                                }
                            }

                            // 名称字段
                            AppTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = stringResource(R.string.common_name),
                                placeholder = stringResource(R.string.mcp_name_hint),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                isError = nameError,
                                supportingText = if (nameError) {
                                    { Text(stringResource(R.string.mcp_name_invalid)) }
                                } else {
                                    null
                                }
                            )

                            // 传输类型选择
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.mcp_transport_type),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SegmentedTabs(
                                    selected = if (isStdio) 1 else 0,
                                    labels = listOf(
                                        stringResource(R.string.mcp_remote_http),
                                        stringResource(R.string.mcp_local_stdio)
                                    ),
                                    onSelect = { isStdio = it == 1 }
                                )
                            }

                            // 具体表单字段
                            if (isStdio) {
                                McpStdioFields(
                                    command = command,
                                    onCommandChange = { command = it },
                                    args = argsList,
                                    env = envList
                                )
                            } else {
                                McpHttpFields(
                                    url = url,
                                    onUrlChange = { url = it },
                                    headers = headers
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        // Tab 1: 工具
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (tools.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            FeatherIcons.Tool,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = if (initial == null) stringResource(R.string.mcp_save_first_hint) else stringResource(R.string.mcp_no_tools_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                tools.forEach { tool ->
                                    val isToolEnabled = tool.name !in disabledToolsSet
                                    var descriptionExpanded by remember(tool.name) { mutableStateOf(false) }

                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = tool.name,
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isToolEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                AppSwitch(
                                                    checked = isToolEnabled,
                                                    onCheckedChange = { checked ->
                                                        if (checked) disabledToolsSet.remove(tool.name) else disabledToolsSet.add(tool.name)
                                                    }
                                                )
                                            }

                                            Text(
                                                text = tool.description ?: stringResource(R.string.mcp_no_description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stringResource(if (descriptionExpanded) R.string.common_collapse else R.string.common_expand),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable { descriptionExpanded = !descriptionExpanded }
                                                    .padding(top = 2.dp)
                                            )

                                            val paramKeys = remember(tool.inputSchema) {
                                                (tool.inputSchema?.get("properties") as? JsonObject)?.keys ?: emptySet()
                                            }
                                            if (paramKeys.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    paramKeys.forEach { key ->
                                                        Box(
                                                            modifier = Modifier
                                                                .background(
                                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                                                    RoundedCornerShape(6.dp)
                                                                )
                                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = key,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // ── 底部保存按钮（删除在列表左滑）──
                // 底部留白向容器编辑弹窗对齐：按钮不贴屏底，下方给一段间距再加导航栏高度。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = canSave,
                        onClick = {
                            val config = if (isStdio) {
                                McpServerConfig(
                                    name = name.trim(),
                                    command = command.trim(),
                                    args = argsList.map { it.trim() }.filter { it.isNotEmpty() },
                                    env = envList
                                        .map { it.first.trim() to it.second }
                                        .filter { it.first.isNotEmpty() }
                                        .toMap(),
                                    enabled = enabled,
                                    disabledTools = disabledToolsSet.toSet()
                                )
                            } else {
                                McpServerConfig(
                                    name = name.trim(),
                                    url = url.trim(),
                                    headers = headers
                                        .map { it.first.trim() to it.second }
                                        .filter { it.first.isNotEmpty() }
                                        .toMap(),
                                    enabled = enabled,
                                    disabledTools = disabledToolsSet.toSet()
                                )
                            }
                            onSave(config, scope)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.common_save), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
        }
    }
}
