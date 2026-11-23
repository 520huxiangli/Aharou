package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.plugin.data.market.OperitMarketCategoryOption
import com.aharou.feature.plugin.data.market.OperitMarketListing
import com.aharou.feature.plugin.data.market.OperitMarketType
import com.aharou.feature.settings.presentation.component.LazyGroupItem
import com.aharou.feature.settings.presentation.component.McpPill
import com.aharou.feature.settings.presentation.component.ModelSearchField
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsEmptyState
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CloudOff
import compose.icons.feathericons.Code
import compose.icons.feathericons.Package
import compose.icons.feathericons.Server
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Tag
import java.util.Locale

/**
 * Operit 市场列表。复用插件商店的搜索框、分组行、空态与重试版式，
 * 只是数据源换成分页的 Operit 静态市场。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperitMarketPage(
    onOpenDetail: (OperitMarketListing) -> Unit,
    viewModel: OperitMarketViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && visible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.page, state.hasMore) {
        if (shouldLoadMore && state.hasMore && !state.loadingMore && state.query.isBlank()) {
            viewModel.loadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                state.error != null && state.items.isEmpty() -> item(key = "error", contentType = "empty") {
                    OperitMarketErrorState(
                        error = state.error!!,
                        onRetry = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.ShoppingBag,
                        title = stringResource(R.string.plugins_store_empty_title),
                        description = stringResource(R.string.operit_market_empty_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                else -> {
                    if (state.error != null) {
                        item(key = "inline-error", contentType = "footer") {
                            OperitMarketInlineError(
                                error = state.error!!,
                                onRetry = viewModel::refresh
                            )
                        }
                    }
                    item(key = "source", contentType = "source") {
                        OperitSourceChips(
                            selected = state.source,
                            onSelect = viewModel::setSource
                        )
                    }
                    item(key = "search", contentType = "search") {
                        ModelSearchField(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            placeholder = stringResource(R.string.plugins_search_placeholder),
                            modifier = Modifier.padding(bottom = Spacing.xs)
                        )
                    }
                    if (state.source == OperitMarketSource.OPERIT) {
                        item(key = "categories", contentType = "categories") {
                            OperitCategoryChips(
                                categories = state.categories,
                                selected = state.scope.categoryId,
                                onSelect = viewModel::setCategory
                            )
                        }
                    }
                    if (state.filtered.isEmpty()) {
                        item(key = "search-empty", contentType = "empty") {
                            PluginSearchEmptyState()
                        }
                    } else {
                        itemsIndexed(
                            items = state.filtered,
                            key = { _, item -> item.id },
                            contentType = { _, _ -> "operit-row" }
                        ) { index, item ->
                            LazyGroupItem(index = index, count = state.filtered.size) {
                                OperitMarketRow(item = item, onClick = { onOpenDetail(item) })
                            }
                        }
                    }
                    if (state.loadingMore) {
                        item(key = "more-loading", contentType = "footer") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.md),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    } else if (state.hasMore) {
                        item(key = "more", contentType = "footer") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(onClick = viewModel::loadMore) {
                                    Text(stringResource(R.string.operit_market_load_more))
                                }
                            }
                        }
                    }
                    if (state.stale) {
                        item(key = "stale", contentType = "footer") {
                            Text(
                                text = stringResource(R.string.operit_market_cached_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.subtleText,
                                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperitMarketInlineError(error: OperitMarketError, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = operitErrorMessage(error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.plugins_store_retry)) }
    }
}

/** 来源切换：社区市场（1283 条）与我们镜像的 Operit2 官方包（56 条）。 */
@Composable
private fun OperitSourceChips(
    selected: OperitMarketSource,
    onSelect: (OperitMarketSource) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected == OperitMarketSource.OPERIT,
            onClick = { onSelect(OperitMarketSource.OPERIT) },
            label = { Text(stringResource(R.string.operit_market_source_operit)) }
        )
        FilterChip(
            selected = selected == OperitMarketSource.OPERIT2,
            onClick = { onSelect(OperitMarketSource.OPERIT2) },
            label = { Text(stringResource(R.string.operit2_market_source)) }
        )
    }
}

@Composable
private fun OperitCategoryChips(
    categories: List<OperitMarketCategoryOption>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
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
            label = { Text(stringResource(R.string.operit_market_all)) }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(operitCategoryLabel(category)) }
            )
        }
    }
}

