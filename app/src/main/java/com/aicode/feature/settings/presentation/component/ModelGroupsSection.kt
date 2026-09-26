package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.repository.ModelGroupRepository
import com.aicode.feature.settings.presentation.ModelGroupsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/**
 * 设置页「模型组」分区（自 OpenMinis 的 Model Groups 移植·适配）。
 *
 * 组 = 具名的有序模型集合；列表 / 新建 / 详情（成员增删排序）/ 删除。
 * 选中组的地方（如「默认模型」各角色）执行时按成员顺序取用（首成员优先）。
 */
@Composable
internal fun ModelGroupsSection(viewModel: ModelGroupsViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    val detail = groups.firstOrNull { it.id == detailId }
    if (detail != null) {
        ModelGroupDetailPane(
            group = detail,
            viewModel = viewModel,
            onBack = { detailId = null },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.modelgroups_title))
        SettingsGroup {
            Text(
                text = stringResource(R.string.modelgroups_intro),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.modelgroups_list_header))
        if (groups.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.modelgroups_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                groups.forEachIndexed { index, group ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { detailId = group.id }
                            .padding(horizontal = Spacing.lg, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val first = group.members.firstOrNull()
                            Text(
                                text = if (first != null) {
                                    stringResource(
                                        R.string.modelgroups_member_count_with_first,
                                        group.members.size,
                                        first.providerName,
                                        first.model,
                                    )
                                } else {
                                    stringResource(R.string.modelgroups_member_count, group.members.size)
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.modelgroups_create),
                onClick = { showCreate = true },
            )
        }
    }

    if (showCreate) {
        ModelGroupNameDialog(
            title = stringResource(R.string.modelgroups_create),
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                val created = viewModel.create(name)
                showCreate = false
                detailId = created.id
            },
        )
    }
}

@Composable
private fun ModelGroupDetailPane(
    group: ModelGroupRepository.ModelGroup,
    viewModel: ModelGroupsViewModel,
    onBack: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    val providers by viewModel.providers.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.ArrowLeft,
                title = stringResource(R.string.modelgroups_back_to_list),
                onClick = onBack,
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Edit2,
                title = stringResource(R.string.modelgroups_rename),
                subtitle = group.name,
                onClick = { showRename = true },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Trash2,
                title = stringResource(R.string.modelgroups_delete),
                onClick = { showDelete = true },
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.modelgroups_members_header))
        SettingsGroup {
            Text(
                text = stringResource(R.string.modelgroups_members_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 10.dp),
            )
        }
        if (group.members.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.modelgroups_no_members),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                group.members.forEachIndexed { index, member ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.model,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = member.providerName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.moveMember(group.id, index, -1) },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                FeatherIcons.ArrowUp,
                                contentDescription = stringResource(R.string.modelgroups_move_up),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.moveMember(group.id, index, +1) },
                            enabled = index < group.members.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                FeatherIcons.ArrowDown,
                                contentDescription = stringResource(R.string.modelgroups_move_down),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeMember(group.id, index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.modelgroups_remove_member),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.modelgroups_add_member),
                onClick = { showAddMember = true },
            )
        }
    }

    if (showRename) {
        ModelGroupNameDialog(
            title = stringResource(R.string.modelgroups_rename),
            initial = group.name,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                viewModel.rename(group.id, name)
                showRename = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.modelgroups_delete_title, group.name)) },
            text = { Text(stringResource(R.string.modelgroups_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(group.id)
                    showDelete = false
                    onBack()
                }) {
                    Text(
                        text = stringResource(R.string.modelgroups_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showAddMember) {
        AddMemberSheet(
            providers = providers,
            onDismiss = { showAddMember = false },
            onPick = { providerId, providerName, model ->
                viewModel.addMember(group.id, providerId, providerName, model)
                showAddMember = false
            },
        )
    }
}

@Composable
private fun ModelGroupNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.modelgroups_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.modelgroups_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberSheet(
    providers: List<com.aicode.feature.settings.domain.model.AIProviderConfig>,
    onDismiss: () -> Unit,
    onPick: (providerId: String, providerName: String, model: String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.modelgroups_add_member),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.md),
            )
            val enabledProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            if (enabledProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.modelgroups_no_provider_models),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md),
                )
            } else {
                enabledProviders.forEach { provider ->
                    Text(
                        text = provider.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    provider.models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(provider.id, provider.name, model) }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = model,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
