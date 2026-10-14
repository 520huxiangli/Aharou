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
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.settings.presentation.MemorySyncViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Zap

/**
 * 设置页「记忆」分区里的同步区：填自己的仓库地址、手动同步一次。
 *
 * 仓库根就是记忆目录本身（详见 [com.aharou.feature.agent.domain.memory.MemorySyncManager]），
 * 所以这里只同步全局记忆；项目记忆留在各自工作区，不进仓库。
 */
@Composable
internal fun MemorySyncSection(viewModel: MemorySyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    var repoUrl by remember { mutableStateOf(viewModel.repoUrl()) }
    var lastResult by remember { mutableStateOf(viewModel.lastResult()) }
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
                title = stringResource(R.string.memory_sync_now),
                subtitle = lastResult.ifBlank { stringResource(R.string.memory_sync_never) },
                trailing = {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                onClick = { if (!syncing) viewModel.syncNow() },
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
