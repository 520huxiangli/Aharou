package com.aharou.feature.git.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.git.data.PrErrorKind
import com.aharou.feature.git.domain.pr.CiStatus
import com.aharou.feature.git.domain.pr.PrState
import com.aharou.feature.git.presentation.PullRequestViewModel.PullRequestUiState
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Clock
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Github
import compose.icons.feathericons.Info
import compose.icons.feathericons.Key
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Zap

/** 卡片里最多列出的失败检查项名称数量，避免长列表撑高卡片。 */
private const val MAX_SHOWN_FAILED_CHECKS = 3

/**
 * Git 页「CI / Pull Request」分组卡片。
 *
 * 依 [state] 分四种呈现：加载中 / 出错（含引导填 token）/ 有 PR（含 CI 结论与失败时的
 * 「让 AI 分析」入口）/ 无关联 PR 的说明。远端非 GitHub 时调用方不渲染本卡片（整块隐藏）。
 */
@Composable
fun PullRequestCard(
    state: PullRequestUiState,
    onRefresh: () -> Unit,
    onOpenTokenDialog: () -> Unit,
    onSaveToken: (String) -> Unit,
    onDismissTokenDialog: () -> Unit,
    showAnalyze: Boolean,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PrCardHeader(refreshing = state.loading, onRefresh = onRefresh)
        SettingsGroup {
            when {
                state.loading -> PrLoadingRow()
                state.error != null -> PrErrorRows(state = state, onOpenTokenDialog = onOpenTokenDialog)
                state.pullRequest != null -> PrContent(state = state, showAnalyze = showAnalyze, onAnalyze = onAnalyze)
                else -> PrRow(
                    icon = FeatherIcons.Info,
                    iconTint = MaterialTheme.semanticColors.subtleText,
                    title = stringResource(R.string.git_pr_no_pr)
                )
            }
        }
    }

    if (state.tokenDialogVisible) {
        GitHubTokenDialog(onSave = onSaveToken, onDismiss = onDismissTokenDialog)
    }
}

/** 分组标题 + 右侧刷新按钮（SettingsGroupHeader 无 trailing，这里自带一个小圆钮）。 */
@Composable
private fun PrCardHeader(refreshing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.git_pr_group_title),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(
                imageVector = FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.git_refresh),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PrLoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = stringResource(R.string.git_pr_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 出错行：人话提示；无 token 且被拒/限流时再给一行「设置 GitHub 令牌」。 */
@Composable
private fun PrErrorRows(state: PullRequestUiState, onOpenTokenDialog: () -> Unit) {
    PrRow(
        icon = FeatherIcons.AlertCircle,
        iconTint = MaterialTheme.colorScheme.error,
        title = prErrorText(state),
        titleColor = MaterialTheme.colorScheme.error
    )
    if (state.needsToken) {
        SettingsDivider()
        PrRow(
            icon = FeatherIcons.Key,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.git_pr_token_button),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = onOpenTokenDialog
        )
    }
}

@Composable
private fun PrContent(state: PullRequestUiState, showAnalyze: Boolean, onAnalyze: () -> Unit) {
    val pr = state.pullRequest ?: return
    val context = LocalContext.current
    PrRow(
        icon = FeatherIcons.Github,
        iconTint = MaterialTheme.colorScheme.onSurface,
        title = stringResource(R.string.git_pr_pr_title, pr.number, pr.title),
        subtitle = prStateLabel(pr.state),
        trailing = {
            if (pr.url.isNotBlank()) {
                Icon(
                    imageVector = FeatherIcons.ExternalLink,
                    contentDescription = null,
                    tint = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        onClick = {
            if (pr.url.isNotBlank()) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.url))) }
            }
        }
    )
    SettingsDivider()
    PrRow(
        icon = ciStatusIcon(pr.checks.status),
        iconTint = ciStatusColor(pr.checks.status),
        title = ciStatusLabel(pr.checks.status),
        subtitle = pr.checks.failed.take(MAX_SHOWN_FAILED_CHECKS)
            .joinToString(separator = "\n") { "· ${it.name}" }
            .ifBlank { null },
        subtitleColor = MaterialTheme.colorScheme.error
    )
    if (pr.checks.status == CiStatus.FAILURE && showAnalyze) {
        SettingsDivider()
        PrRow(
            icon = FeatherIcons.Zap,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.git_pr_analyze),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = onAnalyze
        )
    }
}

@Composable
private fun PrRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ciStatusIcon(status: CiStatus): ImageVector = when (status) {
    CiStatus.SUCCESS -> FeatherIcons.CheckCircle
    CiStatus.FAILURE -> FeatherIcons.AlertCircle
    CiStatus.PENDING -> FeatherIcons.Clock
    CiStatus.NEUTRAL, CiStatus.NONE -> FeatherIcons.Info
}

@Composable
private fun ciStatusColor(status: CiStatus): Color = when (status) {
    CiStatus.SUCCESS -> MaterialTheme.semanticColors.success
    CiStatus.FAILURE -> MaterialTheme.colorScheme.error
    CiStatus.PENDING -> MaterialTheme.semanticColors.warning
    CiStatus.NEUTRAL, CiStatus.NONE -> MaterialTheme.semanticColors.subtleText
}

@Composable
private fun ciStatusLabel(status: CiStatus): String = stringResource(
    when (status) {
        CiStatus.SUCCESS -> R.string.git_pr_ci_success
        CiStatus.FAILURE -> R.string.git_pr_ci_failure
        CiStatus.PENDING -> R.string.git_pr_ci_pending
        CiStatus.NEUTRAL -> R.string.git_pr_ci_neutral
        CiStatus.NONE -> R.string.git_pr_ci_none
    }
)

@Composable
private fun prStateLabel(state: PrState): String = stringResource(
    when (state) {
        PrState.OPEN -> R.string.git_pr_state_open
        PrState.DRAFT -> R.string.git_pr_state_draft
        PrState.MERGED -> R.string.git_pr_state_merged
        PrState.CLOSED -> R.string.git_pr_state_closed
    }
)

/** 错误类别 → 人话文案；限流若带 reset 时间则提示还有几分钟可重试。 */
@Composable
private fun prErrorText(state: PullRequestUiState): String = when (state.error) {
    PrErrorKind.RATE_LIMITED -> {
        val minutes = state.rateLimitResetEpochSec
            ?.let { ((it - System.currentTimeMillis() / 1000).coerceAtLeast(0) + 59) / 60 }
            ?.toInt() ?: 0
        if (minutes > 0) stringResource(R.string.git_pr_error_rate_limited_reset, minutes)
        else stringResource(R.string.git_pr_error_rate_limited)
    }

    PrErrorKind.UNAUTHORIZED -> stringResource(R.string.git_pr_error_unauthorized)
    PrErrorKind.FORBIDDEN -> stringResource(R.string.git_pr_error_forbidden)
    PrErrorKind.NOT_FOUND -> stringResource(R.string.git_pr_error_not_found)
    PrErrorKind.SERVER -> stringResource(R.string.git_pr_error_server)
    PrErrorKind.NETWORK -> stringResource(R.string.git_pr_error_network)
    else -> stringResource(R.string.git_pr_error_unknown)
}

/** 单填一个 GitHub token 的小弹窗；令牌由调用方加密存本地，不进 git 凭据文件。 */
@Composable
private fun GitHubTokenDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.git_pr_token_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.git_pr_token_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = stringResource(R.string.git_pr_token_dialog_label),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = value.isNotBlank()
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
