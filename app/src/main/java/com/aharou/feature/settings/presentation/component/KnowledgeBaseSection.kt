package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.presentation.KnowledgeBaseStatus
import com.aharou.feature.settings.presentation.KnowledgeBaseViewModel

/**
 * 共享知识库页（全屏二级页，形态对齐技能市场）：顶上一排分类，下面列出各知识库，
 * 每个自己一个「安装」——没有一次装全部的入口，没装的源不占本地空间。
 *
 * 内容都在我们自己的镜像仓库里（`520huxiangli/aharou-kb`），所以列表里不显示上游仓库名，
 * 只显示分类、篇数这些用户真正关心的东西。
 */
@Composable
internal fun KnowledgeBaseSection(
    viewModel: KnowledgeBaseViewModel = hiltViewModel()
) {
    var repoInput by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    // per-app 语言下进程 Locale 未必跟着 App 走，取配置里的语言才准
    val lang = LocalConfiguration.current.locales[0].language
    val sources = viewModel.sources
    val categories = viewModel.categories
    val status = viewModel.status
    val busyId = viewModel.busyId

    val visible = remember(sources, category) {
        if (category == null) sources else sources.filter { it.definition.category == category }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text(stringResource(R.string.knowledge_base_category_all)) }
                    )
                    categories.forEach { item ->
                        val label = item.name[lang] ?: item.name.values.firstOrNull() ?: item.id
                        FilterChip(
                            selected = category == item.id,
                            onClick = { category = if (category == item.id) null else item.id },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.knowledge_base_add_source_hint),
                    singleLine = true,
                    isError = status == KnowledgeBaseStatus.InvalidRepo,
                    keyboardActions = KeyboardActions(onDone = {
                        if (viewModel.addSource(repoInput)) repoInput = ""
                    })
                )
                TextButton(
                    onClick = { if (viewModel.addSource(repoInput)) repoInput = "" },
                    enabled = repoInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.knowledge_base_add))
                }
            }

            if (status == KnowledgeBaseStatus.InvalidRepo) {
                Text(
                    text = stringResource(R.string.knowledge_base_invalid_repo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = stringResource(R.string.knowledge_base_no_source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(visible, key = { it.id }) { source ->
                    KnowledgeRow(
                        title = source.definition.displayName(lang),
                        description = source.definition.displayDescription(lang),
                        docCount = source.docCount,
                        installed = source.installed,
                        working = busyId == source.id,
                        blocked = busyId != null && busyId != source.id,
                        custom = source.custom,
                        onInstall = { viewModel.install(source.id) },
                        onUninstall = { viewModel.uninstall(source.id) },
                        onRemoveSource = { viewModel.removeSource(source.id) }
                    )
                }
                item { Spacer(modifier = Modifier.padding(bottom = Spacing.xl)) }
            }
        }
    }
}

/**
 * 单个知识库一行：名称 + 描述 + 篇数，右侧是安装/已安装。
 * 用户自己加的源多一个「删除」，用来把它从列表里摘掉（本地副本一并清）。
 */
@Composable
private fun KnowledgeRow(
    title: String,
    description: String,
    docCount: Int,
    installed: Boolean,
    working: Boolean,
    blocked: Boolean,
    custom: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onRemoveSource: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(10.dp))
            .clickable(enabled = !installed && !working && !blocked) { onInstall() }
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (installed) {
                Text(
                    text = stringResource(R.string.knowledge_base_installed_meta, docCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        when {
            working -> Text(
                text = stringResource(R.string.knowledge_base_installing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
            installed -> TextButton(onClick = onUninstall) {
                Text(stringResource(R.string.knowledge_base_uninstall))
            }
            else -> TextButton(onClick = onInstall, enabled = !blocked) {
                Text(stringResource(R.string.knowledge_base_install))
            }
        }

        if (custom) {
            TextButton(onClick = onRemoveSource) {
                Text(stringResource(R.string.knowledge_base_remove))
            }
        }
    }
}
