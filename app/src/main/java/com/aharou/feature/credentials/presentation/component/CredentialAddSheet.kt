package com.aharou.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Github
import compose.icons.feathericons.Key

/**
 * 「添加凭据」二选一弹层：GitHub 快捷登录（设备码授权）或手动填写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialAddSheet(
    onDismiss: () -> Unit,
    onGitHubLogin: () -> Unit,
    onManualAdd: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.credential_add_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xs)
            )
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Github,
                    title = stringResource(R.string.credential_add_method_github),
                    subtitle = stringResource(R.string.credential_add_method_github_desc),
                    onClick = onGitHubLogin
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Key,
                    title = stringResource(R.string.credential_add_method_manual),
                    subtitle = stringResource(R.string.credential_add_method_manual_desc),
                    onClick = onManualAdd
                )
            }
        }
    }
}
