package com.aharou.feature.settings.presentation.component

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.permission.PermissionDecision
import com.aharou.feature.agent.domain.permission.PermissionRule
import com.aharou.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Users
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onDeleteGlobal: (PermissionRule) -> Unit,
    // 默认助手只在这一处入口暴露；不穿过 SettingsScreen 传参（该文件被其它改动占用），
    // 用 rememberSettingsViewModel 取设置页那个 Activity 级实例，避免另建一份。
    assistantViewModel: SettingsViewModel = rememberSettingsViewModel()
) {
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    // 开启「禁用安全拦截」前先弹强制确认框（确认按钮 5 秒倒计时）；关闭无需确认。
    var showSafetyConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDefaultAssistant by assistantViewModel.isDefaultAssistant.collectAsStateWithLifecycle()
    // 用户可能直接在系统助手页里换掉我们，回页时重新判一次。
    LifecycleResumeEffect(Unit) {
        assistantViewModel.refreshDefaultAssistant()
        onPauseOrDispose { }
    }

    fun openDefaultAssistantSettings() {
        val intent = assistantViewModel.defaultAssistantIntent()
        if (intent == null) {
            Toast.makeText(context, R.string.voice_assistant_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.voice_assistant_open_failed, Toast.LENGTH_LONG).show()
        }
    }

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

        SettingsGroupHeader(text = stringResource(R.string.settings_default_assistant_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(R.string.settings_default_assistant_title),
                subtitle = stringResource(R.string.voice_assistant_summary),
                onClick = {
                    if (isDefaultAssistant) return@SettingsRow
                    scope.launch {
                        // 国产 ROM 的助手页常不把第三方列进去，有 Shizuku 时直接写系统值最可靠；
                        // 写入失败再退回系统页，不把无特权用户堵死。
                        if (assistantViewModel.applyDefaultAssistant()) {
                            assistantViewModel.refreshDefaultAssistant()
                            Toast.makeText(context, R.string.voice_assistant_set_ok, Toast.LENGTH_SHORT).show()
                        } else {
                            openDefaultAssistantSettings()
                        }
                    }
                },
                trailing = {
                    Text(
                        text = stringResource(
                            if (isDefaultAssistant) R.string.voice_assistant_yes else R.string.voice_assistant_set
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
    // 转屏不重置：倒计时是安全闩锁，旋转一下就能立刻确认等于它不存在。
    var secondsLeft by rememberSaveable { mutableStateOf(SAFETY_CONFIRM_COUNTDOWN_SECONDS) }
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
