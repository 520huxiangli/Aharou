package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.settings.domain.model.ProviderDashboardState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Play
import compose.icons.feathericons.X

/**
 * 供应商编辑页的「自定义面板（DIY）」分区：脚本路径、测试按钮、脚本参数入口、测试结果。
 *
 * 从 `ProviderEditorScreen` 主屏整块移出（UI 未改动，只把局部状态换成参数与回调），
 * 这一块只读写脚本路径与测试状态，不碰供应商其余字段。
 */
@Composable
internal fun ProviderDashboardGroup(
    scriptPath: String,
    onScriptPathChange: (String) -> Unit,
    scriptParams: List<Pair<String, String>>,
    testState: ProviderDashboardState,
    providerName: String,
    onPickScript: () -> Unit,
    onRunTest: () -> Unit,
    onOpenParams: () -> Unit,
) {
    SettingsGroupHeader(text = stringResource(R.string.provider_section_dashboard))
    SettingsGroup {
        ProviderTextFieldRow(
            label = stringResource(R.string.provider_dashboard_script),
            value = scriptPath,
            onValueChange = onScriptPathChange,
            placeholder = stringResource(R.string.provider_dashboard_script_placeholder),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scriptPath.isNotBlank()) {
                        IconButton(
                            onClick = { onScriptPathChange("") },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.X,
                                contentDescription = stringResource(R.string.provider_dashboard_clear_script),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onPickScript,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Folder,
                            contentDescription = stringResource(R.string.provider_dashboard_select_script),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
        SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.provider_dashboard_test_btn),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.provider_dashboard_script_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            IconButton(
                onClick = onRunTest,
                enabled = scriptPath.isNotBlank() && testState !is ProviderDashboardState.Loading,
                modifier = Modifier.size(36.dp)
            ) {
                if (testState is ProviderDashboardState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = FeatherIcons.Play,
                        contentDescription = stringResource(R.string.provider_dashboard_run_test),
                        tint = if (scriptPath.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        SettingsDivider()
        SettingsRow(
            icon = null,
            title = stringResource(R.string.provider_script_params_title),
            subtitle = if (scriptParams.isEmpty()) {
                stringResource(R.string.provider_script_params_desc)
            } else {
                stringResource(R.string.provider_script_params_count, scriptParams.count { it.first.isNotBlank() })
            },
            onClick = onOpenParams,
            trailing = {
                Text(
                    text = if (scriptParams.isEmpty()) {
                        stringResource(R.string.provider_script_param_add)
                    } else {
                        stringResource(R.string.provider_script_param_manage)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        if (testState !is ProviderDashboardState.Idle) {
            SettingsDivider()
            DashboardTestResultBox(
                state = testState,
                providerName = providerName
            )
        }
    }
}
