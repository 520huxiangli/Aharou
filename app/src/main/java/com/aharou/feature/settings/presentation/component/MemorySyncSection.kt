package com.aharou.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.agent.domain.memory.MemorySyncScheduler
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

    LaunchedEffect(outcome) {
        val result = outcome ?: return@LaunchedEffect
        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        lastResult = viewModel.lastResult()
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
                title = stringResource(R.string.memory_sync_auto),
                subtitle = when {
                    syncing -> stringResource(R.string.memory_sync_working)
                    lastResult.isNotBlank() -> lastResult
                    else -> stringResource(
                        R.string.memory_sync_auto_hint,
                        MemorySyncScheduler.MIN_INTERVAL_MINUTES
                    )
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
}
