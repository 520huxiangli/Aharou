package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppSwitch

/**
 * 隐身对话（Ghost mode）的可见标识与开关。
 *
 * 三个入口按位置任选：[GhostModeBanner] 常驻聊天页顶部提示当前处于隐身；
 * [GhostModeIndicator] 是紧凑胶囊标识（可点击切换），放工具条/标题栏；
 * [GhostModeToggleRow] 是设置风格的整行开关（标题 + 说明 + [AppSwitch]）。
 *
 * 本文件只负责展示与回调，不持有任何状态——开关值由调用方（当前会话的 ghostMode）传入。
 */

/** 顶部横幅：仅当隐身开启时渲染，明确提示本会话不写历史、不写记忆。 */
@Composable
fun GhostModeBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.semanticColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(colors.warningContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = colors.onWarningContainer,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ghost_mode_banner_title),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onWarningContainer
            )
            Text(
                text = stringResource(R.string.ghost_mode_banner_message),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onWarningContainer
            )
        }
    }
}

/** 紧凑胶囊标识：开着时高亮，点一下即切换。 */
@Composable
fun GhostModeIndicator(
    isGhost: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.semanticColors
    val container = if (isGhost) colors.warningContainer else colors.capsuleSurface
    val content = if (isGhost) colors.onWarningContainer else colors.subtleText
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(container)
            .clickable(enabled = enabled) { onToggle(!isGhost) }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = stringResource(R.string.ghost_mode_label),
            tint = content,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(if (isGhost) R.string.ghost_mode_state_on else R.string.ghost_mode_state_off),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}

/** 设置风格整行开关：左侧标题与说明，右侧 [AppSwitch]。 */
@Composable
fun GhostModeToggleRow(
    isGhost: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ghost_mode_label),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.ghost_mode_banner_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        AppSwitch(checked = isGhost, onCheckedChange = { onToggle(it) }, enabled = enabled)
    }
}
