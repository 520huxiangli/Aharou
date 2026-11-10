package com.aharou.feature.localmodel.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.localmodel.data.LocalModelEntry

/**
 * 本地模型页：清单、安装、删除、试一句。
 *
 * 模型文件不进 APK，装的时候才下载；所以这里每一条的主要动作是「安装」，
 * 而不是「打开」。原生库没编进包时整页只留一句说明。
 */
@Composable
internal fun LocalModelsSection(viewModel: LocalModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.available) {
        Box(Modifier.fillMaxSize().padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.local_models_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.local_models_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.local_models_subtitle) + "  " +
                        stringResource(R.string.local_models_used, formatBytes(state.usedBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { message ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = viewModel::clearError) {
                            Text(stringResource(R.string.local_models_dismiss))
                        }
                    }
                }
            }
        }
        state.testOutput?.let { output ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(output, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::clearOutput) {
                            Text(stringResource(R.string.local_models_dismiss))
                        }
                    }
                }
            }
        }
        items(state.models, key = { it.id }) { entry ->
            ModelRow(
                entry = entry,
                installed = state.installed.containsKey(entry.id),
                progress = state.progress[entry.id],
                testing = state.testing == entry.id,
                testingIdle = state.testing == null,
                onInstall = { viewModel.install(entry.id) },
                onRemove = { viewModel.remove(entry.id) },
                onTest = { viewModel.test(entry.id) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    entry: LocalModelEntry,
    installed: Boolean,
    progress: com.aharou.feature.localmodel.data.LocalModelStore.Progress?,
    testing: Boolean,
    testingIdle: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
) {
    Surface(
        color = MaterialTheme.semanticColors.cardSurface,
        shape = RoundedCornerShape(Radius.mdLarge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = formatBytes(entry.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "${entry.kind} · ${entry.engine} · ${entry.license}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                progress != null -> {
                    Spacer(Modifier.height(Spacing.sm))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = when (progress.stage) {
                            com.aharou.feature.localmodel.data.LocalModelStore.Stage.Downloading ->
                                stringResource(R.string.local_models_installing, (progress.fraction * 100).toInt())
                            com.aharou.feature.localmodel.data.LocalModelStore.Stage.Verifying ->
                                stringResource(R.string.local_models_verifying)
                            com.aharou.feature.localmodel.data.LocalModelStore.Stage.Writing ->
                                stringResource(R.string.local_models_writing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                !entry.runnable -> {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.local_models_engine_needed, entry.engine),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (installed) {
                            TextButton(onClick = onTest, enabled = testingIdle) {
                                Text(
                                    if (testing) stringResource(R.string.local_models_testing)
                                    else stringResource(R.string.local_models_test)
                                )
                            }
                            TextButton(onClick = onRemove) {
                                Text(stringResource(R.string.local_models_remove))
                            }
                            Text(
                                text = stringResource(R.string.local_models_installed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        } else {
                            TextButton(onClick = onInstall) {
                                Text(stringResource(R.string.local_models_install))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} ${units[0]}" else "%.1f %s".format(value, units[index])
}
