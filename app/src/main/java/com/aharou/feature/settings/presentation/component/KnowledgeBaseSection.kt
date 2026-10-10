package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.presentation.KnowledgeBaseStatus
import com.aharou.feature.settings.presentation.KnowledgeBaseViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Database

/**
 * 共享知识库页：列出各源与本地文档数，可单独/整体同步，也能自己加一个仓库当源。
 *
 * 加进来的源只是「从哪个仓库拉 Markdown」，同步结果落在应用私有目录，
 * AI 侧由 `knowledge_search` 工具读取——本页不碰检索本身。
 */
@Composable
internal fun KnowledgeBaseSection(
    viewModel: KnowledgeBaseViewModel = hiltViewModel()
) {
    var input by remember { mutableStateOf("") }
    // per-app 语言下进程 Locale 未必跟着 App 走，取配置里的语言才准
    val lang = LocalConfiguration.current.locales[0].language
    val busy = viewModel.busy
    val sources = viewModel.sources
    val status = viewModel.status

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_knowledge_base))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Database,
                title = stringResource(R.string.knowledge_base_sync_all),
                subtitle = stringResource(R.string.knowledge_base_sync_all_hint),
                enabled = !busy,
                onClick = { viewModel.syncAll() }
            )

            if (status != KnowledgeBaseStatus.Idle) {
                SettingsDivider()
                Text(
                    text = statusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 11.dp)
                )
            }

            if (sources.isEmpty()) {
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.knowledge_base_no_source))
            } else {
                sources.forEach { source ->
                    SettingsDivider()
                    SettingsRow(
                        title = source.definition.displayName(lang),
                        subtitle = stringResource(
                            R.string.knowledge_base_source_meta,
                            source.docCount,
                            source.definition.repo
                        ),
                        trailing = {
                            TextButton(
                                onClick = { viewModel.sync(source.id) },
                                enabled = !busy
                            ) {
                                Text(stringResource(R.string.knowledge_base_sync))
                            }
                            if (source.custom) {
                                TextButton(onClick = { viewModel.removeSource(source.id) }) {
                                    Text(stringResource(R.string.knowledge_base_remove))
                                }
                            }
                        }
                    )
                }
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.knowledge_base_add_source))
        SettingsGroup {
            Column(modifier = Modifier.padding(Spacing.md)) {
                AppTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.knowledge_base_add_source_label),
                    placeholder = stringResource(R.string.knowledge_base_add_source_hint),
                    isError = status == KnowledgeBaseStatus.InvalidRepo
                )
                TextButton(
                    onClick = {
                        if (viewModel.addSource(input)) input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(top = Spacing.sm)
                ) {
                    Text(stringResource(R.string.knowledge_base_add))
                }
            }
        }
    }
}

@Composable
private fun statusText(status: KnowledgeBaseStatus): String = when (status) {
    KnowledgeBaseStatus.Syncing -> stringResource(R.string.knowledge_base_syncing)
    KnowledgeBaseStatus.Synced -> stringResource(R.string.knowledge_base_synced)
    KnowledgeBaseStatus.Failed -> stringResource(R.string.knowledge_base_sync_failed)
    KnowledgeBaseStatus.InvalidRepo -> stringResource(R.string.knowledge_base_invalid_repo)
    KnowledgeBaseStatus.Idle -> ""
}
