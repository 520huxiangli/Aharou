package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.voice.domain.VoiceModelStatus
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.feathericons.Layers
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.settings.data.local.ModelSheetCollapseStore
import com.aharou.feature.settings.data.repository.ModelGroupRepository
import com.aharou.feature.settings.data.repository.ModelGroupResolver
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Image
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Type
import compose.icons.feathericons.Volume2

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    titleProviderId: String,
    titleModel: String,
    imageGenProviderId: String,
    imageGenModel: String,
    voiceSttProviderId: String,
    voiceSttModel: String,
    voiceTtsProviderId: String,
    voiceTtsModel: String,
    voiceTtsVoice: String,
    autoReadAloud: Boolean = false,
    onAutoReadAloudChange: (Boolean) -> Unit = {},
    onToggleAutoReadAloud: () -> Unit = {},
    voiceModelStatus: VoiceModelStatus,
    voiceModelMessage: Int? = null,
    onRereleaseVoiceModel: () -> Unit = {},
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit,
    onSelectTitleModel: (providerId: String, model: String) -> Unit,
    onClearTitleModel: () -> Unit,
    onSelectImageGenModel: (providerId: String, model: String) -> Unit,
    onClearImageGenModel: () -> Unit,
    onSelectVoiceModel: (providerId: String, model: String) -> Unit,
    onClearVoiceModel: () -> Unit,
    onSelectVoiceTtsModel: (providerId: String, model: String) -> Unit,
    onChangeVoiceTtsVoice: (String) -> Unit,
    onClearVoiceTtsModel: () -> Unit
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }
    var showTitleSheet by remember { mutableStateOf(false) }
    var showImageGenSheet by remember { mutableStateOf(false) }
    var showVoiceSttSheet by remember { mutableStateOf(false) }
    var showVoiceTtsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    // [Aharou] 模型组：本页各角色可整组选择（执行时按组内成员顺序取用）
    val groupsViewModel: com.aharou.feature.settings.presentation.ModelGroupsViewModel = hiltViewModel()
    val modelGroups by groupsViewModel.groups.collectAsStateWithLifecycle()

    val visionValue = if (ModelGroupResolver.isGroupSelection(visionProviderId)) {
        stringResource(R.string.modelgroups_group_prefix_label, ModelGroupResolver.groupName(visionProviderId).orEmpty())
    } else if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        visionModel
    }

    val compactionValue = if (ModelGroupResolver.isGroupSelection(compactionProviderId)) {
        stringResource(R.string.modelgroups_group_prefix_label, ModelGroupResolver.groupName(compactionProviderId).orEmpty())
    } else if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        compactionModel
    }

    val titleValue = if (ModelGroupResolver.isGroupSelection(titleProviderId)) {
        stringResource(R.string.modelgroups_group_prefix_label, ModelGroupResolver.groupName(titleProviderId).orEmpty())
    } else if (titleProviderId.isBlank() || titleModel.isBlank()) {
        stringResource(R.string.settings_title_follow_chat)
    } else {
        titleModel
    }

    val imageGenValue = if (ModelGroupResolver.isGroupSelection(imageGenProviderId)) {
        stringResource(R.string.modelgroups_group_prefix_label, ModelGroupResolver.groupName(imageGenProviderId).orEmpty())
    } else if (imageGenProviderId.isBlank() || imageGenModel.isBlank()) {
        stringResource(R.string.settings_image_gen_unconfigured)
    } else {
        imageGenModel
    }

    val voiceSttValue = if (voiceSttProviderId.isBlank() || voiceSttModel.isBlank()) {
        stringResource(R.string.settings_voice_local_fallback)
    } else {
        voiceSttModel
    }

    val voiceTtsValue = if (voiceTtsProviderId.isBlank() || voiceTtsModel.isBlank()) {
        stringResource(R.string.settings_voice_tts_unconfigured)
    } else {
        voiceTtsModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_vision_model),
                onClick = { showVisionSheet = true },
                trailing = {
                    Text(
                        text = visionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Minimize2,
                title = stringResource(R.string.settings_compaction_model),
                onClick = { showCompactionSheet = true },
                trailing = {
                    Text(
                        text = compactionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Type,
                title = stringResource(R.string.settings_title_model),
                onClick = { showTitleSheet = true },
                trailing = {
                    Text(
                        text = titleValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Camera,
                title = stringResource(R.string.settings_image_gen_model),
                onClick = { showImageGenSheet = true },
                trailing = {
                    Text(
                        text = imageGenValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Mic,
                title = stringResource(R.string.settings_voice_stt_model),
                onClick = { showVoiceSttSheet = true },
                trailing = {
                    Text(
                        text = voiceSttValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Volume2,
                title = stringResource(R.string.settings_voice_tts_model),
                onClick = { showVoiceTtsSheet = true },
                trailing = {
                    Text(
                        text = voiceTtsValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Volume2,
                title = stringResource(R.string.settings_voice_auto_read),
                subtitle = stringResource(R.string.settings_voice_auto_read_desc),
                onClick = onToggleAutoReadAloud,
                trailing = {
                    AppSwitch(
                        checked = autoReadAloud,
                        onCheckedChange = onAutoReadAloudChange
                    )
                }
            )
            SettingsDivider()
            val voiceModelProblem = if (voiceModelStatus.ready) {
                null
            } else {
                val missing = stringResource(R.string.settings_voice_model_missing)
                val mismatch = stringResource(R.string.settings_voice_model_size_mismatch)
                voiceModelStatus.files.filter { !it.ok }
                    .joinToString("、") { f -> "${f.name}：" + if (f.actual == null) missing else mismatch }
            }
            SettingsRow(
                icon = FeatherIcons.Mic,
                title = stringResource(R.string.settings_voice_model_title),
                subtitle = when {
                    voiceModelMessage != null -> stringResource(voiceModelMessage)
                    voiceModelProblem != null -> voiceModelProblem
                    else -> stringResource(R.string.settings_voice_model_ready)
                },
                onClick = onRereleaseVoiceModel,
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_voice_model_rerelease),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            )
        }
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            groups = modelGroups,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false }
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            groups = modelGroups,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false }
        )
    }

    if (showTitleSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_title_model),
            noModelsText = stringResource(R.string.title_no_models),
            providers = providers,
            groups = modelGroups,
            currentProviderId = titleProviderId,
            currentModel = titleModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectTitleModel(pid, model)
                showTitleSheet = false
            },
            onClear = {
                onClearTitleModel()
                showTitleSheet = false
            },
            onDismiss = { showTitleSheet = false }
        )
    }

    if (showImageGenSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_image_gen_model),
            noModelsText = stringResource(R.string.image_gen_no_models),
            providers = providers,
            groups = modelGroups,
            currentProviderId = imageGenProviderId,
            currentModel = imageGenModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectImageGenModel(pid, model)
                showImageGenSheet = false
            },
            onClear = {
                onClearImageGenModel()
                showImageGenSheet = false
            },
            onDismiss = { showImageGenSheet = false }
        )
    }

    if (showVoiceSttSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_voice_stt_model),
            noModelsText = stringResource(R.string.voice_no_models),
            providers = providers,
            currentProviderId = voiceSttProviderId,
            currentModel = voiceSttModel,
            modelMetadata = modelMetadata,
            modelFilter = ::isSpeechModel,
            onSelect = { pid, model ->
                onSelectVoiceModel(pid, model)
                showVoiceSttSheet = false
            },
            onClear = {
                onClearVoiceModel()
                showVoiceSttSheet = false
            },
            onDismiss = { showVoiceSttSheet = false }
        )
    }

    if (showVoiceTtsSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_voice_tts_model),
            noModelsText = stringResource(R.string.voice_tts_no_models),
            providers = providers,
            currentProviderId = voiceTtsProviderId,
            currentModel = voiceTtsModel,
            modelMetadata = modelMetadata,
            modelFilter = ::isSpeechModel,
            voiceField = VoiceFieldState(
                value = voiceTtsVoice,
                onValueChange = onChangeVoiceTtsVoice,
                placeholder = stringResource(R.string.settings_voice_tts_voice_hint)
            ),
            onSelect = { pid, model ->
                onSelectVoiceTtsModel(pid, model)
                showVoiceTtsSheet = false
            },
            onClear = {
                onClearVoiceTtsModel()
                showVoiceTtsSheet = false
            },
            onDismiss = { showVoiceTtsSheet = false }
        )
    }
}

