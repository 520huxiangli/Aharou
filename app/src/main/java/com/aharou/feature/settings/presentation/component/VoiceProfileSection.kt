package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.voice.domain.VoiceModelState
import com.aharou.feature.voice.tts.VoiceProfile
import com.aharou.feature.voice.tts.VoiceProfileState
import com.aharou.feature.voice.tts.VoiceProfileViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Volume2

/**
 * 设置页里「我的音色」这一行，点开是录制与管理的弹层。
 *
 * 收在一个组件里而不是拆成回调传进父级：音色档案是这个弹层独有的状态，
 * 父级（默认模型设置）没有别的地方要读它，多传一层只会让已经贴着行数上限的
 * DefaultModelsSection 继续变长。
 */
@Composable
internal fun VoiceProfileRow(modifier: Modifier = Modifier) {
    val viewModel: VoiceProfileViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedId }?.name
    SettingsRow(
        icon = FeatherIcons.Mic,
        title = stringResource(R.string.settings_voice_profile_title),
        subtitle = selectedName ?: stringResource(R.string.settings_voice_profile_summary),
        onClick = {
            viewModel.refresh()
            showSheet = true
        },
        trailing = {
            Text(
                text = stringResource(R.string.settings_voice_profile_manage),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        },
        modifier = modifier,
    )
    if (showSheet) {
        VoiceProfileSheet(viewModel = viewModel, onDismiss = { showSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceProfileSheet(
    viewModel: VoiceProfileViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val uploadingId by viewModel.uploadingId.collectAsStateWithLifecycle()
    val cloudVoices by viewModel.cloudVoices.collectAsStateWithLifecycle()
    val cloudLoading by viewModel.cloudLoading.collectAsStateWithLifecycle()
    val currentVoice by viewModel.currentVoice.collectAsStateWithLifecycle()
    var cloudQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshCloudVoices() }
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    // 不限定 audio/*：wav、m4a、mp3、微信语音的 amr 各家 MIME 不一致，实在找不到文件更痛苦。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importAudio(uri) }

    var draftName by remember { mutableStateOf("") }
    var draftText by remember { mutableStateOf("") }
    val reviewKey = (state as? VoiceProfileState.Review)?.durationMs
    // 转写结果只灌一次：之后用户在输入框里改的内容不能被重组顶回原样。
    LaunchedEffect(reviewKey) {
        if (reviewKey != null) draftText = (state as? VoiceProfileState.Review)?.text.orEmpty()
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = {
            viewModel.discard()
            viewModel.clearError()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.voice_profile_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.voice_profile_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 克隆模型不在设置页的「下载全部」里（156MB，只有用克隆才需要），所以在这一屏自己管。
            val downloading = modelState as? VoiceModelState.Downloading
            val modelBusy = downloading != null || modelState is VoiceModelState.Extracting
            if (!modelStatus.ready || modelBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.voice_profile_model_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = when {
                                downloading != null -> stringResource(
                                    R.string.settings_voice_model_downloading,
                                    (downloading.progress * 100).toInt(),
                                )
                                modelState is VoiceModelState.Extracting ->
                                    stringResource(R.string.voice_model_extracting)
                                modelState is VoiceModelState.Failed ->
                                    stringResource(R.string.settings_voice_model_download_failed)
                                modelStatus.neverDownloaded ->
                                    stringResource(R.string.settings_voice_model_not_downloaded)
                                else -> stringResource(R.string.settings_voice_model_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = viewModel::downloadModel, enabled = !modelBusy) {
                        Text(
                            stringResource(
                                if (modelBusy) R.string.settings_voice_model_downloading_short
                                else R.string.settings_voice_model_download
                            )
                        )
                    }
                }
            }

            when (val current = state) {
                VoiceProfileState.Recording -> {
                    StatusLine(stringResource(R.string.voice_profile_recording))
                    ActionRow(
                        primary = stringResource(R.string.voice_profile_stop),
                        onPrimary = viewModel::stopRecording,
                        secondary = stringResource(R.string.voice_profile_discard),
                        onSecondary = viewModel::discard,
                    )
                }
                VoiceProfileState.Transcribing -> {
                    StatusLine(stringResource(R.string.voice_profile_transcribing))
                }
                is VoiceProfileState.Review -> {
                    Text(
                        text = stringResource(R.string.voice_profile_review_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.voice_profile_review_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        placeholder = stringResource(R.string.voice_profile_review_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        placeholder = stringResource(R.string.voice_profile_name_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ActionRow(
                        primary = stringResource(R.string.voice_profile_save),
                        onPrimary = {
                            viewModel.save(draftName, draftText)
                            draftName = ""
                            draftText = ""
                        },
                        secondary = stringResource(R.string.voice_profile_rerecord),
                        onSecondary = {
                            draftText = ""
                            viewModel.discard()
                        },
                    )
                }
                is VoiceProfileState.Error -> {
                    Text(
                        text = stringResource(current.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                VoiceProfileState.Idle -> {
                    ActionRow(
                        primary = stringResource(R.string.voice_profile_record),
                        onPrimary = viewModel::startRecording,
                        secondary = stringResource(R.string.voice_profile_import),
                        onSecondary = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.voice_cloud_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refreshCloudVoices, enabled = !cloudLoading) {
                    Text(stringResource(R.string.voice_cloud_list_refresh))
                }
            }
            // 云端动辄几百个音色，不做过滤就只能一路滑到底。
            val filteredCloud = if (cloudQuery.isBlank()) {
                cloudVoices
            } else {
                cloudVoices.filter {
                    it.name.contains(cloudQuery, ignoreCase = true) ||
                        it.referenceText.contains(cloudQuery, ignoreCase = true)
                }
            }
            if (cloudVoices.isNotEmpty()) {
                AppTextField(
                    value = cloudQuery,
                    onValueChange = { cloudQuery = it },
                    placeholder = stringResource(R.string.voice_cloud_search_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filteredCloud.isEmpty()) {
                Text(
                    text = stringResource(
                        when {
                            cloudLoading -> R.string.voice_profile_syncing
                            cloudQuery.isNotBlank() -> R.string.voice_cloud_search_empty
                            else -> R.string.voice_cloud_list_empty
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filteredCloud.forEach { cloud ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = cloud.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = cloud.referenceText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = { viewModel.selectCloudVoice(cloud) },
                            enabled = currentVoice != cloud.uri,
                        ) {
                            Text(
                                stringResource(
                                    if (currentVoice == cloud.uri) R.string.voice_profile_in_use
                                    else R.string.voice_profile_use
                                )
                            )
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.voice_profile_empty_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        playing = profile.id == playingId,
                        uploading = uploadingId == profile.id,
                        onSelect = { viewModel.select(profile.id) },
                        onPreview = { viewModel.preview(profile.id) },
                        onUploadCloud = { viewModel.uploadToCloud(profile.id) },
                        onDelete = { viewModel.delete(profile.id) },
                    )
                }
                if (selectedId != null) {
                    TextButton(onClick = viewModel::clearSelection) {
                        Text(stringResource(R.string.voice_profile_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: VoiceProfile,
    selected: Boolean,
    playing: Boolean,
    uploading: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onUploadCloud: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (profile.cloudVoiceUri.isNotBlank()) {
                    stringResource(R.string.voice_profile_cloud_ready) + " · " + profile.referenceText
                } else {
                    profile.referenceText
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        if (uploading) {
            Text(
                text = stringResource(R.string.voice_profile_syncing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (profile.cloudVoiceUri.isBlank()) {
            TextButton(onClick = onUploadCloud) {
                Text(stringResource(R.string.voice_profile_sync_cloud))
            }
        }
        TextButton(onClick = onPreview, enabled = !playing) {
            Text(stringResource(if (playing) R.string.voice_profile_previewing else R.string.voice_profile_preview))
        }
        TextButton(onClick = onSelect, enabled = !selected) {
            Text(
                stringResource(
                    if (selected) R.string.voice_profile_in_use else R.string.voice_profile_use
                )
            )
        }
        TextButton(onClick = onDelete) {
            Text(
                stringResource(R.string.voice_profile_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            FeatherIcons.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionRow(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = onPrimary) { Text(primary) }
        if (secondary != null) {
            TextButton(onClick = onSecondary) { Text(secondary) }
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}
