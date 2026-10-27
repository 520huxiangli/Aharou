package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.presentation.ContainerWorkspaceProfilesViewModel
import com.aharou.feature.settings.presentation.WorkspaceProfileRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Server

/**
 * 容器页的「工作区运行环境」卡片：一行一个工作区，点开选它用哪个容器。
 *
 * 只管「在这个工作区里执行的命令落在哪个容器」；MCP / 语言服务器 / 沙盒仍用上面选中的全局容器，
 * 所以底部特意写明这一点，避免用户以为改了这里就全都跟着换。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮（那边只剩 2 行余量）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContainerWorkspaceProfilesSection(
    viewModel: ContainerWorkspaceProfilesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<WorkspaceProfileRow?>(null) }

    Spacer(Modifier.height(Spacing.md))
    SettingsGroupHeader(text = stringResource(R.string.settings_container_workspace_env_title))
    SettingsGroup {
        if (state.rows.isEmpty()) {
            SettingsRow(
                title = stringResource(R.string.settings_container_workspace_env_empty_title),
                subtitle = stringResource(R.string.settings_container_workspace_env_empty_desc)
            )
        } else {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.HardDrive,
                    title = row.workspaceName,
                    subtitle = if (row.profileId == null) {
                        stringResource(R.string.settings_container_workspace_env_follow_global, state.defaultProfileName)
                    } else {
                        row.profileName.orEmpty()
                    },
                    onClick = { picking = row },
                    trailing = {
                        Icon(
                            imageVector = FeatherIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.settings_container_workspace_env_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
    )
    Spacer(Modifier.height(Spacing.md))

    picking?.let { row ->
        WorkspaceProfilePickerSheet(
            row = row,
            profiles = state.profiles,
            defaultProfileName = state.defaultProfileName,
            onPick = { profileId ->
                viewModel.setWorkspaceProfile(row.workspacePath, profileId)
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

/** 选择弹层：第一项「跟随全局」，后面是本机所有容器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceProfilePickerSheet(
    row: WorkspaceProfileRow,
    profiles: List<ContainerProfile>,
    defaultProfileName: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = row.workspaceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.settings_container_workspace_env_picker_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_container_workspace_env_follow_global, defaultProfileName),
                    subtitle = stringResource(R.string.settings_container_workspace_env_picker_follow_desc),
                    onClick = { onPick(null) },
                    trailing = { if (row.profileId == null) SelectedCheck() }
                )
                profiles.forEach { profile ->
                    SettingsDivider()
                    val remote = profile.mode == ExecutionMode.REMOTE_SSH
                    SettingsRow(
                        icon = if (remote) FeatherIcons.Server else FeatherIcons.HardDrive,
                        title = profile.name,
                        subtitle = if (remote) {
                            stringResource(R.string.settings_container_workspace_env_remote)
                        } else {
                            stringResource(R.string.settings_container_workspace_env_local)
                        },
                        onClick = { onPick(profile.id) },
                        trailing = { if (row.profileId == profile.id) SelectedCheck() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedCheck() {
    Icon(
        imageVector = FeatherIcons.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp)
    )
}
