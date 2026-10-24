package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/**
 * 供应商编辑页的「基本信息」分区：名称、API Key（含明文切换）、Base URL、自定义请求头入口、
 * 供应商类型入口。
 *
 * 从 `ProviderEditorScreen` 主屏整块移出（UI 未改动，只把局部状态换成参数与回调）。
 */
@Composable
internal fun ProviderBasicGroup(
    name: String,
    onNameChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    multiKeyEnabled: Boolean,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    headers: List<Pair<String, String>>,
    onOpenHeaders: () -> Unit,
    typeLabel: String,
    onOpenType: () -> Unit,
) {
    SettingsGroupHeader(text = stringResource(R.string.provider_section_basic))
    SettingsGroup(
        modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_CONFIG_INFO)
    ) {
        ProviderTextFieldRow(
            label = stringResource(R.string.common_name),
            value = name,
            onValueChange = onNameChange
        )
        if (!multiKeyEnabled) {
            ProviderTextFieldRow(
                label = stringResource(R.string.provider_api_key),
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = stringResource(R.string.provider_api_key_placeholder),
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    Icon(
                        imageVector = if (apiKeyVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                        contentDescription = stringResource(
                            if (apiKeyVisible) R.string.provider_hide_api_key else R.string.provider_show_api_key
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onToggleApiKeyVisible() }
                            .padding(2.dp)
                    )
                }
            )
        }
        ProviderTextFieldRow(
            label = stringResource(R.string.provider_base_url),
            value = baseUrl,
            onValueChange = onBaseUrlChange
        )
        SettingsDivider()
        SettingsRow(
            icon = null,
            title = stringResource(R.string.provider_headers_title),
            subtitle = if (headers.isEmpty()) {
                stringResource(R.string.provider_headers_desc)
            } else {
                stringResource(R.string.provider_headers_count, headers.count { it.first.isNotBlank() })
            },
            onClick = onOpenHeaders,
            trailing = {
                Text(
                    text = if (headers.isEmpty()) {
                        stringResource(R.string.provider_headers_add)
                    } else {
                        stringResource(R.string.provider_headers_manage)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        SettingsDivider()
        SettingsRow(
            icon = null,
            title = stringResource(R.string.provider_section_type),
            onClick = onOpenType,
            trailing = {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
