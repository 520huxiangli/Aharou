package com.aharou.feature.settings.presentation.component

import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import com.aharou.core.util.ProviderIconStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.SwipeToDeleteRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.aharou.core.util.PanelScriptImporter
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.FloatingTabBar
import com.aharou.core.ui.FloatingTabItem
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.KeyRotationStrategy
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aharou.feature.settings.domain.model.ProviderKey
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.data.repository.ProxyConfig
import com.aharou.feature.settings.domain.model.ProxyType
import com.aharou.feature.settings.domain.model.mergeModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import com.aharou.feature.settings.domain.model.sanitized
import com.aharou.feature.settings.presentation.FetchState
import com.aharou.feature.settings.presentation.SettingsViewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aharou.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import android.content.Intent
import android.widget.Toast
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import compose.icons.feathericons.X
import com.aharou.feature.agent.presentation.component.AdaptiveCardView
import com.aharou.feature.settings.domain.model.ProviderDashboardResult
import com.aharou.feature.settings.domain.model.ProviderDashboardState
import androidx.compose.ui.res.stringResource
import com.aharou.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelSheet(
    existingModels: List<String>,
    title: String,
    confirmLabel: String,
    initial: ModelMetadata?,
    /** 系统给的默认显示名（官方名）；重置时回到它，为空则回到模型名。 */
    defaultAlias: String? = null,
    /** 所属供应商 id：模型自定义图标的文件名要带上它，否则两个供应商的同名模型会共用一个文件。 */
    providerId: String,
    onSave: (String, ModelMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)
    var modelName by remember { mutableStateOf(initial?.id ?: "") }
    // 显示名：列表里给模型起的别名，留空就显示原始模型名。
    var displayName by remember { mutableStateOf(initial?.displayName?.takeIf { it != initial.id } ?: "") }
    var customIcon by remember { mutableStateOf(initial?.customIcon ?: "") }
    var showIconDialog by remember { mutableStateOf(false) }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var supportsImageOutput by remember { mutableStateOf(initial?.supportsImageOutput ?: false) }
    var supportsTools by remember { mutableStateOf(initial?.supportsTools ?: false) }
    var supportsReasoning by remember { mutableStateOf(initial?.supportsReasoning ?: false) }
    var contextTokens by remember { mutableStateOf(initial?.contextTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var inputTokens by remember { mutableStateOf(initial?.inputTokens?.toString() ?: "") }
    var outputTokens by remember { mutableStateOf(initial?.outputTokens?.toString() ?: "") }
    var inputPrice by remember { mutableStateOf(initial?.inputCostUsdPerM?.toString() ?: "") }
    var outputPrice by remember { mutableStateOf(initial?.outputCostUsdPerM?.toString() ?: "") }
    var cacheReadPrice by remember { mutableStateOf(initial?.cacheReadCostUsdPerM?.toString() ?: "") }
    var cacheWritePrice by remember { mutableStateOf(initial?.cacheWriteCostUsdPerM?.toString() ?: "") }
    val trimmedModel = modelName.trim()
    val duplicate = existingModels.any { it == trimmedModel && it != initial?.id }
    val contextError = contextTokens.isNotBlank() && (contextTokens.trim().toIntOrNull() ?: 0) <= 0
    val inputError = inputTokens.isNotBlank() && (inputTokens.trim().toIntOrNull() ?: 0) <= 0
    val outputError = outputTokens.isNotBlank() && (outputTokens.trim().toIntOrNull() ?: 0) <= 0
    val canSave = trimmedModel.isNotEmpty() && !duplicate && !contextError && !inputError && !outputError

    // 保存逻辑抽出来，保存按钮和「重置」共用：重置得立刻落盘才算真的重置，只改输入框等于没生效。
    val saveWithName: (String) -> Unit = { rawName ->
        val resolvedName = rawName.trim().ifBlank { trimmedModel }
        val meta = ModelMetadata(
            id = trimmedModel,
            displayName = resolvedName,
            // 只有真的改过名字才在列表里当别名显示；改回原名/重置 = 未改动。
            customName = resolvedName != (defaultAlias?.takeIf { it.isNotBlank() } ?: trimmedModel),
            customIcon = customIcon,
            contextTokens = contextTokens.trim().toIntOrNull() ?: 0,
            inputTokens = inputTokens.trim().toIntOrNull(),
            outputTokens = outputTokens.trim().toIntOrNull(),
            inputCostUsdPerM = inputPrice.trim().toDoubleOrNull(),
            outputCostUsdPerM = outputPrice.trim().toDoubleOrNull(),
            cacheReadCostUsdPerM = cacheReadPrice.trim().toDoubleOrNull(),
            cacheWriteCostUsdPerM = cacheWritePrice.trim().toDoubleOrNull(),
            supportsVision = supportsVision,
            supportsImageOutput = supportsImageOutput,
            supportsTools = supportsTools,
            supportsReasoning = supportsReasoning
        )
        onSave(trimmedModel, meta)
    }

    val iconPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            ProviderIconStore.import(context, "model-$providerId-$trimmedModel", uri)?.let { customIcon = it }
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
                .imePadding()
        ) {
            // ── 顶部标题栏：居中标题（仿 MCP 编辑对话框）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(36.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(36.dp))
            }

            // ── 表单区：输入框直接铺背景，卡片承载能力开关 ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .nestedScroll(flingFix),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_name),
                    value = modelName,
                    onValueChange = { modelName = it }
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_display_name),
                    value = displayName,
                    onValueChange = { displayName = it }
                )
                if (initial != null) {
                    // 重置 = 恢复成没改过的状态：显示名回系统原名并立即保存，列表回到模型名。
                    TextButton(
                        onClick = {
                            val restored = defaultAlias?.takeIf { it.isNotBlank() } ?: trimmedModel
                            displayName = restored
                            saveWithName(restored)
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = stringResource(R.string.provider_model_display_name_reset),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.provider_model_icon),
                    subtitle = if (customIcon.isBlank()) stringResource(R.string.provider_model_icon_hint) else null,
                    onClick = { showIconDialog = true },
                    trailing = {
                        ModelLogoIcon(modelName = trimmedModel, customIcon = customIcon, size = 22.dp)
                    }
                )
                if (showIconDialog) {
                    ProviderIconDialog(
                        currentIcon = customIcon,
                        onPickGallery = {
                            showIconDialog = false
                            iconPickLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onConfirmLink = { link ->
                            customIcon = link
                            showIconDialog = false
                        },
                        onReset = {
                            ProviderIconStore.delete(context, "model-$providerId-$trimmedModel")
                            customIcon = ""
                            showIconDialog = false
                        },
                        onDismiss = { showIconDialog = false }
                    )
                }
                if (duplicate) {
                    Text(
                        text = stringResource(R.string.provider_model_already_added),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_context_total),
                    value = contextTokens,
                    onValueChange = { contextTokens = it },
                    keyboardType = KeyboardType.Number,
                    isError = contextError
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_context_input),
                    value = inputTokens,
                    onValueChange = { inputTokens = it },
                    keyboardType = KeyboardType.Number,
                    isError = inputError
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_context_output),
                    value = outputTokens,
                    onValueChange = { outputTokens = it },
                    keyboardType = KeyboardType.Number,
                    isError = outputError
                )

                SectionLabel(stringResource(R.string.provider_model_section_price))
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_input),
                    value = inputPrice,
                    onValueChange = { inputPrice = it },
                    keyboardType = KeyboardType.Decimal
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_output),
                    value = outputPrice,
                    onValueChange = { outputPrice = it },
                    keyboardType = KeyboardType.Decimal
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_cache_read),
                    value = cacheReadPrice,
                    onValueChange = { cacheReadPrice = it },
                    keyboardType = KeyboardType.Decimal
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_cache_write),
                    value = cacheWritePrice,
                    onValueChange = { cacheWritePrice = it },
                    keyboardType = KeyboardType.Decimal
                )

                SectionLabel(stringResource(R.string.provider_model_capabilities))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        // 中性浅灰，避免 surfaceVariant 在蓝调主题下偏蓝。
                        containerColor = MaterialTheme.semanticColors.mutedSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_cap_vision),
                            checked = supportsVision,
                            onCheckedChange = { supportsVision = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_cap_image_output),
                            checked = supportsImageOutput,
                            onCheckedChange = { supportsImageOutput = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_capability_tools),
                            checked = supportsTools,
                            onCheckedChange = { supportsTools = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_capability_reasoning),
                            checked = supportsReasoning,
                            onCheckedChange = { supportsReasoning = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── 底部保存按钮（仿 MCP 编辑对话框）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = Spacing.xl)
                    .navigationBarsPadding()
            ) {
                Button(
                    enabled = canSave,
                    onClick = { saveWithName(displayName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(confirmLabel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/** 分段组小标题：灰色小字、紧凑间距，直接铺在弹窗背景上（无卡片）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.semanticColors.subtleText,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.xs)
    )
}

/** 添加/编辑模型弹窗内的全宽输入框：样式与 MCP 编辑对话框一致。 */
@Composable
private fun ModelSheetTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.provider_model_window_invalid)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 能力开关行：标题 + 右侧 Switch，卡片内一行。 */
@Composable
private fun CapabilitySwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FetchModelsDialog(
    fetchState: FetchState,
    providerId: String,
    modelMetadata: Map<String, ModelMetadata>,
    existingModels: List<String>,
    onFetchModels: () -> Unit,
    onAddModel: (String) -> Unit,
    onAddModels: (List<String>) -> Unit = { list -> list.forEach(onAddModel) },
    onDismiss: () -> Unit,
    isOnboarding: Boolean = false,
    onOnboardingModelAdded: (() -> Unit)? = null,
    onOnboardingDismissFetchDialog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var searchQuery by remember { mutableStateOf("") }
    var showDebugSheet by remember { mutableStateOf(false) }
    var showAddAllConfirm by remember { mutableStateOf(false) }

    val availableNewModels = remember(fetchState, existingModels, isOnboarding) {
        when (fetchState) {
            is FetchState.Success -> fetchState.models.filter { it !in existingModels }
            is FetchState.Error -> if (isOnboarding) listOf("deepseek-v4-flash").filter { it !in existingModels } else emptyList()
            else -> emptyList()
        }
    }

    val debugInfo = when (fetchState) {
        is FetchState.Success -> fetchState.debugInfo
        is FetchState.Error -> fetchState.debugInfo
        else -> null
    }

    LaunchedEffect(Unit) {
        // Wait for bottom sheet animation to smooth out before firing network request
        delay(300)
        onFetchModels()
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = {
            onDismiss()
            if (isOnboarding) {
                onOnboardingDismissFetchDialog?.invoke()
            }
        },
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.provider_fetch_models),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (availableNewModels.isNotEmpty()) {
                    TextButton(
                        onClick = { showAddAllConfirm = true },
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = stringResource(R.string.provider_add_all_models),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint),
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )

            when (fetchState) {
                is FetchState.Loading -> {
                    FetchModelsSkeleton()
                }
                is FetchState.Error -> {
                    if (isOnboarding) {
                        // 引导模式下未配置有效 Key 时，呈现 DeepSeek 推荐模型演示供新手继续体验
                        val fallbackModels = listOf("deepseek-v4-flash").filter { it !in existingModels }
                        val grouped = fallbackModels.groupBy { m -> modelBrandKey(m) }
                            .toSortedMap(compareBy<String> { it == "other" }.thenBy { brandDisplayName(context, it) })
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            grouped.forEach { (brandKey, models) ->
                                item(key = "header_$brandKey") {
                                    SettingsGroupHeader("${brandDisplayName(context, brandKey)} (${models.size})")
                                }
                                item(key = "card_$brandKey") {
                                    SettingsGroup {
                                        models.forEachIndexed { index, m ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            val isFirstTarget = brandKey == grouped.firstKey() && index == 0
                                            FetchModelRow(
                                                model = m,
                                                metadata = modelMetadata[m],
                                                onAdd = {
                                                    onAddModel(m)
                                                    onDismiss()
                                                    if (isOnboarding) {
                                                        onOnboardingModelAdded?.invoke()
                                                    }
                                                },
                                                modifier = if (isFirstTarget) Modifier.onboardingTarget(OnboardingStep.SIMULATE_FETCH_DIALOG) else Modifier
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        SettingsGroup {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 320.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val displayMsg = if (debugInfo != null && debugInfo.responseCode > 0) {
                                    stringResource(R.string.provider_fetch_http_error, debugInfo.responseCode, debugInfo.latencyMs)
                                } else {
                                    val codeMatch = Regex("""(?i)(HTTP\s*\d{3}|code[:\s]+[a-zA-Z0-9_]+)""").find(fetchState.message)
                                    if (codeMatch != null) codeMatch.value
                                    else fetchState.message.lines().firstOrNull()?.let { if (it.length > 28) it.take(28) + "..." else it } ?: stringResource(R.string.common_error)
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (debugInfo != null) Modifier.clickable { showDebugSheet = true } else Modifier
                                        )
                                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.AlertCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(
                                        text = displayMsg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    if (debugInfo != null) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = FeatherIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is FetchState.Success -> {
                    val rawModels = fetchState.models.filter { it !in existingModels && it.contains(searchQuery, ignoreCase = true) }
                    val newModels = if (rawModels.isEmpty() && isOnboarding) {
                        listOf("deepseek-v4-flash").filter { it !in existingModels }
                    } else rawModels
                    if (newModels.isEmpty()) {
                        SettingsGroup {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 360.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.provider_no_matching_models), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        // 按品牌分组，每个分类一个独立卡片。"other" 分组永远在最后，其他按显示名称排序。
                        val grouped = newModels.groupBy { m -> modelBrandKey(m) }
                            .toSortedMap(compareBy<String> { it == "other" }.thenBy { brandDisplayName(context, it) })

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp, max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            grouped.forEach { (brandKey, models) ->
                                item(key = "header_$brandKey") {
                                    SettingsGroupHeader("${brandDisplayName(context, brandKey)} (${models.size})")
                                }
                                item(key = "card_$brandKey") {
                                    SettingsGroup {
                                        models.forEachIndexed { index, m ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            val isFirstTarget = isOnboarding && brandKey == grouped.firstKey() && index == 0
                                            FetchModelRow(
                                                model = m,
                                                metadata = modelMetadata[modelMetadataKey(providerId, m)],
                                                onAdd = {
                                                    onAddModel(m)
                                                    if (isOnboarding) {
                                                        onDismiss()
                                                        onOnboardingModelAdded?.invoke()
                                                    }
                                                },
                                                modifier = if (isFirstTarget) Modifier.onboardingTarget(OnboardingStep.SIMULATE_FETCH_DIALOG) else Modifier
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is FetchState.Idle -> {
                    SettingsGroup {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.provider_please_wait), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDebugSheet && debugInfo != null) {
        ModelTestDetailBottomSheet(
            model = stringResource(R.string.provider_fetch_models),
            result = debugInfo,
            onDismiss = { showDebugSheet = false }
        )
    }

    if (showAddAllConfirm) {
        AlertDialog(
            onDismissRequest = { showAddAllConfirm = false },
            title = { Text(stringResource(R.string.provider_add_all_models_title)) },
            text = {
                Text(stringResource(R.string.provider_add_all_models_confirm, availableNewModels.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAddAllConfirm = false
                        onAddModels(availableNewModels)
                        onDismiss()
                        if (isOnboarding) {
                            onOnboardingModelAdded?.invoke()
                        }
                    }
                ) {
                    Text(stringResource(R.string.provider_add_all_models))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 拉取模型加载骨架屏：模拟品牌标题 + 模型行占位块，避免加载时空白/转圈。 */
@Composable
private fun FetchModelsSkeleton() {
    val block = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        MaterialTheme.semanticColors.subtleBorder
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(3) {
            SkeletonBlock(
                width = 80.dp,
                height = 14.dp,
                color = block,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
            SettingsGroup {
                repeat(3) { idx ->
                    if (idx > 0) {
                        SettingsDivider()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SkeletonBlock(width = 24.dp, height = 24.dp, color = block, shape = RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SkeletonBlock(width = 120.dp, height = 14.dp, color = block)
                            SkeletonBlock(width = 80.dp, height = 10.dp, color = block)
                        }
                        SkeletonBlock(width = 48.dp, height = 24.dp, color = block, shape = RoundedCornerShape(50))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    color: Color,
    shape: Shape = RoundedCornerShape(4.dp),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(color)
    )
}

