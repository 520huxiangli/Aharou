package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.agent.domain.rule.RuleFileRepository
import com.aharou.feature.settings.presentation.FragmentError
import com.aharou.feature.settings.presentation.RuleEditTarget
import com.aharou.feature.settings.presentation.RuleScope
import com.aharou.feature.settings.presentation.RulesUiState
import com.aharou.feature.settings.presentation.RulesViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.delay

/** 停笔多久后写盘：太短会在打字过程中反复写文件，太长又容易没等到就退出页面。 */
private const val AUTO_SAVE_MS = 600L

/**
 * 规则页：列表列出全局 / 项目两份主规则与各自的 `rules.d` 片段，点任一条进详情页编辑。
 *
 * 版式照记忆分区来——进详情后顶部是「返回 / 删除」两行，下面是输入框与保存按钮，
 * 而不是把大输入框直接摊在列表上。
 */
@Composable
internal fun RulesSection(
    state: RulesUiState,
    viewModel: RulesViewModel = hiltViewModel()
) {
    val editing = state.editing
    if (editing != null) {
        RuleEditorPane(
            target = editing,
            document = if (editing.scope == RuleScope.GLOBAL) state.global else state.project,
            initialName = state.editingName,
            initialContent = state.editingContent,
            saving = state.saving,
            error = state.fragmentError,
            failed = state.failedScope == editing.scope,
            onBack = viewModel::closeEdit,
            onSave = viewModel::saveEditing,
            onDelete = viewModel::deleteEditing
        )
        return
    }

    var scopeTab by rememberSaveable { mutableStateOf(0) }
    val scope = if (scopeTab == 0) RuleScope.GLOBAL else RuleScope.PROJECT
    val document = if (scopeTab == 0) state.global else state.project
    val enabled = if (scopeTab == 0) !state.loading else !state.loading && state.hasWorkspace

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl)
    ) {
        // 与记忆页一致：顶部先选作用域，下面只铺一份列表，而不是两组卡片上下堆着
        SegmentedTabs(
            selected = scopeTab,
            labels = listOf(
                stringResource(R.string.rules_global_title),
                stringResource(R.string.rules_project_title)
            ),
            onSelect = { scopeTab = it },
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm)
        )
        Spacer(Modifier.height(Spacing.md))
        RuleListGroup(
            document = document,
            enabled = enabled,
            onOpenRule = { viewModel.openRule(scope) },
            onOpenFragment = { name -> viewModel.openFragment(scope, name) },
            onNewFragment = { viewModel.startNewFragment(scope) }
        )
        if (scopeTab == 1 && !state.hasWorkspace) {
            Text(
                text = stringResource(R.string.rules_no_workspace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.warning,
                modifier = Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm)
            )
        }
    }
}

/** 一个作用域的列表：主规则一行 + `rules.d` 片段若干行 + 新建片段。 */
@Composable
private fun RuleListGroup(
    document: RuleFileRepository.RuleDocument?,
    enabled: Boolean,
    onOpenRule: () -> Unit,
    onOpenFragment: (String) -> Unit,
    onNewFragment: () -> Unit
) {
    SettingsGroup(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        SettingsRow(
            icon = FeatherIcons.FileText,
            title = document?.fileName ?: stringResource(R.string.rules_open_rule),
            subtitle = ruleSummary(document),
            enabled = enabled,
            onClick = onOpenRule
        )
        val fragments = document?.fragments.orEmpty()
        for (name in fragments) {
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = name,
                subtitle = stringResource(R.string.rules_fragment_subtitle),
                enabled = enabled,
                onClick = { onOpenFragment(name) }
            )
        }
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Plus,
            title = stringResource(R.string.rules_fragment_new),
            enabled = enabled,
            onClick = onNewFragment
        )
    }
}

/** 主规则一行的副标题：没配显示「未配置」，配了给字数与生效路径。 */
@Composable
private fun ruleSummary(document: RuleFileRepository.RuleDocument?): String {
    if (document == null) return stringResource(R.string.rules_loading)
    val chars = document.content.trim().length
    if (chars == 0 && document.fragments.isEmpty()) return stringResource(R.string.rules_summary_none)
    return stringResource(R.string.rules_file_path, document.displayPath)
}

/** 规则详情页：照记忆编辑器——顶部返回 / 删除，下面输入框与保存按钮。 */
@Composable
private fun RuleEditorPane(
    target: RuleEditTarget,
    document: RuleFileRepository.RuleDocument?,
    initialName: String,
    initialContent: String,
    saving: Boolean,
    error: FragmentError?,
    failed: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    val isFragment = target.isNew || target.fragment != null
    var name by remember(target, initialName) { mutableStateOf(initialName) }
    var content by remember(target, initialContent) { mutableStateOf(initialContent) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 自动保存的基准：只有内容真的变了才写盘，避免刚打开页面就把原内容重复写一遍
    var saved by remember(target, initialName) { mutableStateOf(initialName to initialContent) }

    LaunchedEffect(name, content) {
        if ((name to content) == saved) return@LaunchedEffect
        // 片段还没填文件名时不自动写，否则会拿空名去写盘
        if (isFragment && name.isBlank()) return@LaunchedEffect
        delay(AUTO_SAVE_MS)
        saved = name to content
        onSave(name, content)
    }

    val title = when {
        target.isNew -> stringResource(R.string.rules_fragment_new)
        isFragment -> target.fragment.orEmpty()
        target.scope == RuleScope.GLOBAL -> stringResource(R.string.rules_global_title)
        else -> stringResource(R.string.rules_project_title)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroup(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            SettingsRow(
                icon = FeatherIcons.ArrowLeft,
                title = stringResource(R.string.rules_back),
                onClick = onBack
            )
            if (!target.isNew) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Trash2,
                    title = stringResource(R.string.common_delete),
                    enabled = !saving,
                    onClick = { confirmDelete = true }
                )
            }
        }

        SettingsGroupHeader(text = title)
        SettingsGroup(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)) {
                if (document != null && !isFragment) {
                    Text(
                        text = stringResource(R.string.rules_file_path, document.displayPath),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (isFragment) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.rules_fragment_name),
                        enabled = !saving,
                        supportingText = { Text(stringResource(R.string.rules_fragment_name_hint)) }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = stringResource(R.string.rules_fragment_content),
                    singleLine = false,
                    maxLines = 30,
                    enabled = !saving
                )
                if (error != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(
                            when (error) {
                                FragmentError.INVALID_NAME -> R.string.rules_fragment_error_name
                                FragmentError.DUPLICATE -> R.string.rules_fragment_error_duplicate
                                FragmentError.IO -> R.string.rules_fragment_error_io
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (failed) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.rules_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.rules_auto_save_hint),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rules_delete_title)) },
            text = {
                Text(
                    if (isFragment) {
                        stringResource(R.string.rules_fragment_delete_confirm, target.fragment.orEmpty())
                    } else {
                        stringResource(R.string.rules_delete_rule_confirm)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
