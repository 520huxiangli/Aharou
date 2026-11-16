package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.settings.presentation.ExtensionsViewModel
import com.aharou.feature.settings.presentation.component.extension.ExtensionEntryRow
import com.aharou.feature.settings.presentation.component.extension.extensionKindLabelRes
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.Box
import compose.icons.feathericons.Database
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Package
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import kotlinx.coroutines.delay

/**
 * 扩展统一入口：把插件 / 技能 / MCP / 知识库 / 连接器五类收进一张列表，按类型筛选、按关键词搜索。
 *
 * 本页只负责「看 + 转发」：插件的启停与卸载就地完成，其余类型的安装 / 编辑仍在各自原页，
 * 由每行右下的「管理」按钮跳到那里（[onOpenSection]）。
 */
@Composable
internal fun ExtensionsSection(
    onOpenSection: (SettingsSection) -> Unit,
    viewModel: ExtensionsViewModel = hiltViewModel(),
) {
    val entries by viewModel.visibleEntries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val kindFilter by viewModel.kindFilter.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            ExtensionKindChips(selected = kindFilter, onSelect = viewModel::setKindFilter)

            AppTextField(
                value = query,
                onValueChange = viewModel::search,
                placeholder = stringResource(R.string.extension_search_hint),
                leadingIcon = {
                    Icon(
                        FeatherIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.semanticColors.subtleText
                    )
                }
            )

            error?.let {
                Text(
                    text = stringResource(R.string.extension_load_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 去原页的入口常驻最上面：某一类可能一条都没有（如还没配过 MCP），但路不能断
            // ——曾经把它挂在列表末尾、且要求列表非空才渲染，结果空分类下整块功能都进不去。
            kindFilter?.let { kind ->
                SettingsGroup {
                    SettingsRow(
                        icon = extensionJumpIcon(kind),
                        title = stringResource(extensionJumpSection(kind).titleRes),
                        subtitle = stringResource(R.string.extension_open_original_hint),
                        onClick = { onOpenSection(extensionJumpSection(kind)) }
                    )
                }
            }

            when {
                loading && entries.isEmpty() -> HintText(stringResource(R.string.extension_loading))
                entries.isEmpty() -> HintText(stringResource(R.string.extension_empty))
                else -> SettingsGroup {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        ExtensionEntryRow(
                            entry = entry,
                            busy = false,
                            anyBusy = false,
                            onAction = {},
                            onUninstall = {},
                            onOpenOriginal = { onOpenSection(extensionJumpSection(entry.kind)) },
                        )
                    }
                }
            }
        }
    }
}

/** 类型筛选 chips：全部 + 五类，横向可滑，源多了也不会挤成两行。 */
@Composable
private fun ExtensionKindChips(selected: ExtensionKind?, onSelect: (ExtensionKind?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.extension_kind_all)) }
        )
        ExtensionKind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(stringResource(extensionKindLabelRes(kind))) }
            )
        }
    }
}

/** 走哪个 [SettingsSection] 去管这一类扩展。 */
internal fun extensionJumpSection(kind: ExtensionKind): SettingsSection = when (kind) {
    ExtensionKind.PLUGIN -> SettingsSection.Plugins
    ExtensionKind.SKILL -> SettingsSection.Skills
    ExtensionKind.MCP -> SettingsSection.Mcp
    ExtensionKind.KNOWLEDGE -> SettingsSection.Knowledge
    ExtensionKind.CONNECTOR -> SettingsSection.Connectors
}

private fun extensionJumpIcon(kind: ExtensionKind): ImageVector = when (kind) {
    ExtensionKind.PLUGIN -> FeatherIcons.Package
    ExtensionKind.SKILL -> FeatherIcons.Book
    ExtensionKind.MCP -> FeatherIcons.Box
    ExtensionKind.KNOWLEDGE -> FeatherIcons.Database
    ExtensionKind.CONNECTOR -> FeatherIcons.Globe
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** 操作提示条的存活时长：够看完一句话（失败原因可能挺长）。 */
private const val EVENT_NOTICE_MS = 6_000L