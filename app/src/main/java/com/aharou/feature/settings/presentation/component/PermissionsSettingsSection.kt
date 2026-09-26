package com.aharou.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.permission.PermissionDecision
import com.aharou.feature.agent.domain.permission.PermissionRule
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.delay

/**
 * 「工具授权」二级页：与设置页其它二级页一致的 iOS 分组列表。
 * 顶部是「安全防护」开关；其下按「当前项目 / 全局」分组列出已保存的授权规则，分组可折叠收起；
 * 规则左滑删除；项目规则可「提升为全局」。
 */
@Composable
internal fun PermissionsSection(
    projectName: String?,
    projectRules: List<PermissionRule>,
    globalRules: List<PermissionRule>,
    disableSafetyInterception: Boolean,
    onToggleSafetyInterception: (Boolean) -> Unit,
    onDeleteProject: (PermissionRule) -> Unit,
    onPromote: (PermissionRule) -> Unit,
    onDeleteGlobal: (PermissionRule) -> Unit
) {
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    // 开启「禁用安全拦截」前先弹强制确认框（确认按钮 5 秒倒计时）；关闭无需确认。
    var showSafetyConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.perm_safety_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.perm_disable_safety_interception),
                subtitle = stringResource(R.string.perm_disable_safety_interception_desc),
                trailing = {
                    AppSwitch(
                        checked = disableSafetyInterception,
                        onCheckedChange = { enabled ->
                            if (enabled) showSafetyConfirm = true
                            else onToggleSafetyInterception(false)
                        }
                    )
                }
            )
        }

        CollapsibleGroupHeader(
            text = if (projectName != null) {
                stringResource(R.string.perm_current_project, projectName)
            } else {
                stringResource(R.string.perm_current_project_none)
            },
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded }
        )
        AnimatedVisibility(visible = projectExpanded) {
            SettingsGroup {
                if (projectRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_project_rules))
                } else {
                    projectRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteProject(rule) },
                            onPromote = { onPromote(rule) }
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_global),
            expanded = globalExpanded,
            onToggle = { globalExpanded = !globalExpanded }
        )
        AnimatedVisibility(visible = globalExpanded) {
            SettingsGroup {
                if (globalRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_global_rules))
                } else {
                    globalRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteGlobal(rule) },
                            onPromote = null
                        )
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.perm_rules_short))
    }

    if (showSafetyConfirm) {
        SafetyConfirmDialog(
            onConfirm = {
                onToggleSafetyInterception(true)
                showSafetyConfirm = false
            },
            onDismiss = { showSafetyConfirm = false }
        )
    }
}

/** 「禁用安全拦截」确认框的倒计时秒数。 */
private const val SAFETY_CONFIRM_COUNTDOWN_SECONDS = 5

/** 开启「禁用安全拦截」前的强制确认框：确认按钮带 5 秒倒计时，倒计时结束前不可点。 */
@Composable
private fun SafetyConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(SAFETY_CONFIRM_COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perm_safety_confirm_title)) },
        text = { Text(stringResource(R.string.perm_safety_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = secondsLeft == 0
            ) {
                Text(
                    if (secondsLeft > 0) {
                        stringResource(R.string.perm_safety_confirm_action, secondsLeft)
                    } else {
                        stringResource(R.string.perm_safety_confirm_action_ready)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 单条规则行：紧凑布局（同设置页行），判定图标（允许对勾 / 禁止叉）+ 工具名（主）+ 匹配范围/判定（次），
 * 右侧「提升为全局」图标 (仅项目规则)；左滑露出删除按钮。
 */
@Composable
internal fun RuleRow(
    rule: PermissionRule,
    onDelete: () -> Unit,
    onPromote: (() -> Unit)?
) {
    val allowed = rule.decision == PermissionDecision.ALLOW
    val iconTint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    SwipeToDeleteRow(onDelete = onDelete) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (allowed) FeatherIcons.CheckCircle else FeatherIcons.XCircle,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.toolName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = (if (rule.pattern == PermissionRule.WHOLE_TOOL) {
                        stringResource(R.string.perm_entire_tool)
                    } else {
                        rule.pattern
                    }) + " · " + if (allowed) {
                        stringResource(R.string.common_allow)
                    } else {
                        stringResource(R.string.perm_deny)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onPromote != null) {
                IconButton(onClick = onPromote) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.perm_promote_to_global),
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun RuleEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

/** 页脚说明：灰色小字。 */
@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs)
    )
}
