package com.aharou.feature.settings.presentation.component

import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.AlertDialog
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.BatchDeleteConfirmDialog
import com.aharou.core.ui.SelectionAction
import com.aharou.core.ui.SelectionActionBar
import com.aharou.core.ui.rememberListSelectionState
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
import com.aharou.core.util.ProviderIconStore
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
fun ProviderEditorScreen(
    viewModel: SettingsViewModel,
    initialProvider: AIProviderConfig?,
    onNavigateBack: () -> Unit,
    onSave: (AIProviderConfig) -> Unit,
    /**
     * 从预设库新建时预填的名称/类型/Base URL/模型列表；
     * 仅当 [initialProvider] 为 null（新建场景）时生效。
     */
    presetPrefill: ProviderPreset? = null,
    initialTab: Int = 0,
    onboardingStep: OnboardingStep? = null,
    onOnboardingModelAdded: (() -> Unit)? = null,
    onOnboardingDismissFetchDialog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val presetType by remember {
        mutableStateOf(presetPrefill?.type?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() })
    }
    var name by remember { mutableStateOf(initialProvider?.name ?: presetPrefill?.name ?: "") }
    var apiKey by remember { mutableStateOf(initialProvider?.apiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var multiKeyEnabled by remember { mutableStateOf(initialProvider?.multiKeyEnabled ?: false) }
    val apiKeys = remember { mutableStateListOf<ProviderKey>().apply { addAll(initialProvider?.apiKeys ?: emptyList()) } }
    var keyRotationStrategy by remember { mutableStateOf(initialProvider?.keyRotationStrategy ?: KeyRotationStrategy.SEQUENTIAL) }
    var keyCooldownMinutes by remember { mutableIntStateOf(initialProvider?.keyCooldownMinutes ?: 5) }
    var keySwitchStatusCodes by remember {
        mutableStateOf(initialProvider?.keySwitchStatusCodes?.joinToString(",") ?: "")
    }
    var baseUrl by remember { mutableStateOf(initialProvider?.baseUrl ?: presetPrefill?.baseUrl ?: "") }
    var useFullUrl by remember { mutableStateOf(initialProvider?.useFullUrl ?: false) }
    var useResponseApi by remember { mutableStateOf(initialProvider?.useResponseApi ?: presetPrefill?.useResponseApi ?: false) }
    var anthropicCacheBreakpoints by remember { mutableStateOf(initialProvider?.anthropicCacheBreakpoints ?: true) }
    var openaiChatCacheKey by remember { mutableStateOf(initialProvider?.openaiChatCacheKey ?: false) }
    var dashboardScriptPath by remember { mutableStateOf(initialProvider?.dashboardScriptPath ?: "") }
    val scriptParams = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initialProvider?.scriptParams?.toList() ?: emptyList())
        }
    }
    val customHeaders = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll((initialProvider?.customHeaders ?: presetPrefill?.customHeaders ?: emptyMap()).toList())
        }
    }
    var proxyEnabled by remember { mutableStateOf(initialProvider?.proxyEnabled ?: false) }
    var proxyType by remember { mutableStateOf(initialProvider?.proxyType ?: ProxyType.HTTP) }
    var proxyHost by remember { mutableStateOf(initialProvider?.proxyHost ?: "") }
    var proxyPort by remember { mutableIntStateOf(initialProvider?.proxyPort ?: 0) }
    var proxyUsername by remember { mutableStateOf(initialProvider?.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(initialProvider?.proxyPassword ?: "") }
    var isEnabled by remember { mutableStateOf(initialProvider?.isEnabled ?: true) }
    var type by remember { mutableStateOf(initialProvider?.type ?: presetType ?: ProviderType.OPENAI) }
    val providerId = remember { initialProvider?.id ?: System.currentTimeMillis().toString() }
    // 自定义图标：相册导入后存本地绝对路径，也可以直接是图片链接。
    var customIcon by remember { mutableStateOf(initialProvider?.customIcon ?: "") }
    // 回退供应商：本轮请求失败时切到它继续，空串表示不启用。
    var fallbackProviderId by remember { mutableStateOf(initialProvider?.fallbackProviderId ?: "") }
    var fallbackModel by remember { mutableStateOf(initialProvider?.fallbackModel ?: "") }
    var showFallbackSheet by remember { mutableStateOf(false) }
    var showIconDialog by remember { mutableStateOf(false) }
    val models = remember {
        mutableStateListOf<String>().apply {
            addAll(initialProvider?.models ?: presetPrefill?.models ?: emptyList())
        }
    }
    val customMetadataStore = remember { CustomModelMetadataStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var customMetadata by remember { mutableStateOf<Map<String, ModelMetadata>>(emptyMap()) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(initialPage = initialTab) { 2 }
    LaunchedEffect(initialTab) {
        if (pagerState.currentPage != initialTab) {
            pagerState.animateScrollToPage(initialTab)
        }
    }
    var showTypeSheet by remember { mutableStateOf(false) }
    var showAddModelSheet by remember { mutableStateOf(false) }
    var showFetchDialog by remember { mutableStateOf(false) }
    var showScriptPickerSheet by remember { mutableStateOf(false) }
    // 从手机导入脚本：面板分享包（.json）会自动带上参数，普通脚本文件照旧拷进 ~/.aharou/scripts/。
    val scriptImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val displayName = PanelScriptImporter.queryDisplayName(context, uri)
                if (PanelScriptImporter.maybePanelPackage(displayName)) {
                    val pkg = PanelScriptImporter.parsePackage(context, uri)
                    if (pkg != null) {
                        PanelScriptImporter.writePackageScript(context, pkg)?.let { saved ->
                            if (pkg.params.isNotEmpty()) {
                                scriptParams.clear()
                                scriptParams.addAll(pkg.params.toList())
                            }
                            dashboardScriptPath = saved
                            viewModel.loadDashboardScripts()
                            Toast.makeText(
                                context,
                                context.getString(R.string.provider_dashboard_package_imported, saved),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                }
                val name = PanelScriptImporter.import(context, uri)
                if (name != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.share_script_imported, name),
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.loadDashboardScripts()
                }
            }
        }
    }
    var showProxyPage by remember { mutableStateOf(false) }
    var showKeysPage by remember { mutableStateOf(false) }
    var showHeadersSheet by remember { mutableStateOf(false) }
    var showScriptParamsSheet by remember { mutableStateOf(false) }
    var fetchDialogKey by remember { mutableIntStateOf(0) }

    // 两个 tab 的滚动状态提升到页面层，聚合出「是否正在滚动」供底部 tab栏滚动弱化（同 Git 页面）。
    val configScrollState = rememberScrollState()
    val modelsListState = rememberLazyListState()
    var modelsOrderDirty by remember { mutableStateOf(false) }
    val tabsScrolling by remember {
        derivedStateOf {
            configScrollState.isScrollInProgress || modelsListState.isScrollInProgress
        }
    }

    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    val autoRemoveStaleModels by viewModel.autoRemoveStaleModels.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val allProviders by viewModel.providers.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val proxyTestState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    val dashboardTestState by viewModel.dashboardTestState.collectAsStateWithLifecycle()
    val dashboardScripts by viewModel.dashboardScripts.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val modelIdSet by remember {
        derivedStateOf { models.toSet() }
    }

    DisposableEffect(Unit) {
        viewModel.resetFetchState()
        viewModel.clearTestResults()
        viewModel.clearDashboardTestState()
        onDispose {
            viewModel.resetFetchState()
            viewModel.clearTestResults()
            viewModel.clearDashboardTestState()
        }
    }

    LaunchedEffect(type, modelIdSet) {
        viewModel.resolveModelMetadata(providerId, type, models.toList())
    }

    LaunchedEffect(onboardingStep) {
        if (onboardingStep == OnboardingStep.SIMULATE_FETCH_DIALOG) {
            fetchDialogKey++
            showFetchDialog = true
        }
    }

    LaunchedEffect(providerId) {
        customMetadata = customMetadataStore.all()
    }

    fun currentConfig() = AIProviderConfig(
        id = providerId,
        name = name.ifEmpty { context.getString(R.string.provider_new) },
        type = type,
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys.toList(),
        keyRotationStrategy = keyRotationStrategy,
        keyCooldownMinutes = keyCooldownMinutes,
        keySwitchStatusCodes = keySwitchStatusCodes.split(",").mapNotNull { it.trim().toIntOrNull() },
        baseUrl = baseUrl.ifBlank { defaultProviderBaseUrl(type) },
        useFullUrl = useFullUrl,
        isEnabled = isEnabled,
        defaultModel = initialProvider?.defaultModel ?: "",
        models = models.toList(),
        selectedModel = initialProvider?.selectedModel ?: "",
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints,
        openaiChatCacheKey = openaiChatCacheKey,
        dashboardScriptPath = dashboardScriptPath,
        dashboardRefreshInterval = initialProvider?.dashboardRefreshInterval ?: 5,
        customHeaders = customHeaders.filter { it.first.isNotBlank() }.toMap(),
        sortOrder = initialProvider?.sortOrder ?: -1,
        proxyEnabled = proxyEnabled,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUsername = proxyUsername,
        proxyPassword = proxyPassword,
        scriptParams = scriptParams.filter { it.first.isNotBlank() }.toMap(),
        customIcon = customIcon,
        fallbackProviderId = fallbackProviderId,
        fallbackModel = fallbackModel
    ).sanitized()

    // 新建场景下判断用户是否填写了实质内容：名称、API Key、Base URL 任一非空白，或已添加模型 / DIY 脚本参数。
    // 全空白时退出编辑页（防止新建一个名为「默认供应商」的空记录）。
    fun hasSubstantiveInput(): Boolean =
        initialProvider != null ||
            name.isNotBlank() ||
            apiKey.isNotBlank() ||
            apiKeys.any { it.value.isNotBlank() } ||
            baseUrl.isNotBlank() ||
            dashboardScriptPath.isNotBlank() ||
            scriptParams.any { it.first.isNotBlank() } ||
            customHeaders.any { it.first.isNotBlank() } ||
            models.isNotEmpty()

    // 与上次提交内容完全相同则跳过保存（改了又改回原值、或原样返回都不应触发 DB 写入）；编辑场景以进入时配置为基线，新建场景为 null 保证首次填写必存。
    var lastSubmittedConfig by remember {
        mutableStateOf(if (initialProvider != null) currentConfig() else null)
    }

    fun saveCurrent() {
        if (!hasSubstantiveInput()) return
        val config = currentConfig()
        if (config == lastSubmittedConfig) return
        onSave(config)
        lastSubmittedConfig = config
    }

    fun applyIcon(newIcon: String) {
        customIcon = newIcon
        saveCurrent()
    }

    val iconPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            ProviderIconStore.import(context, providerId, uri)?.let { applyIcon(it) }
        }
    }

    // 拉取成功后自动对齐：远端已不存在的本地模型直接移除（可在「偏好设置」关掉）。拉取失败或返回空列表时不动列表。
    LaunchedEffect(fetchState, showFetchDialog, autoRemoveStaleModels) {
        if (!autoRemoveStaleModels) return@LaunchedEffect
        val state = fetchState
        if (!showFetchDialog || state !is FetchState.Success) return@LaunchedEffect
        val remote = state.models.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (remote.isEmpty()) return@LaunchedEffect
        val stale = models.filterNot { it in remote }
        if (stale.isNotEmpty()) {
            models.removeAll(stale)
            saveCurrent()
            Toast.makeText(
                context,
                context.getString(R.string.provider_models_aligned_removed, stale.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val modelsReorderableState = rememberReorderableLazyListState(modelsListState) { from, to ->
        if (from.index !in models.indices || to.index !in models.indices || from.index == to.index) return@rememberReorderableLazyListState
        models.add(to.index, models.removeAt(from.index))
        modelsOrderDirty = true
    }
    val hapticFeedback = LocalHapticFeedback.current

    // 模型列表多选：与其余设置列表共用同一套选中状态（选中集合 + 多选开关）
    val modelSelection = rememberListSelectionState()
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 拉取/自动对齐会整体换列表，丢掉已不存在的模型，否则「已选 N 项」与可见行数对不上
    LaunchedEffect(models.toList()) { modelSelection.retain(models) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0 && modelSelection.selectionMode) modelSelection.exit()
    }

    fun saveAndNavigateBack() {
        modelsOrderDirty = false
        saveCurrent()
        onNavigateBack()
    }

    if (showProxyPage || showKeysPage) {
        BackHandler {
            showProxyPage = false
            showKeysPage = false
        }
    } else if (modelSelection.selectionMode) {
        BackHandler { modelSelection.exit() }
    } else {
        BackHandler { saveAndNavigateBack() }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(if (initialProvider == null) stringResource(R.string.provider_add) else stringResource(R.string.provider_edit)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (modelSelection.selectionMode) modelSelection.exit() else saveAndNavigateBack()
                    }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (pagerState.currentPage == 1 && models.isNotEmpty()) {
                        IconButton(onClick = {
                            if (modelSelection.selectionMode) modelSelection.exit() else modelSelection.enterSelectionMode()
                        }) {
                            Icon(
                                FeatherIcons.CheckSquare,
                                contentDescription = stringResource(R.string.provider_models_select),
                                tint = if (modelSelection.selectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                        showAddModelSheet = true
                    }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.provider_add_model))
                    }
                }
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                if (tab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(configScrollState)
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = 70.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                    // ── 基本信息 ──
                    ProviderBasicGroup(
                        name = name,
                        onNameChange = { name = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        apiKeyVisible = apiKeyVisible,
                        onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                        multiKeyEnabled = multiKeyEnabled,
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        headers = customHeaders,
                        onOpenHeaders = { showHeadersSheet = true },
                        typeLabel = providerTypeLabel(type),
                        onOpenType = { showTypeSheet = true },
                    )

                    // ── 选项 ──
                    SettingsGroupHeader(text = stringResource(R.string.provider_section_options))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.provider_custom_icon),
                            subtitle = stringResource(R.string.provider_custom_icon_hint),
                            onClick = { showIconDialog = true }
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.provider_fallback_title),
                            subtitle = fallbackProviderId
                                .takeIf { it.isNotBlank() }
                                ?.let { id ->
                                    val name = allProviders.firstOrNull { it.id == id }?.name ?: id
                                    if (fallbackModel.isNotBlank()) "$name · $fallbackModel" else name
                                }
                                ?: stringResource(R.string.provider_fallback_hint),
                            onClick = { showFallbackSheet = true }
                        )
                        SettingsDivider()
                        ProviderSwitchRow(
                            title = stringResource(R.string.common_enabled),
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it }
                        )
                        SettingsDivider()
                        ProviderSwitchRow(
                            title = stringResource(R.string.provider_full_url),
                            subtitle = stringResource(R.string.provider_full_url_desc),
                            checked = useFullUrl,
                            onCheckedChange = { useFullUrl = it }
                        )
                        // 新版端点开关：OpenAI 是 Responses API，Gemini 是 Interactions API，
                        // 共用 useResponseApi 一个字段（语义一致：切到该家的新版统一端点）。
                        if (type == ProviderType.OPENAI || type == ProviderType.GEMINI) {
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(
                                    if (type == ProviderType.GEMINI) R.string.provider_interactions_api
                                    else R.string.provider_response_api
                                ),
                                checked = useResponseApi,
                                onCheckedChange = { useResponseApi = it }
                            )
                        }
                        if (type == ProviderType.OPENAI) {
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(R.string.provider_cache_openai_chat_title),
                                subtitle = stringResource(R.string.provider_cache_openai_chat_subtitle),
                                checked = openaiChatCacheKey,
                                onCheckedChange = { openaiChatCacheKey = it }
                            )
                        }
                        if (type == ProviderType.ANTHROPIC) {
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(R.string.provider_cache_anthropic_title),
                                subtitle = stringResource(R.string.provider_cache_anthropic_subtitle),
                                checked = anthropicCacheBreakpoints,
                                onCheckedChange = { anthropicCacheBreakpoints = it }
                            )
                        }
                        SettingsDivider()
                        ProviderSwitchRow(
                            title = stringResource(R.string.provider_multi_key_title),
                            subtitle = stringResource(R.string.provider_multi_key_subtitle),
                            checked = multiKeyEnabled,
                            onCheckedChange = { enabled ->
                                multiKeyEnabled = enabled
                                // 开启时把已填的单 Key 带进列表，避免输入框隐藏后看起来「刚填的 Key 丢了」。
                                if (enabled && apiKeys.isEmpty() && apiKey.isNotBlank()) apiKeys.add(ProviderKey(apiKey))
                            }
                        )
                        if (multiKeyEnabled) {
                            SettingsDivider()
                            SettingsRow(
                                icon = null,
                                title = stringResource(R.string.provider_multi_key_manage),
                                onClick = { showKeysPage = true },
                                trailing = {
                                    Text(
                                        text = stringResource(
                                            R.string.provider_multi_key_summary,
                                            apiKeys.count { it.value.isNotBlank() },
                                            keyRotationStrategyLabel(keyRotationStrategy)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                        SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.proxy_title),
                            onClick = { showProxyPage = true },
                            trailing = {
                                Text(
                                    text = if (proxyEnabled) {
                                        "${proxyTypeLabel(proxyType)} ${proxyHost}:${proxyPort}"
                                    } else {
                                        stringResource(R.string.provider_proxy_off)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }

                    // ── 自定义面板 (DIY) ──
                    ProviderDashboardGroup(
                        scriptPath = dashboardScriptPath,
                        onScriptPathChange = { dashboardScriptPath = it },
                        scriptParams = scriptParams,
                        testState = dashboardTestState,
                        providerName = name.ifBlank { stringResource(R.string.provider_dashboard_preview_title) },
                        onPickScript = {
                            showScriptPickerSheet = true
                            viewModel.loadDashboardScripts()
                        },
                        onRunTest = {
                            focusManager.clearFocus()
                            viewModel.testDashboardScript(currentConfig(), dashboardScriptPath)
                        },
                        onOpenParams = { showScriptParamsSheet = true },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg)
                ) {
                    if (modelSelection.selectionMode) {
                        SelectionActionBar(
                            selectedCount = modelSelection.selectedCount,
                            allSelected = modelSelection.isAllSelected(models),
                            onToggleSelectAll = { modelSelection.toggleSelectAll(models) },
                            onExit = { modelSelection.exit() },
                            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
                            actions = listOf(
                                SelectionAction(
                                    icon = FeatherIcons.Play,
                                    label = stringResource(R.string.provider_models_batch_test),
                                    onClick = {
                                        modelSelection.selectedIds.forEach { m ->
                                            viewModel.testModel(currentConfig(), m)
                                        }
                                    }
                                ),
                                SelectionAction(
                                    icon = FeatherIcons.Trash2,
                                    label = stringResource(R.string.provider_models_batch_delete),
                                    onClick = { showBatchDeleteConfirm = true },
                                    destructive = true
                                )
                            )
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.provider_models_count, models.size),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (models.isNotEmpty()) {
                                TextButton(
                                    onClick = { modelSelection.enterSelectionMode() },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(FeatherIcons.CheckSquare, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(stringResource(R.string.provider_models_select))
                                }
                            }
                            TextButton(
                                onClick = {
                                    fetchDialogKey++
                                    showFetchDialog = true
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_FETCH_MODELS)
                            ) {
                                Icon(FeatherIcons.DownloadCloud, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text(stringResource(R.string.provider_fetch_models))
                            }
                        }
                    }
                    if (models.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Text(
                                    text = stringResource(R.string.provider_no_models),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.provider_no_models_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = modelsListState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 70.dp)
                        ) {
                            itemsIndexed(
                                items = models,
                                key = { _, model -> model }
                            ) { index, model ->
                                ReorderableItem(
                                    state = modelsReorderableState,
                                    key = model
                                ) { isDragging ->
                                    val isFirst = index == 0
                                    val isLast = index == models.lastIndex
                                    val shape = if (isDragging) {
                                        RoundedCornerShape(Radius.lg)
                                    } else {
                                        RoundedCornerShape(
                                            topStart = if (isFirst) Radius.lg else 0.dp,
                                            topEnd = if (isFirst) Radius.lg else 0.dp,
                                            bottomStart = if (isLast) Radius.lg else 0.dp,
                                            bottomEnd = if (isLast) Radius.lg else 0.dp
                                        )
                                    }
                                    val dragScale by animateFloatAsState(
                                        targetValue = if (isDragging) 0.95f else 1f,
                                        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                                        label = "modelDragScale"
                                    )
                                    val dragElevation by animateDpAsState(
                                        targetValue = if (isDragging) 8.dp else 0.dp,
                                        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                                        label = "modelDragElevation"
                                    )
                                    Surface(
                                        shape = shape,
                                        color = MaterialTheme.semanticColors.cardSurface,
                                        shadowElevation = dragElevation,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                scaleX = dragScale
                                                scaleY = dragScale
                                            }
                                    ) {
                                        ProviderModelRow(
                                            model = model,
                                            metadata = mergeModelMetadata(model, modelMetadata[modelMetadataKey(providerId, model)], customMetadata[modelMetadataKey(providerId, model)]),
                                            testing = model in testing,
                                            result = testResults[model],
                                            onTest = { viewModel.testModel(currentConfig(), model) },
                                            onEdit = {
                                                editingModel = model
                                                showAddModelSheet = true
                                            },
                                            onRemove = {
                                                models.remove(model)
                                                scope.launch {
                                                    customMetadataStore.remove(providerId, model)
                                                    customMetadata = customMetadataStore.all()
                                                }
                                                saveCurrent()
                                            },
                                            showDivider = !isLast && !isDragging,
                                            selectionMode = modelSelection.selectionMode,
                                            selected = modelSelection.isSelected(model),
                                            onToggleSelect = { modelSelection.toggle(model) },
                                            dragModifier = if (modelSelection.selectionMode) Modifier else Modifier.longPressDraggableHandle(
                                                onDragStarted = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                    if (modelsOrderDirty) {
                                                        modelsOrderDirty = false
                                                        saveCurrent()
                                                    }
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.provider_config)),
                    FloatingTabItem(FeatherIcons.Cpu, stringResource(R.string.common_model))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showTypeSheet) {
        ProviderTypeSelectionSheet(
            selected = type,
            onSelected = { type = it },
            onDismiss = { showTypeSheet = false }
        )
    }

    if (showProxyPage) {
        ProviderProxyPage(
            config = ProxyConfig(
                enabled = proxyEnabled,
                type = proxyType,
                host = proxyHost,
                port = proxyPort,
                username = proxyUsername,
                password = proxyPassword
            ),
            testState = proxyTestState,
            onBack = { showProxyPage = false },
            onSetEnabled = { proxyEnabled = it },
            onSetType = { proxyType = it },
            onSetHost = { proxyHost = it },
            onSetPort = { proxyPort = it },
            onSetUsername = { proxyUsername = it },
            onSetPassword = { proxyPassword = it },
            onTestProxy = { url ->
                viewModel.testProxy(
                    ProxyConfig(
                        enabled = true,
                        type = proxyType,
                        host = proxyHost,
                        port = proxyPort,
                        username = proxyUsername,
                        password = proxyPassword
                    ),
                    url
                )
            }
        )
    }

    if (showKeysPage) {
        ProviderKeysPage(
            keys = apiKeys,
            strategy = keyRotationStrategy,
            cooldownMinutes = keyCooldownMinutes,
            switchStatusCodes = keySwitchStatusCodes,
            onBack = { showKeysPage = false },
            onSetStrategy = { keyRotationStrategy = it },
            onSetCooldownMinutes = { keyCooldownMinutes = it },
            onSetSwitchStatusCodes = { keySwitchStatusCodes = it }
        )
    }

    if (showScriptPickerSheet) {
        ScriptPickerBottomSheet(
            scripts = dashboardScripts,
            onImportFromFile = { scriptImportLauncher.launch(arrayOf("*/*")) },
            onSelect = { selectedScript ->
                dashboardScriptPath = selectedScript
                showScriptPickerSheet = false
            },
            onDelete = { scriptName ->
                viewModel.deleteDashboardScript(scriptName)
                viewModel.loadDashboardScripts()
            },
            onShare = { scriptName ->
                scope.launch {
                    val params = scriptParams.filter { it.first.isNotBlank() }.toMap()
                    val uri = PanelScriptImporter.exportPackage(context, scriptName, params)
                    if (uri == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.provider_dashboard_share_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            setType("application/json")
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.provider_dashboard_share_script))
                        )
                    }
                }
            },
            onDismiss = { showScriptPickerSheet = false }
        )
    }

    if (showHeadersSheet) {
        ProviderHeadersSheet(
            headers = customHeaders,
            onDismiss = { showHeadersSheet = false }
        )
    }

    if (showScriptParamsSheet) {
        ProviderScriptParamsSheet(
            params = scriptParams,
            onDismiss = { showScriptParamsSheet = false }
        )
    }

    if (showAddModelSheet) {
        key(editingModel) {
            AddModelSheet(
                existingModels = models,
                title = if (editingModel != null) {
                    stringResource(R.string.provider_edit_model)
                } else {
                    stringResource(R.string.provider_add_model)
                },
                confirmLabel = if (editingModel != null) {
                    stringResource(R.string.common_save)
                } else {
                    stringResource(R.string.common_add)
                },
                initial = editingModel?.let { mergeModelMetadata(it, modelMetadata[modelMetadataKey(providerId, it)], customMetadata[modelMetadataKey(providerId, it)]) },
                providerId = providerId,
                onSave = { model, meta ->
                    val editing = editingModel
                    if (editing != null && model != editing) {
                        val idx = models.indexOf(editing)
                        if (idx >= 0) models[idx] = model else models.add(model)
                    } else if (model !in models) {
                        models.add(model)
                    }
                    scope.launch {
                        if (editing != null && model != editing) {
                            customMetadataStore.remove(providerId, editing)
                        }
                        customMetadataStore.put(providerId, model, meta)
                        customMetadata = customMetadataStore.all()
                    }
                    saveCurrent()
                    editingModel = null
                    showAddModelSheet = false
                },
                onDismiss = {
                    editingModel = null
                    showAddModelSheet = false
                }
            )
        }
    }

    if (showBatchDeleteConfirm) {
        val toDelete = modelSelection.selectedIds.toList()
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.provider_models_batch_delete_title),
            message = stringResource(R.string.provider_models_batch_delete_confirm, toDelete.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                models.removeAll(toDelete.toSet())
                modelSelection.exit()
                scope.launch {
                    toDelete.forEach { m -> customMetadataStore.remove(providerId, m) }
                    customMetadata = customMetadataStore.all()
                }
                saveCurrent()
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }

    // 模型拉取结果弹窗
    if (showFetchDialog) {
        key(fetchDialogKey) {
            FetchModelsDialog(
                fetchState = fetchState,
                providerId = providerId,
                modelMetadata = modelMetadata,
                existingModels = models,
                isOnboarding = onboardingStep == OnboardingStep.SIMULATE_FETCH_DIALOG,
                onFetchModels = { viewModel.fetchModels(currentConfig()) },
                onAddModel = { m ->
                    if (m !in models) {
                        models.add(m)
                        saveCurrent()
                    }
                },
                onAddModels = { list ->
                    val toAdd = list.filter { it !in models }
                    if (toAdd.isNotEmpty()) {
                        models.addAll(toAdd)
                        saveCurrent()
                    }
                },
                onDismiss = {
                    showFetchDialog = false
                    viewModel.resetFetchState()
                },
                onOnboardingModelAdded = onOnboardingModelAdded,
                onOnboardingDismissFetchDialog = onOnboardingDismissFetchDialog
            )
        }
    }

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
                applyIcon(link)
                showIconDialog = false
            },
            onReset = {
                ProviderIconStore.delete(context, providerId)
                applyIcon("")
                showIconDialog = false
            },
            onDismiss = { showIconDialog = false }
        )
    }

    if (showFallbackSheet) {
        ProviderFallbackSheet(
            providers = allProviders.filter { it.id != providerId },
            selectedProviderId = fallbackProviderId,
            onSelect = { id ->
                fallbackProviderId = id
                // 换了回退供应商后旧的回退模型可能已不存在，清空让用户重新选，避免静默用错模型
                fallbackModel = ""
                saveCurrent()
                showFallbackSheet = false
            },
            onDismiss = { showFallbackSheet = false }
        )
    }
}

/** 自定义供应商图标：相册选图 / 填图片链接 / 恢复默认。 */
@Composable
internal fun ProviderIconDialog(
    currentIcon: String,
    onPickGallery: () -> Unit,
    onConfirmLink: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var link by remember(currentIcon) {
        mutableStateOf(if (currentIcon.startsWith("http")) currentIcon else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_custom_icon)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.provider_custom_icon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onPickGallery, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.provider_custom_icon_pick))
                }
                AppTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = stringResource(R.string.provider_custom_icon_url)
                )
                if (currentIcon.isNotBlank()) {
                    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.provider_custom_icon_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirmLink(link.trim()) }, enabled = link.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

