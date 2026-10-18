package com.aharou.feature.settings.presentation.component.extension

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.extension.domain.ExtensionState
import com.aharou.feature.plugin.domain.market.PluginMarketEntry
import com.aharou.feature.plugin.presentation.PluginEvent

/** 类型标签（标题右侧的小徽标）用的字符串资源。 */
@StringRes
internal fun extensionKindLabelRes(kind: ExtensionKind): Int = when (kind) {
    ExtensionKind.PLUGIN -> R.string.extension_kind_plugin
    ExtensionKind.SKILL -> R.string.extension_kind_skill
    ExtensionKind.MCP -> R.string.extension_kind_mcp
    ExtensionKind.KNOWLEDGE -> R.string.extension_kind_knowledge
    ExtensionKind.CONNECTOR -> R.string.extension_kind_connector
}

/** 状态标签的字符串资源。 */
@StringRes
internal fun extensionStateLabelRes(state: ExtensionState): Int = when (state) {
    ExtensionState.AVAILABLE -> R.string.extension_state_available
    ExtensionState.INSTALLED -> R.string.extension_state_installed
    ExtensionState.UPDATE_AVAILABLE -> R.string.extension_state_update
    ExtensionState.RUNNING -> R.string.extension_state_running
    ExtensionState.FAILED -> R.string.extension_state_failed
}

internal fun extensionStateColor(state: ExtensionState, primary: Color, error: Color, subtle: Color): Color =
    when (state) {
        ExtensionState.RUNNING -> primary
        ExtensionState.UPDATE_AVAILABLE -> primary
        ExtensionState.FAILED -> error
        ExtensionState.AVAILABLE,
        ExtensionState.INSTALLED -> subtle
    }

/**
 * 一条扩展在列表里的行：名称 + 类型徽标 + 状态、版本/作者/来源、描述与类型专属说明，底部是操作区。
 *
 * 「已安装 / 可用」在这五类里含义不同，所以 [entry] 的说明文字按类型分别给，别让用户以为
 * 连接器也需要「安装」。
 */
@Composable
internal fun ExtensionEntryRow(
    entry: ExtensionEntry,
    busy: Boolean,
    anyBusy: Boolean,
    onAction: () -> Unit,
    onUninstall: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    val meta = remember(entry) {
        listOfNotNull(
            entry.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            entry.author.takeIf { it.isNotBlank() },
            entry.sourceLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    // 只显示本地化的类型说明：各类自己那条 detail 是原始值（GLOBAL / oauth / 分支名），
    // 直接洒到界面上是中文页面里冒英文，而这些信息在原页里都有
    val detail = extensionSubtitle(entry)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.sm))
            KindBadge(entry.kind)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(extensionStateLabelRes(entry.state)),
                style = MaterialTheme.typography.labelSmall,
                color = extensionStateColor(
                    entry.state,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.semanticColors.subtleText
                )
            )
        }

        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                Text(
                    text = stringResource(R.string.extension_busy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            } else if (entry.kind == ExtensionKind.PLUGIN) {
                TextButton(onClick = onAction, enabled = !anyBusy) {
                    Text(
                        stringResource(
                            if (entry.state == ExtensionState.RUNNING) R.string.extension_action_stop
                            else R.string.extension_action_start
                        )
                    )
                }
            } else {
                TextButton(onClick = onOpenOriginal) {
                    Text(stringResource(R.string.extension_action_open))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (entry.kind == ExtensionKind.PLUGIN) {
                TextButton(onClick = onUninstall, enabled = !anyBusy) {
                    Text(stringResource(R.string.extension_action_uninstall))
                }
            }
        }
    }
}

/** 类型徽标：一眼看出这条属哪一类，避免同名条目分不清。 */
@Composable
internal fun KindBadge(kind: ExtensionKind) {
    Text(
        text = stringResource(extensionKindLabelRes(kind)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 类型专属说明：把这四类「已装 / 可用」的不同含义说清楚。 */
@Composable
internal fun extensionSubtitle(entry: ExtensionEntry): String = when (entry.kind) {
    ExtensionKind.KNOWLEDGE -> stringResource(
        if (entry.state == ExtensionState.AVAILABLE) R.string.extension_subtitle_knowledge_factory
        else R.string.extension_subtitle_knowledge_custom
    )
    ExtensionKind.CONNECTOR -> stringResource(R.string.extension_subtitle_connector)
    ExtensionKind.SKILL -> stringResource(R.string.extension_subtitle_skill)
    ExtensionKind.PLUGIN -> stringResource(R.string.extension_subtitle_plugin)
    ExtensionKind.MCP -> stringResource(R.string.extension_subtitle_mcp)
}

/** 市场里的一条插件：名称 + 版本/作者/描述 + 安装（或已安装 / 更新）按钮。 */
@Composable
internal fun MarketPluginRow(
    entry: PluginMarketEntry,
    installedVersion: String?,
    busy: Boolean,
    anyBusy: Boolean,
    onInstall: () -> Unit,
) {
    val hasUpdate = installedVersion != null && entry.version.isNotBlank() && entry.version != installedVersion
    val meta = remember(entry) {
        listOfNotNull(
            entry.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            entry.author.takeIf { it.isNotBlank() },
            entry.repo.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = entry.name.ifBlank { entry.id },
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                Text(
                    text = stringResource(R.string.extension_busy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            when {
                installedVersion != null && !hasUpdate -> Text(
                    text = stringResource(R.string.extension_state_installed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
                else -> TextButton(onClick = onInstall, enabled = !anyBusy) {
                    Text(
                        stringResource(
                            if (hasUpdate) R.string.extension_action_update
                            else R.string.extension_action_install
                        )
                    )
                }
            }
        }
    }
}

/** 最近一次操作的提示行。 */
@Composable
internal fun ExtensionNotice(event: PluginEvent?) {
    if (event == null) return
    val isError = event is PluginEvent.Failed
    val text = when (event) {
        is PluginEvent.Installed -> stringResource(R.string.extension_event_installed, event.name, event.version)
        PluginEvent.Completed -> stringResource(R.string.extension_event_completed)
        is PluginEvent.Failed -> stringResource(R.string.extension_event_failed, event.reason)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}