@Composable
private fun OperitMarketRow(item: OperitMarketListing, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = operitTypeIcon(item.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                OperitTypePill(item.type)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = operitMetaLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = operitCompatHint(item.type),
                style = MaterialTheme.typography.bodySmall,
                color = operitCompatColor(item.type),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 单条详情：展示市场信息与兼容性提示，安装按钮触发「下载 → 校验 → 转换 → 预览」，
 * 预览与确认沿用 [PluginImportHost] 那套弹层。
 */
@Composable
internal fun OperitMarketDetailSection(
    item: OperitMarketListing,
    onShowPreview: (PluginStorePreview) -> Unit,
    viewModel: OperitMarketViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latest = state.items.firstOrNull { it.id == item.id } ?: item
    val installing = state.installingId == latest.id

    LaunchedEffect(state.preview) {
        // 预览是全局单槽状态；只弹出当前条目的预览，避免 A 的结果出现在 B 的详情页
        val preview = state.preview?.takeIf { it.operitItemId == latest.id } ?: return@LaunchedEffect
        onShowPreview(preview)
        viewModel.consumePreview()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.md, bottom = Spacing.xl)
    ) {
        OperitHeaderCard(item = latest)

        SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.plugins_store_detail_version),
                trailing = {
                    Text(
                        text = latest.version?.let { "v$it" }
                            ?: stringResource(R.string.plugins_store_version_latest),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Package,
                title = stringResource(R.string.operit_market_detail_type),
                trailing = { OperitInfoValue(operitTypeLabel(latest.type)) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Code,
                title = stringResource(R.string.operit_market_detail_downloads),
                trailing = { OperitInfoValue(operitDownloadText(latest.downloadCount)) }
            )
            latest.categoryId?.let { categoryId ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Server,
                    title = stringResource(R.string.plugins_store_detail_tags),
                    trailing = {
                        OperitInfoValue(
                            operitCategoryLabel(
                                OperitMarketCategoryOption(id = categoryId, fallbackName = categoryId)
                            )
                        )
                    }
                )
            }
            latest.asset?.let { asset ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Book,
                    title = stringResource(R.string.operit_market_detail_file),
                    trailing = { OperitInfoValue(asset.name) }
                )
            }
        }

        if (latest.description.isNotBlank() || latest.detail.isNotBlank()) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_contents))
            SettingsGroup {
                Text(
                    text = latest.detail.ifBlank { latest.description },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.operit_market_compat_header))
        SettingsGroup {
            Text(
                text = operitCompatHint(latest.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        }

        Button(
            onClick = { viewModel.install(latest) },
            enabled = latest.installable && !installing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg)
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(stringResource(R.string.operit_market_downloading))
            } else {
                Text(
                    stringResource(
                        if (latest.installable) R.string.plugins_install
                        else R.string.operit_market_not_installable
                    )
                )
            }
        }
        Text(
            text = stringResource(R.string.operit_market_install_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
        )

        val installError = state.installError?.takeIf { it.itemId == latest.id }
        if (installError != null) {
            Text(
                text = operitErrorMessage(installError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
            )
        }
    }
}

@Composable
private fun OperitHeaderCard(item: OperitMarketListing) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = operitTypeIcon(item.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    OperitTypePill(item.type)
                }
                Text(
                    text = operitMetaLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OperitInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

@Composable
private fun OperitTypePill(type: OperitMarketType) {
    McpPill(
        text = operitTypeLabel(type),
        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer
    )
}

@Composable
private fun OperitMarketErrorState(
    error: OperitMarketError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsEmptyState(
        icon = FeatherIcons.CloudOff,
        title = operitErrorMessage(error),
        description = error.detail?.takeIf { it.isNotBlank() },
        actions = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.plugins_store_retry)) }
        },
        modifier = modifier
    )
}

@Composable
private fun operitMetaLine(item: OperitMarketListing): String {
    val author = item.author.ifBlank { stringResource(R.string.operit_market_unknown_author) }
    return "$author · ${operitDownloadText(item.downloadCount)}"
}

@Composable
private fun operitDownloadText(count: Long): String =
    stringResource(R.string.operit_market_downloads, String.format(Locale.getDefault(), "%,d", count))

