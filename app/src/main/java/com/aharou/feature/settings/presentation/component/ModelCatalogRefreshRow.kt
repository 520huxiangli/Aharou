package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.settings.presentation.CatalogRefreshState
import com.aharou.feature.settings.presentation.ModelCatalogViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.RefreshCw

/**
 * 「刷新模型目录」行：点一次去拉最新目录，结果直接显示在副标题里。
 *
 * 首次拉取是懒触发的（进设置页用旧快照、后台刷新），这一行是给「现在就要最新」准备的。
 */
@Composable
internal fun ModelCatalogRefreshRow(
    viewModel: ModelCatalogViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subtitle = when (state) {
        CatalogRefreshState.Refreshing -> "…"
        CatalogRefreshState.Done -> stringResource(R.string.models_catalog_updated)
        CatalogRefreshState.Failed -> stringResource(R.string.models_catalog_failed)
        CatalogRefreshState.Idle -> null
    }

    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.RefreshCw,
            title = stringResource(R.string.models_catalog_refresh),
            subtitle = subtitle,
            enabled = state !is CatalogRefreshState.Refreshing,
            onClick = { viewModel.refresh() }
        )
    }
}
