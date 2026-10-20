package com.aharou.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.settings.presentation.MemorySyncViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Zap

/**
 * 设置页「记忆」分区里的同步区：填自己的仓库地址、开关自动同步。
 *
 * 仓库是一个收集桶（`global/` + `projects/<工作区名-项目身份哈希>/`），全局与各项目的记忆都进得去；
 * 细节见 [com.aharou.feature.agent.domain.memory.MemorySyncManager]。
 */
@Composable
internal fun MemorySyncSection(viewModel: MemorySyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    var repoUrl by remember { mutableStateOf(viewModel.repoUrl()) }
    var lastResult by remember { mutableStateOf(viewModel.lastResult()) }
    var autoSync by remember { mutableStateOf(viewModel.autoSyncEnabled()) }
    var editingRepo by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    var hasToken by remember { mutableStateOf(false) }
    // 凭据查询是挂起的，取值不能写在 remember 初始化里（那会在组合期同步等）。
    LaunchedEffect(Unit) { hasToken = viewModel.hasToken() }
    var lastCounts by remember { mutableStateOf<SyncCounts?>(null) }
    var pendingDeletions by remember { mutableStateOf(viewModel.pendingDeletions()) }

    LaunchedEffect(outcome) {
        val result = outcome ?: return@LaunchedEffect
        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        if (result.success) {
            lastCounts = SyncCounts(result.collected, result.conflicts, result.pendingDeletions.size)
        }
        lastResult = viewModel.lastResult()
        pendingDeletions = viewModel.pendingDeletions()
        viewModel.consumeOutcome()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsGroupHeader(text = stringResource(R.string.memory_sync_header))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_sync_repo_title),
                subtitle = repoUrl.ifBlank { stringResource(R.string.memory_sync_repo_empty) },
                onClick = { editingRepo = true },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_sync_token_title),
                subtitle = if (hasToken) stringResource(R.string.memory_sync_token_set)
                    else stringResource(R.string.memory_sync_token_empty),
                onClick = { editingToken = true },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_sync_now),
                subtitle = lastResult.ifBlank { stringResource(R.string.memory_sync_never) },
                enabled = !syncing && repoUrl.isNotBlank(),
                onClick = viewModel::syncNow,
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.memory_sync_auto),
                subtitle = when {
                    syncing -> stringResource(R.string.memory_sync_working)
                    else -> {
                        val hint = stringResource(
                            R.string.memory_sync_auto_hint,
                            viewModel.autoSyncIntervalMinutes()
                        )
                        if (lastResult.isNotBlank()) "$hint · $lastResult" else hint
                    }
                },
                trailing = {
                    AppSwitch(
                        checked = autoSync,
                        onCheckedChange = {
                            autoSync = it
                            viewModel.setAutoSync(it)
                        }
                    )
                }
            )
        }

        // 周期任务由系统调度：省电策略、后台限制都可能把它推迟，用户看到时间不更新时先想到这里。
        if (autoSync) {
            Text(
                text = stringResource(R.string.memory_sync_system_limit_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }

        // 上次同步的明细：把数量摊开；「疑似在其他设备被删除」只提示，不执行删除。
        val counts = lastCounts
        if (counts != null || pendingDeletions.isNotEmpty()) {
            SettingsGroupHeader(text = stringResource(R.string.memory_sync_result_header))
            SettingsGroup {
                if (counts != null) {
                    SyncCountRow(stringResource(R.string.memory_sync_result_collected), counts.collected)
                    SettingsDivider()
                    SyncCountRow(stringResource(R.string.memory_sync_result_conflicts), counts.conflicts)
                    SettingsDivider()
                    SyncCountRow(stringResource(R.string.memory_sync_pending_deleted), counts.pendingDeleted)
                }
                if (pendingDeletions.isNotEmpty()) {
                    if (counts != null) SettingsDivider()
                    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.memory_sync_pending_deleted_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        pendingDeletions.forEach { path ->
                            Text(
                                text = path,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (editingRepo) {
        var draft by remember { mutableStateOf(repoUrl) }
        AlertDialog(
            onDismissRequest = { editingRepo = false },
            title = { Text(stringResource(R.string.memory_sync_repo_title)) },
            text = {
                AppTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = stringResource(R.string.memory_sync_repo_hint),
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveRepoUrl(draft)
                    repoUrl = draft.trim()
                    editingRepo = false
                }) {
                    Text(stringResource(R.string.memory_edit_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRepo = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (editingToken) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingToken = false },
            title = { Text(stringResource(R.string.memory_sync_token_title)) },
            text = {
                AppTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = stringResource(R.string.memory_sync_token_hint),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = dialogTextFieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveToken(draft)
                    hasToken = draft.isNotBlank()
                    editingToken = false
                }) {
                    Text(stringResource(R.string.memory_edit_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingToken = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** 一次同步的数量明细，供结果区展示。 */
private data class SyncCounts(val collected: Int, val conflicts: Int, val pendingDeleted: Int)

/** 结果区的一行：左侧说明，右侧数字。 */
@Composable
private fun SyncCountRow(title: String, count: Int) {
    SettingsRow(
        title = title,
        trailing = {
            Text(
                text = count.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