@Composable
private fun operitTypeLabel(type: OperitMarketType): String = stringResource(
    when (type) {
        OperitMarketType.PACKAGE -> R.string.operit_market_type_package
        OperitMarketType.SCRIPT -> R.string.operit_market_type_script
        OperitMarketType.SKILL -> R.string.operit_market_type_skill
        OperitMarketType.MCP -> R.string.operit_market_type_mcp
        OperitMarketType.UNKNOWN -> R.string.operit_market_type_unknown
    }
)

private fun operitTypeIcon(type: OperitMarketType): ImageVector = when (type) {
    OperitMarketType.PACKAGE -> FeatherIcons.Package
    OperitMarketType.SCRIPT -> FeatherIcons.Code
    OperitMarketType.SKILL -> FeatherIcons.Book
    OperitMarketType.MCP -> FeatherIcons.Server
    OperitMarketType.UNKNOWN -> FeatherIcons.Package
}

@Composable
private fun operitCompatHint(type: OperitMarketType): String = stringResource(
    when (type) {
        OperitMarketType.SCRIPT -> R.string.operit_market_compat_script
        OperitMarketType.PACKAGE -> R.string.operit_market_compat_package
        OperitMarketType.SKILL -> R.string.operit_market_compat_skill
        OperitMarketType.MCP -> R.string.operit_market_compat_mcp
        OperitMarketType.UNKNOWN -> R.string.operit_market_compat_unknown
    }
)

@Composable
private fun operitCompatColor(type: OperitMarketType): Color = when (type) {
    OperitMarketType.SCRIPT -> MaterialTheme.colorScheme.onSurfaceVariant
    OperitMarketType.PACKAGE -> MaterialTheme.semanticColors.warning
    OperitMarketType.SKILL, OperitMarketType.MCP, OperitMarketType.UNKNOWN ->
        MaterialTheme.semanticColors.subtleText
}

/** 分类名以市场 ID 为准做本地化；市场新增的分类退回它自己的英文名。 */
@Composable
internal fun operitCategoryLabel(option: OperitMarketCategoryOption): String = when (option.id) {
    "chat_communication" -> stringResource(R.string.operit_market_category_chat_communication)
    "life_entertainment" -> stringResource(R.string.operit_market_category_life_entertainment)
    "media_content" -> stringResource(R.string.operit_market_category_media_content)
    "system_data" -> stringResource(R.string.operit_market_category_system_data)
    "automation_workflow" -> stringResource(R.string.operit_market_category_automation_workflow)
    "dev_code" -> stringResource(R.string.operit_market_category_dev_code)
    "other" -> stringResource(R.string.operit_market_category_other)
    "search_research" -> stringResource(R.string.operit_market_category_search_research)
    "integration_api" -> stringResource(R.string.operit_market_category_integration_api)
    "docs_knowledge" -> stringResource(R.string.operit_market_category_docs_knowledge)
    "business_productivity" -> stringResource(R.string.operit_market_category_business_productivity)
    else -> option.fallbackName.ifBlank { option.id }
}

@Composable
private fun operitErrorMessage(error: OperitMarketError): String = when (error.code) {
    OperitMarketErrorCode.NETWORK -> stringResource(R.string.operit_market_error_network)
    OperitMarketErrorCode.INVALID_DATA -> stringResource(R.string.operit_market_error_invalid)
    OperitMarketErrorCode.NO_ASSET -> stringResource(R.string.operit_market_error_no_asset)
    OperitMarketErrorCode.DOWNLOAD_FAILED -> stringResource(R.string.plugins_store_error_download)
    OperitMarketErrorCode.CHECKSUM_MISMATCH -> stringResource(R.string.operit_market_error_checksum)
    OperitMarketErrorCode.PACKAGE_TOO_LARGE -> stringResource(R.string.plugins_error_archive_too_large)
    OperitMarketErrorCode.CONVERT_FAILED -> error.pluginErrors.firstOrNull()
        ?.let { pluginErrorMessage(it) }
        ?: stringResource(R.string.operit_market_error_convert)
    OperitMarketErrorCode.PREVIEW_FAILED -> error.pluginErrors.firstOrNull()
        ?.let { pluginErrorMessage(it) }
        ?: stringResource(R.string.plugins_store_error_preview)
}
