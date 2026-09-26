package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Terminal
import com.aharou.R

/**
 * MCP 二级页：与提供商/默认模型一致的 iOS 分组列表。
 * 白色分组卡片内每台 server 一行，两行布局（名称+状态 / 类型+摘要），支持左滑删除。
 */
@Composable
internal fun McpSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    reloading: Boolean,
    onReload: () -> Unit,
    onToggle: (String, Boolean, McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    onDelete: (String, McpScope) -> Unit
) {
    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    SettingsDivider()
                }
                McpServerRow(
                    server = entry.server,
                    scope = entry.scope,
                    status = statuses.firstOrNull { it.name == entry.server.name },
                    onClick = { onEdit(entry) },
                    onDelete = { onDelete(entry.server.name, entry.scope) }
                )
            }
        }
    }
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
    onDelete: () -> Unit
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
        onClick = onClick
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