/**
 * 合成音色输入框的状态。音色名各家格式不同（`alloy` / `longxiaochun` /
 * `FunAudioLLM/CosyVoice2-0.5B:alex`），无法用统一枚举，故纯文本输入、原样透传。
 */
internal data class VoiceFieldState(
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String
)

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    groups: List<ModelGroupRepository.ModelGroup> = emptyList(),
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** 模型名过滤：语音模型等专用场景用它把不相关的模型挡在外面（模型组的整组选择不受影响）。 */
    modelFilter: ((String) -> Boolean)? = null,
    /** 非空时在列表上方插一个音色输入框（仅语音合成用）。 */
    voiceField: VoiceFieldState? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val collapseStore = remember { ModelSheetCollapseStore(context.applicationContext) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedProviderIds by remember { mutableStateOf(collapseStore.collapsedProviderIds()) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // [Aharou] 模型组：整组选择（执行时按组内成员顺序取用）
            if (groups.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.modelgroups_title),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.sm, bottom = 2.dp),
                    )
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(ModelGroupRepository.GROUP_PREFIX + group.id, "") }
                                .padding(horizontal = Spacing.lg, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = group.name,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.modelgroups_member_count, group.members.size),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SettingsDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                onClear?.let { onClear ->
                    Text(
                        text = stringResource(R.string.common_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint)
            )

            voiceField?.let { field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_voice_tts_voice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(64.dp)
                    )
                    AppTextField(
                        value = field.value,
                        onValueChange = field.onValueChange,
                        placeholder = field.placeholder,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            // 过滤后的可选项：过滤为空时视为该 provider 无可用模型，不展示它的分组
            fun visibleModels(provider: AIProviderConfig): List<String> =
                provider.models.filter {
                    (modelFilter == null || modelFilter(it)) &&
                        (searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true))
                }
            val hasAnyVisible = activeProviders.any { visibleModels(it).isNotEmpty() }
            if (!hasAnyVisible) {
                SettingsGroup {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = noModelsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    activeProviders.forEach { provider ->
                        val filteredModels = visibleModels(provider)
                        if (filteredModels.isNotEmpty()) {
                            // 搜索时强制展开，否则用折叠状态：用户搜到名字却看不到结果会很困惑。
                            val expanded = searchQuery.isNotBlank() || provider.id !in collapsedProviderIds
                            item(key = "header_${provider.id}") {
                                CollapsibleGroupHeader(
                                    text = "${provider.name} (${filteredModels.size})",
                                    expanded = expanded,
                                    onToggle = {
                                        val updated = if (provider.id in collapsedProviderIds) {
                                            collapsedProviderIds - provider.id
                                        } else {
                                            collapsedProviderIds + provider.id
                                        }
                                        collapsedProviderIds = updated
                                        collapseStore.save(updated)
                                    }
                                )
                            }
                            if (expanded) {
                                item(key = "card_${provider.id}") {
                                    SettingsGroup {
                                        filteredModels.forEachIndexed { index, model ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            ModelSelectionRow(
                                                model = model,
                                                selected = provider.id == currentProviderId && model == currentModel,
                                                metadata = modelMetadata[modelMetadataKey(provider.id, model)],
                                                onClick = { onSelect(provider.id, model) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")

/**
 * 判断模型名是否属于「语音模型」（语音转文字 / 文字转语音）。
 *
 * 供应商的 `/v1/models` 返回的是自家全部模型，其中绝大多数是对话模型，而语音模型没有统一的能力字段
 * 可供筛选——这里按业界常见的命名约定做关键词匹配。匹配不到时用户仍可在供应商里手动添加模型名。
 */
internal fun isSpeechModel(model: String): Boolean {
    val name = model.lowercase()
    return SPEECH_MODEL_KEYWORDS.any { name.contains(it) }
}

private val SPEECH_MODEL_KEYWORDS = listOf(
    // 语音转文字
    "whisper", "transcrib", "sensevoice", "paraformer", "funasr",
    "speech-to-text", "stt", "asr", "-speech", "speech-", "audio-transcri",
    // 文字转语音
    "tts", "text-to-speech", "cosyvoice", "sovits", "fish-speech", "fishaudio",
    "chattts", "moss-ttsd", "index-tts", "f5-tts", "kokoro",
    // 语音对话类
    "voice-", "-voice", "realtime-audio", "audio-preview",
)