internal fun defaultProviderBaseUrl(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "https://api.anthropic.com/"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/"
    else -> "https://api.openai.com/"
}

/** 分组内输入行：全宽 AppTextField，可选密文转换与尾随操作。 */
@Composable
internal fun ProviderTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = if (placeholder.isNotBlank()) placeholder else null,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

/** 分组内开关行：标题 + 可选副标题 + 右侧 Switch。 */
@Composable
private fun ProviderSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 提供商类型选择底部弹窗，样式与主题选择弹窗一致。 */

internal fun providerTypeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> "OpenAI"
    ProviderType.ANTHROPIC -> "Anthropic"
    ProviderType.GEMINI -> "Gemini"
}

@Composable
internal fun keyRotationStrategyLabel(strategy: KeyRotationStrategy): String = stringResource(
    when (strategy) {
        KeyRotationStrategy.SEQUENTIAL -> R.string.provider_multi_key_strategy_sequential
        KeyRotationStrategy.ROUND_ROBIN -> R.string.provider_multi_key_strategy_round_robin
    }
)

/**
 * 多 Key 管理子页：Key 列表增删 + 取用策略 + 切换状态码 + 冷却时长。
 * 与代理页同一模式——就地渲染的内嵌页，改动写回编辑器状态，随提供商一起保存。
 */
