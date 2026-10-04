package com.aharou.feature.settings.presentation.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.pageEnter
import com.aharou.core.ui.pageExit
import com.aharou.core.util.LogLevel
import com.aharou.R
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.prompt.PromptFragment
import com.aharou.feature.agent.domain.prompt.PromptFragmentSource
import com.aharou.feature.agent.presentation.component.MarkdownContent
import com.aharou.feature.agent.presentation.component.MarkdownRenderCache
import com.aharou.feature.backup.presentation.BackupSection
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.presentation.PromptsViewModel
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.settings.presentation.ShizukuViewModel
import com.aharou.feature.settings.presentation.SkillExportState
import com.aharou.feature.settings.presentation.SkillImportState
import com.aharou.feature.settings.presentation.SkillUiEntry
import com.aharou.feature.agent.domain.skill.SkillImportError
import com.aharou.feature.agent.domain.skill.SkillMoveResult
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Smile
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Download
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Info
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Trash2
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.terminal.presentation.component.TerminalSettingsSheet

/** 使用手册在线文档站地址。 */
private const val USER_GUIDE_DOCS_URL = "https://github.com/520huxiangli/Aharou"

/** 大屏设置页左侧常驻菜单宽度。 */
private val SettingsMenuWidth = 320.dp

/**
 * 设置页走双栏所需的**自身**最小宽度。
 *
 * 不能直接拿窗口宽度判定：大屏下设置页左边还有常驻会话侧栏，按窗口算的话
 * 小平板（约 840dp）上扇出三栏，详情区只剩 200dp 不到，根本没法用。
 */
private val SettingsTwoPaneMinWidth = 840.dp

/** 设置页内部二级菜单分区。Menu 为首页菜单，其余为各自的二级页。 */
internal enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    Soul(R.string.soul_settings_title),
    ConfigAudit(R.string.config_audit_title),
    ShadowScreen(R.string.vd_settings_title),
    EnvVars(R.string.envvars_title),
    Accessibility(R.string.a11y_settings_title),
    Pet(R.string.pet_title),
    Memory(R.string.memory_settings_title),
    General(R.string.settings_general),
    Providers(R.string.settings_providers),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    Mcp(R.string.settings_mcp),
    Skills(R.string.settings_skills),
    SkillDetail(R.string.settings_skills),
    SkillEditor(R.string.settings_skills),
    SkillMarket(R.string.skills_market),
    // 详情页与市场页共用一个标题：层级靠返回箭头体现
    SkillMarketDetail(R.string.skills_market),
    SubAgents(R.string.settings_subagents),
    SubAgentDetail(R.string.settings_subagents),
    SubAgentEditor(R.string.settings_subagents),
    Prompts(R.string.prompts_title),
    PromptDetail(R.string.prompts_title),
    PromptEditor(R.string.prompts_title),
    Container(R.string.settings_container),
    ContainerDownloads(R.string.container_download_image),
    Proxy(R.string.proxy_title),
    Log(R.string.settings_log),
    Permissions(R.string.settings_permissions),
    AppPermissions(R.string.settings_app_permissions),
    BackgroundRun(R.string.settings_category_background),
    RemoteServers(R.string.settings_remote_servers),
    Storage(R.string.settings_storage),
    TokenStats(R.string.settings_token_stats_title),
    Backup(R.string.settings_backup),
    About(R.string.settings_about),
    AharouGroup(R.string.settings_category_aharou),
    GeneralGroup(R.string.settings_category_general),
    AiGroup(R.string.settings_category_ai),
    PermissionsGroup(R.string.settings_category_permissions),
    MoreGroup(R.string.settings_category_more),
    HelpGroup(R.string.settings_category_help)
}

/**
 * 分区层级：0 首页菜单，1 一级分区，2 分区里再翻一层的详情 / 编辑页，3 详情页之上的编辑页。
 *
 * 只用于判定切换方向（变浅算返回）。[SettingsSection.Log] 的上一层是动态的（见 logReturnSection），
 * 统一当二级算：从菜单直接进它仍是 0 → 2 的「前进」，方向不会反。
 */
private fun SettingsSection.depth(): Int = when (this) {
    SettingsSection.Menu -> 0
    // 技能/子代理编辑页既可从列表(1) 进也可从详情页(2) 进，必须比详情页更深：
    // 同深度会让「编辑 → 详情」也被当成前进，返回时页面从右侧滑入，方向是反的。
    SettingsSection.SkillEditor,
    SettingsSection.SubAgentEditor,
    SettingsSection.PromptEditor -> 3
    SettingsSection.ProviderEditor,
    SettingsSection.SkillDetail,
    SettingsSection.SkillMarketDetail,
    SettingsSection.SubAgentDetail,
    SettingsSection.PromptDetail,
    SettingsSection.ContainerDownloads,
    SettingsSection.Log -> 2
    else -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit = {},
    onboardingStep: OnboardingStep? = null,
    onOnboardingModelAdded: (() -> Unit)? = null,
    onOnboardingDismissFetchDialog: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logViewerState by viewModel.logViewerState.collectAsStateWithLifecycle()
    val mcpEntries by viewModel.mcpEntries.collectAsStateWithLifecycle()
    val mcpStatuses by viewModel.mcpStatuses.collectAsStateWithLifecycle()
    val mcpReloading by viewModel.mcpReloading.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val skillSaveState by viewModel.skillSaveState.collectAsStateWithLifecycle()
    val skillImportState by viewModel.skillImportState.collectAsStateWithLifecycle()
    val skillDeleting by viewModel.skillDeleting.collectAsStateWithLifecycle()
    val subAgents by viewModel.subAgents.collectAsStateWithLifecycle()
    val subAgentSaveState by viewModel.subAgentSaveState.collectAsStateWithLifecycle()
    val subAgentDeleting by viewModel.subAgentDeleting.collectAsStateWithLifecycle()
    val globalRules by viewModel.globalRules.collectAsStateWithLifecycle()
    val projectRules by viewModel.projectRules.collectAsStateWithLifecycle()
    val currentProjectName by viewModel.currentProjectName.collectAsStateWithLifecycle()
    val disableSafetyInterception by viewModel.disableSafetyInterception.collectAsStateWithLifecycle()
    val keepaliveEnabled by viewModel.keepaliveEnabled.collectAsStateWithLifecycle()
    val screenOnEnabled by viewModel.screenOnEnabled.collectAsStateWithLifecycle()
    val agentSoundEnabled by viewModel.agentSoundEnabled.collectAsStateWithLifecycle()
    val autoRemoveStaleModels by viewModel.autoRemoveStaleModels.collectAsStateWithLifecycle()
    val startupSessionMode by viewModel.startupSessionMode.collectAsStateWithLifecycle()
    val firstByteTimeoutSec by viewModel.firstByteTimeoutSec.collectAsStateWithLifecycle()
    val streamIdleTimeoutSec by viewModel.streamIdleTimeoutSec.collectAsStateWithLifecycle()
    val maxNetworkRetries by viewModel.maxNetworkRetries.collectAsStateWithLifecycle()
    val enterToSend by viewModel.enterToSend.collectAsStateWithLifecycle()
    val compactionThresholdPercent by viewModel.compactionThresholdPercent.collectAsStateWithLifecycle()
    val softCompactionThresholdPercent by viewModel.softCompactionThresholdPercent.collectAsStateWithLifecycle()
    val sendFileMaxSizeMb by viewModel.sendFileMaxSizeMb.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themePresetId by viewModel.themePresetId.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val backgroundImagePath by viewModel.backgroundImagePath.collectAsStateWithLifecycle()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val visionProviderId by viewModel.visionProviderId.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val compactionProviderId by viewModel.compactionProviderId.collectAsStateWithLifecycle()
    val compactionModel by viewModel.compactionModel.collectAsStateWithLifecycle()
    val titleProviderId by viewModel.titleProviderId.collectAsStateWithLifecycle()
    val titleModel by viewModel.titleModel.collectAsStateWithLifecycle()
    val imageGenProviderId by viewModel.imageGenProviderId.collectAsStateWithLifecycle()
    val imageGenModel by viewModel.imageGenModel.collectAsStateWithLifecycle()
    val voiceSttProviderId by viewModel.voiceSttProviderId.collectAsStateWithLifecycle()
    val voiceSttModel by viewModel.voiceSttModel.collectAsStateWithLifecycle()
    val voiceTtsProviderId by viewModel.voiceTtsProviderId.collectAsStateWithLifecycle()
    val voiceTtsModel by viewModel.voiceTtsModel.collectAsStateWithLifecycle()
    val voiceTtsVoice by viewModel.voiceTtsVoice.collectAsStateWithLifecycle()
    val autoReadAloud by viewModel.autoReadAloud.collectAsStateWithLifecycle()
    val voiceWakeEnabled by viewModel.voiceWakeEnabled.collectAsStateWithLifecycle()
    val isDefaultAssistant by viewModel.isDefaultAssistant.collectAsStateWithLifecycle()
    val ocrForTextOnlyModels by viewModel.ocrForTextOnlyModels.collectAsStateWithLifecycle()
    val voiceModelStatus by viewModel.voiceModelStatus.collectAsStateWithLifecycle()
    val voiceModelMessage by viewModel.voiceModelMessage.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val containerProfiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val defaultContainerId by viewModel.defaultContainerId.collectAsStateWithLifecycle()
    val containerOsMap by viewModel.containerOsMap.collectAsStateWithLifecycle()
    val remoteConnections by viewModel.remoteConnections.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStats.collectAsStateWithLifecycle()
    val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsStateWithLifecycle()
    val updateCheckChannel by viewModel.updateCheckChannel.collectAsStateWithLifecycle()
    val autoDownloadEnabled by viewModel.autoDownloadEnabled.collectAsStateWithLifecycle()
    val autoInstallEnabled by viewModel.autoInstallEnabled.collectAsStateWithLifecycle()
    val containerAnnouncementText by viewModel.containerAnnouncementText.collectAsStateWithLifecycle()
    val containerAnnouncementOutdated by viewModel.containerAnnouncementOutdated.collectAsStateWithLifecycle()
    val imageCatalog by viewModel.imageCatalog.collectAsStateWithLifecycle()
    val imageDownload by viewModel.containerImageDownload.collectAsStateWithLifecycle()
    val containerReset by viewModel.containerReset.collectAsStateWithLifecycle()
    val imageSourceOptions by viewModel.imageSourceOptions.collectAsStateWithLifecycle()
    val selectedImageSource by viewModel.selectedImageSource.collectAsStateWithLifecycle()
    val downloadedImages by viewModel.downloadedImages.collectAsStateWithLifecycle()
    val sourceUnavailableIds by viewModel.sourceUnavailableIds.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    val proxyConfig by viewModel.proxyConfig.collectAsStateWithLifecycle()
    val proxyTestState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    var showTerminalSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.aharou.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }


    var section by remember { mutableStateOf(SettingsSection.Menu) }
    // 市场列表的滚动位置：放在这一层，进技能详情页再返回时不会跳回第一条
    val marketListState = rememberLazyListState()
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showAddProviderSheet by remember { mutableStateOf(false) }
    var providerPresetPrefill by remember { mutableStateOf<ProviderPreset?>(null) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerEntry?>(null) }

    LaunchedEffect(onboardingStep) {
        when (onboardingStep) {
            OnboardingStep.CONFIG_PROVIDER -> {
                section = SettingsSection.Menu
            }
            OnboardingStep.PROVIDER_ADD -> {
                section = SettingsSection.Providers
            }
            OnboardingStep.PROVIDER_CONFIG_INFO -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            OnboardingStep.PROVIDER_FETCH_MODELS,
            OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            else -> {}
        }
    }
    var selectedSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    var skillToDelete by remember { mutableStateOf<SkillUiEntry?>(null) }
    // 技能编辑目标：null 表示新建一个；编辑现有技能时指向被编辑的条目。
    var editingSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    // 编辑页的返回目标：从详情页进就回详情页，从列表顶栏「＋」进就回列表。
    var skillEditorReturn by remember { mutableStateOf(SettingsSection.Skills) }
    // 保存后要在详情页展示的技能名：列表刷新是异步的，先记名字等刷新完再换快照。
    var pendingSkillName by remember { mutableStateOf<String?>(null) }
    // 「添加技能」底部弹层：选择作用域后走手动新建 / 文件导入 / 压缩包导入。
    var showSkillAddSheet by remember { mutableStateOf(false) }
    var skillImportScope by remember { mutableStateOf(SkillScope.GLOBAL) }
    // 最近一次发起搬运的目标作用域：结果回来时靠它决定提示文案（详情页与列表长按菜单共用）。
    var skillMoveTarget by remember { mutableStateOf(SkillScope.GLOBAL) }
    // 技能文件 / 压缩包选择器：结果交给 ViewModel 读取并落盘到所选作用域。
    val skillFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillFromMarkdown(uri, skillImportScope)
    }
    val skillZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillsFromZip(uri, skillImportScope)
    }
    var selectedSubAgent by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    var subAgentToDelete by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    // 子代理编辑目标：null 表示新建一个；编辑现有定义时指向被编辑的条目。
    var editingSubAgent by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    // 编辑页的返回目标：从详情页进就回详情页，从列表顶栏「＋」进就回列表。
    var subAgentEditorReturn by remember { mutableStateOf(SettingsSection.SubAgents) }
    // 保存后要在详情页展示的子代理名：列表刷新是异步的，先记名字等刷新完再换快照。
    var pendingSubAgentName by remember { mutableStateOf<String?>(null) }
    // 子代理提示词 Markdown 解析缓存：与技能详情同理，避免重复解析卡顿
    val subAgentMarkdownCache = remember { MarkdownRenderCache() }
    // 技能正文 Markdown 解析缓存：详情页多次进入复用，避免重复解析卡顿
    val skillMarkdownCache = remember { MarkdownRenderCache() }
    // 自定义提示词：右上角「+」弹层可见性 + 编辑目标（新建/编辑）
    var showPromptsAddSheet by remember { mutableStateOf(false) }
    var promptEditTarget by remember { mutableStateOf<PromptEditTarget?>(null) }
    var selectedPrompt by remember { mutableStateOf<PromptFragment?>(null) }
    // 编辑页返回目标：从详情进就回详情，从列表「+」进就回列表。
    var promptEditorReturn by remember { mutableStateOf(SettingsSection.Prompts) }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showContainerAnnouncement by remember { mutableStateOf(false) }
    var showImageSourceSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showResetTokenStats by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val expanded = maxWidth >= SettingsTwoPaneMinWidth

    // 返回目标：null 表示没有上一层，返回动作交还上层导航（退出设置页）。
    // 大屏左菜单常驻，一级分区页没有「上一层」——回菜单只会得到一个空占位。
    // 返回键与顶栏返回箭头共用这一份判定，不再各写一套（之前两处不一致，镜像下载页返回会直接跳回菜单）。
    val parentSection: SettingsSection? = when (section) {
        SettingsSection.Menu -> null
        SettingsSection.AharouGroup,
        SettingsSection.AiGroup,
        SettingsSection.GeneralGroup,
        SettingsSection.PermissionsGroup,
        SettingsSection.MoreGroup,
        SettingsSection.HelpGroup -> SettingsSection.Menu
        SettingsSection.Soul,
        SettingsSection.ConfigAudit,
        SettingsSection.Memory,
        SettingsSection.Pet -> SettingsSection.AharouGroup
        SettingsSection.Providers,
        SettingsSection.DefaultModels,
        SettingsSection.Mcp,
        SettingsSection.Skills -> SettingsSection.AiGroup
        SettingsSection.General -> SettingsSection.GeneralGroup
        SettingsSection.Permissions,
        SettingsSection.AppPermissions,
        SettingsSection.Accessibility,
        SettingsSection.BackgroundRun -> SettingsSection.PermissionsGroup
        SettingsSection.Container,
        SettingsSection.Proxy,
        SettingsSection.ShadowScreen,
        SettingsSection.SubAgents,
        SettingsSection.RemoteServers,
        SettingsSection.EnvVars,
        SettingsSection.Storage,
        SettingsSection.TokenStats,
        SettingsSection.Backup -> SettingsSection.MoreGroup
        SettingsSection.About -> SettingsSection.HelpGroup
        SettingsSection.Prompts -> SettingsSection.AharouGroup
        SettingsSection.ProviderEditor -> SettingsSection.Providers
        SettingsSection.Log -> logReturnSection.takeUnless { expanded && it == SettingsSection.Menu }
        SettingsSection.SkillDetail -> SettingsSection.Skills
        SettingsSection.SkillMarket -> SettingsSection.Skills
        SettingsSection.SkillMarketDetail -> SettingsSection.SkillMarket
        SettingsSection.SkillEditor -> skillEditorReturn
        SettingsSection.SubAgentDetail -> SettingsSection.SubAgents
        SettingsSection.SubAgentEditor -> subAgentEditorReturn
        SettingsSection.PromptDetail -> SettingsSection.Prompts
        SettingsSection.PromptEditor -> promptEditorReturn
        SettingsSection.ContainerDownloads -> SettingsSection.Container
        else -> if (expanded) null else SettingsSection.Menu
    }

    // 有上一层时系统返回键先回上一层；没有则交还给上层导航。
    BackHandler(enabled = parentSection != null) {
        parentSection?.let { section = it }
    }

    // settingsViewModel 为 Activity 级共享实例，每次进入设置页重新扫描技能，反映磁盘增删改。
    LaunchedEffect(Unit) {
        viewModel.refreshSkills()
        viewModel.refreshSubAgents()
    }

    // 编辑保存后回详情页：等列表刷新出新快照再换，避免详情页停在保存前的旧值（改名时按新名找）。
    LaunchedEffect(skills, pendingSkillName) {
        val target = pendingSkillName ?: return@LaunchedEffect
        skills.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { fresh ->
            selectedSkill = fresh
            pendingSkillName = null
        }
    }

    // 技能导出结果（详情页「导出」与列表长按菜单都能触发）统一在这里处理：成功后调系统分享。
    val skillExportState by viewModel.skillExportState.collectAsStateWithLifecycle()
    LaunchedEffect(skillExportState) {
        when (val state = skillExportState) {
            is SkillExportState.Ready -> {
                shareFile(context, state.file, "application/zip")
                viewModel.clearSkillExportState()
            }
            SkillExportState.Failed -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.skills_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.clearSkillExportState()
            }
            else -> Unit
        }
    }

    // 搬运结果提示（详情页「存放位置」行与列表长按菜单都能触发）。
    val skillMoveResult by viewModel.skillMoveResult.collectAsStateWithLifecycle()
    LaunchedEffect(skillMoveResult) {
        val result = skillMoveResult ?: return@LaunchedEffect
        val text = when (result) {
            SkillMoveResult.OK -> context.getString(
                if (skillMoveTarget == SkillScope.PROJECT) R.string.skills_move_ok_project
                else R.string.skills_move_ok_global
            )
            SkillMoveResult.NAME_CONFLICT -> context.getString(R.string.skills_move_conflict)
            SkillMoveResult.READ_ONLY -> context.getString(R.string.skills_move_readonly)
            SkillMoveResult.FAILED -> context.getString(R.string.skills_move_failed)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        if (result == SkillMoveResult.OK) {
            // 搬完作用域变了：详情页开着的话靠 pendingSkillName 换成列表里的新快照
            pendingSkillName = selectedSkill?.name
        }
        viewModel.clearSkillMoveResult()
    }

    // 编辑保存后回详情页：等列表刷新出新快照再换，避免详情页停在保存前的旧值（改名时按新名找）。
    LaunchedEffect(subAgents, pendingSubAgentName) {
        val target = pendingSubAgentName ?: return@LaunchedEffect
        subAgents.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { fresh ->
            selectedSubAgent = fresh
            pendingSubAgentName = null
        }
    }

    // 首次（或公告内容更新后）进入「容器与镜像」页自动弹出使用说明；哈希比对在 ViewModel 完成。
    LaunchedEffect(section, containerAnnouncementOutdated) {
        if (section == SettingsSection.Container && containerAnnouncementOutdated && containerAnnouncementText.isNotBlank()) {
            showContainerAnnouncement = true
        }
    }

    val openManual: () -> Unit = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_DOCS_URL)))
    }
    // 分区入口：进日志页前刷新一次，并把返回目标指回「更多」分组
    val openSection: (SettingsSection) -> Unit = { target ->
        if (target == SettingsSection.Log) {
            logReturnSection = SettingsSection.MoreGroup
            viewModel.refreshLogs(filterServerName = null)
        }
        section = target
    }

    // 菜单内容：窄窗当首页用，大屏当常驻左栏用，共用同一份。
    // 滚动位置必须提到 AnimatedContent 之外持有：菜单页作为分区分支被切走时会被 dispose，
    // 内部 rememberScrollState 一并丢弃，从二级页返回就跳回顶部。
    val menuScrollState = rememberScrollState()
    val menuBody: @Composable () -> Unit = {
        SettingsMenu(
            scrollState = menuScrollState,
            onOpen = openSection
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 大屏：菜单常驻左栏，右栏只换详情，不再整页来回切。
        if (expanded) {
            Column(
                modifier = Modifier
                    .width(SettingsMenuWidth)
                    .fillMaxHeight()
                    .background(settingsPageBackground())
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = settingsPageBackground(),
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
                menuBody()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // 过渡期间退场页会向左移出自身区域（sizeTransform 为 null 时 AnimatedContent 不裁剪），
        // 不夹住就会画到大屏左侧常驻菜单上。
        Box(modifier = Modifier.weight(1f).clipToBounds()) {
        // 分区切换走整页过渡（顶栏一起滑），而不是瞬切；方向按层级深度定：进更深一层从右来，返回从左来。
        AnimatedContent(
            targetState = section,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.depth() >= initialState.depth()
                // sizeTransform 必须给 null：新旧内容尺寸本就一样，默认的尺寸补间只会让容器裁剪拖影。
                ContentTransform(pageEnter(forward), pageExit(forward), sizeTransform = null)
            },
            label = "settings-section"
        ) { current ->
        when {
            // 这两页自带顶栏，不能嵌进下面的 Scaffold（会双层顶栏）：窄窗占整屏，大屏占右栏。
            current == SettingsSection.ProviderEditor -> ProviderEditorScreen(
                viewModel = viewModel,
                initialProvider = editingProvider,
                presetPrefill = providerPresetPrefill,
                initialTab = if (onboardingStep == OnboardingStep.PROVIDER_FETCH_MODELS || onboardingStep == OnboardingStep.SIMULATE_FETCH_DIALOG) 1 else 0,
                onboardingStep = onboardingStep,
                onOnboardingModelAdded = onOnboardingModelAdded,
                onOnboardingDismissFetchDialog = onOnboardingDismissFetchDialog,
                onNavigateBack = {
                    section = SettingsSection.Providers
                    providerPresetPrefill = null
                },
                onSave = { provider ->
                    viewModel.saveProvider(provider)
                }
            )

            current == SettingsSection.SkillEditor -> SkillEditorScreen(
                initial = editingSkill,
                saveState = skillSaveState,
                defaultScope = skillImportScope,
                onSave = { form, scope -> viewModel.saveSkill(form, scope, editingSkill?.name) },
                onSaved = { savedName ->
                    viewModel.clearSkillSaveState()
                    // 从详情页进来的改完回详情页，但得等新快照到位再展示
                    if (skillEditorReturn == SettingsSection.SkillDetail) {
                        pendingSkillName = savedName
                    }
                    section = skillEditorReturn
                },
                onNavigateBack = {
                    viewModel.clearSkillSaveState()
                    section = skillEditorReturn
                }
            )

            current == SettingsSection.SubAgentEditor -> SubAgentEditorScreen(
                initial = editingSubAgent,
                providers = providers,
                modelMetadata = modelMetadata,
                availableTools = remember { viewModel.availableToolNames() },
                saveState = subAgentSaveState,
                onLoadMetadata = { viewModel.loadAllModelMetadata() },
                onSave = { form, scope -> viewModel.saveSubAgent(form, scope, editingSubAgent?.name) },
                onSaved = { savedName ->
                    viewModel.clearSubAgentSaveState()
                    // 从详情页进来的改完回详情页，但得等新快照到位再展示
                    if (subAgentEditorReturn == SettingsSection.SubAgentDetail) {
                        pendingSubAgentName = savedName
                    }
                    section = subAgentEditorReturn
                },
                onNavigateBack = {
                    viewModel.clearSubAgentSaveState()
                    section = subAgentEditorReturn
                }
            )

            current == SettingsSection.PromptEditor -> {
                val promptsViewModel: PromptsViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                val number = promptEditTarget?.number
                val fragment = number?.let { value -> promptsState.fragments.firstOrNull { it.number == value } }
                PromptEditorScreen(
                    isNew = number == null,
                    initialNumber = fragment?.number ?: number ?: 0,
                    initialTitle = fragment?.title.orEmpty(),
                    initialDescription = fragment?.description.orEmpty(),
                    initialContent = fragment?.body.orEmpty(),
                    initialScope = fragment?.source ?: PromptFragmentSource.GLOBAL,
                    hasWorkspace = promptsState.hasWorkspace,
                    onSave = { savedNumber, title, scope, content ->
                        promptsViewModel.saveFragment(savedNumber, title, scope, content, previousNumber = number)
                        section = promptEditorReturn
                    },
                    onNavigateBack = { section = promptEditorReturn }
                )
            }

            current == SettingsSection.RemoteServers ->
                com.aharou.feature.workspace.presentation.remote.RemoteServerScreen(
                    onNavigateBack = { section = SettingsSection.MoreGroup }
                )

            else -> {
            // 存储页的顶栏刷新按钮与页面内容要共用同一个 ViewModel，故在此分支创建；
            // 它的构造即触发一次全盘统计，不能提到 SettingsScreen 顶层（那样每次进设置页都会扫盘）。
            val storageViewModel: com.aharou.feature.settings.presentation.StorageViewModel? =
                if (current == SettingsSection.Storage) androidx.hilt.navigation.compose.hiltViewModel() else null
            Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (current == SettingsSection.SkillDetail) {
                            selectedSkill?.name ?: stringResource(current.titleRes)
                        } else if (current == SettingsSection.SubAgentDetail) {
                            selectedSubAgent?.name ?: stringResource(current.titleRes)
                        } else if (expanded && current == SettingsSection.Menu) {
                            // 大屏左栏顶部已经写着「设置」，右栏空态不再重复
                            ""
                        } else {
                            stringResource(current.titleRes)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    // 大屏一级分区页不给返回箭头：左栏菜单随时可切，退出设置的入口在左栏顶部。
                    if (!expanded || parentSection != null) {
                        IconButton(onClick = {
                            val parent = parentSection
                            if (parent != null) section = parent else onNavigateBack()
                        }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    when (current) {
                        SettingsSection.Providers -> IconButton(
                            onClick = {
                                providerPresetPrefill = null
                                showAddProviderSheet = true
                            },
                            modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_ADD)
                        ) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider))
                        }
                        SettingsSection.Mcp -> {
                            IconButton(onClick = { viewModel.reloadMcp() }) {
                                if (mcpReloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(R.string.settings_reconnect),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingMcp = null
                                showMcpDialog = true
                            }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.settings_add_mcp_server),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Container -> {
                            IconButton(onClick = { showContainerAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.container_announcement_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { section = SettingsSection.ContainerDownloads }) {
                                Icon(
                                    FeatherIcons.Download,
                                    contentDescription = stringResource(R.string.container_download_image),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showContainerAddSheet = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image))
                            }
                        }
                        SettingsSection.ContainerDownloads -> {
                            IconButton(onClick = { showImageSourceSheet = true }) {
                                Icon(
                                    FeatherIcons.Globe,
                                    contentDescription = stringResource(R.string.container_download_current_source_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.SkillMarket -> {
                            val marketRefreshing by viewModel.marketLoading.collectAsStateWithLifecycle()
                            IconButton(
                                onClick = { viewModel.refreshMarket() },
                                enabled = !marketRefreshing
                            ) {
                                if (marketRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(R.string.skills_market_refresh),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        SettingsSection.Skills -> IconButton(onClick = {
                            showSkillAddSheet = true
                        }) {                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.skills_add),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                            // 内置技能只读，不给编辑入口
                            if (!entry.builtin) {
                                IconButton(onClick = {
                                    editingSkill = entry
                                    skillEditorReturn = SettingsSection.SkillDetail
                                    section = SettingsSection.SkillEditor
                                }) {
                                    Icon(
                                        FeatherIcons.Edit2,
                                        contentDescription = stringResource(R.string.skills_edit),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        SettingsSection.SubAgents -> IconButton(onClick = {
                            editingSubAgent = null
                            subAgentEditorReturn = SettingsSection.SubAgents
                            section = SettingsSection.SubAgentEditor
                        }) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.subagent_add),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.SubAgentDetail -> selectedSubAgent?.let { entry ->
                            IconButton(onClick = {
                                editingSubAgent = entry
                                subAgentEditorReturn = SettingsSection.SubAgentDetail
                                section = SettingsSection.SubAgentEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.subagent_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Prompts -> IconButton(onClick = {
                            showPromptsAddSheet = true
                        }) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.prompts_add_prompt),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.PromptDetail -> selectedPrompt?.let { fragment ->
                            IconButton(onClick = {
                                promptEditTarget = PromptEditTarget(fragment.number)
                                promptEditorReturn = SettingsSection.PromptDetail
                                section = SettingsSection.PromptEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.prompts_editor_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Log -> {
                            IconButton(onClick = { viewModel.refreshLogs() }) {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs))
                            }
                        }
                        SettingsSection.TokenStats -> {
                            IconButton(onClick = { showResetTokenStats = true }) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.settings_token_stats_reset),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Storage -> {
                            IconButton(onClick = { storageViewModel?.refresh() }) {
                                Icon(
                                    FeatherIcons.RefreshCw,
                                    contentDescription = stringResource(R.string.storage_refresh),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (current) {
                // 大屏菜单已常驻左栏，右栏在没选中分区时给个占位提示
                SettingsSection.Menu -> if (expanded) SettingsDetailPlaceholder() else menuBody()
                SettingsSection.AiGroup -> SettingsAiGroupScreen(onOpen = openSection)
                SettingsSection.AharouGroup -> SettingsAharouGroupScreen(onOpen = openSection)
                SettingsSection.GeneralGroup -> SettingsGeneralGroupScreen(
                    themeMode = themeMode,
                    themePresetId = themePresetId,
                    dynamicColorEnabled = dynamicColorEnabled,
                    terminalSettings = terminalSettings,
                    currentLanguageDisplayName = currentLanguageDisplayName,
                    onOpenThemeSheet = { showThemeSheet = true },
                    onOpenTerminalSettingsSheet = { showTerminalSettingsSheet = true },
                    onOpenLanguageSheet = { showLanguageSheet = true },
                    onOpen = openSection
                )
                SettingsSection.PermissionsGroup -> SettingsPermissionsGroupScreen(onOpen = openSection)
                SettingsSection.MoreGroup -> SettingsMoreGroupScreen(onOpen = openSection)
                SettingsSection.HelpGroup -> SettingsHelpGroupScreen(onOpen = openSection, onOpenManual = openManual)
                SettingsSection.Soul -> SoulSettingsSection()
                SettingsSection.ConfigAudit -> ConfigAuditSection()
                SettingsSection.ShadowScreen -> ShadowScreenSection()
                SettingsSection.EnvVars -> EnvVarsSection()
                SettingsSection.Accessibility -> AccessibilitySection()
                SettingsSection.Pet -> PetSection()
                SettingsSection.Memory -> MemorySection()
                SettingsSection.General -> GeneralSettingsSection(
                    autoRemoveStaleModels = autoRemoveStaleModels,
                    onToggleAutoRemoveStaleModels = { viewModel.setAutoRemoveStaleModels(it) },
                    startupSessionMode = startupSessionMode,
                    onSelectStartupSessionMode = { viewModel.setStartupSessionMode(it) },
                    firstByteTimeoutSec = firstByteTimeoutSec,
                    onSetFirstByteTimeoutSec = { viewModel.setFirstByteTimeoutSec(it) },
                    streamIdleTimeoutSec = streamIdleTimeoutSec,
                    onSetStreamIdleTimeoutSec = { viewModel.setStreamIdleTimeoutSec(it) },
                    maxNetworkRetries = maxNetworkRetries,
                    onSetMaxNetworkRetries = { viewModel.setMaxNetworkRetries(it) },
                    enterToSend = enterToSend,
                    onToggleEnterToSend = { viewModel.setEnterToSend(it) },
                    compactionThresholdPercent = compactionThresholdPercent,
                    onSetCompactionThresholdPercent = { viewModel.setCompactionThresholdPercent(it) },
                    softCompactionThresholdPercent = softCompactionThresholdPercent,
                    onSetSoftCompactionThresholdPercent = { viewModel.setSoftCompactionThresholdPercent(it) },
                    sendFileMaxSizeMb = sendFileMaxSizeMb,
                    onSetSendFileMaxSizeMb = { viewModel.setSendFileMaxSizeMb(it) },
                    deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
                    onToggleDeleteExternalWorkspaceSessions = { viewModel.setDeleteExternalWorkspaceSessions(it) }
                )
                SettingsSection.Providers -> ProvidersSection(
                    providers = providers,
                    onEdit = {
                        editingProvider = it
                        section = SettingsSection.ProviderEditor
                    },
                    onDelete = { viewModel.deleteProvider(it.id) },
                    onReorder = { fromIndex, toIndex ->
                        viewModel.reorderProviders(fromIndex, toIndex)
                    }
                )
                SettingsSection.DefaultModels -> DefaultModelsSection(
                    providers = providers,
                    visionProviderId = visionProviderId,
                    visionModel = visionModel,
                    compactionProviderId = compactionProviderId,
                    compactionModel = compactionModel,
                    titleProviderId = titleProviderId,
                    titleModel = titleModel,
                    imageGenProviderId = imageGenProviderId,
                    imageGenModel = imageGenModel,
                    voiceSttProviderId = voiceSttProviderId,
                    voiceSttModel = voiceSttModel,
                    voiceTtsProviderId = voiceTtsProviderId,
                    voiceTtsModel = voiceTtsModel,
                    voiceTtsVoice = voiceTtsVoice,
                    autoReadAloud = autoReadAloud,
                    onAutoReadAloudChange = viewModel::setAutoReadAloud,
                    onToggleAutoReadAloud = { viewModel.toggleAutoReadAloud() },
                    voiceWakeEnabled = voiceWakeEnabled,
                    onToggleVoiceWake = { viewModel.toggleVoiceWake() },
                    isDefaultAssistant = isDefaultAssistant,
                    onRequestDefaultAssistant = {
                        // 优先用特权通道直接设：ColorOS 的助手设置页不认第三方，
                        // 走 RoleManager/系统页都落不了地。没权限时才退回系统页面。
                        scope.launch {
                            if (viewModel.applyDefaultAssistant()) {
                                viewModel.refreshDefaultAssistant()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.voice_assistant_set_ok),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            // 必须用 Activity 的 context 启动：application context + NEW_TASK
                            // 会被系统（尤其 ColorOS）当成后台启动 Activity 默默拦掉。
                            val intent = viewModel.defaultAssistantIntent()
                            val opened = intent != null &&
                                runCatching { context.startActivity(intent) }.isSuccess
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (opened) R.string.voice_assistant_opened
                                    else R.string.voice_assistant_open_failed
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            viewModel.refreshDefaultAssistant()
                        }
                    },
                    ocrForTextOnlyModels = ocrForTextOnlyModels,
                    onToggleOcrForTextOnlyModels = { viewModel.toggleOcrForTextOnlyModels() },
                    voiceModelStatus = voiceModelStatus,
                    voiceModelMessage = voiceModelMessage,
                    onRereleaseVoiceModel = { viewModel.rereleaseVoiceModel() },
                    modelMetadata = modelMetadata,
                    onLoadMetadata = { viewModel.loadAllModelMetadata() },
                    onSelectVisionModel = { pid, m -> viewModel.setVisionModel(pid, m) },
                    onClearVisionModel = { viewModel.clearVisionModel() },
                    onSelectCompactionModel = { pid, m -> viewModel.setCompactionModel(pid, m) },
                    onClearCompactionModel = { viewModel.clearCompactionModel() },
                    onSelectTitleModel = { pid, m -> viewModel.setTitleModel(pid, m) },
                    onClearTitleModel = { viewModel.clearTitleModel() },
                    onSelectImageGenModel = { pid, m -> viewModel.setImageGenModel(pid, m) },
                    onClearImageGenModel = { viewModel.clearImageGenModel() },
                    onSelectVoiceModel = { pid, m -> viewModel.setVoiceSttModel(pid, m) },
                    onClearVoiceModel = { viewModel.clearVoiceSttModel() },
                    onSelectVoiceTtsModel = { pid, m ->
                        // 选模型时一并写入当前音色：setTtsModel 是三项整体覆写，不带上会把已配音色清掉。
                        // pid/model 为空（重置）时不带音色，让 clear 那一路统一处理。
                        viewModel.setVoiceTtsModel(pid, m, if (pid.isBlank()) "" else voiceTtsVoice)
                    },
                    onChangeVoiceTtsVoice = { viewModel.setVoiceTtsVoice(it) },
                    onClearVoiceTtsModel = { viewModel.clearVoiceTtsModel() }
                )
                SettingsSection.Mcp -> McpSection(
                    entries = mcpEntries,
                    statuses = mcpStatuses,
                    reloading = mcpReloading,
                    onReload = { viewModel.reloadMcp() },
                    onToggle = { name, enabled, scope -> viewModel.setMcpServerEnabled(name, enabled, scope) },
                    onEdit = {
                        editingMcp = it
                        showMcpDialog = true
                    },
                    onDelete = { name, scope -> viewModel.deleteMcpServer(name, scope) }
                )
                SettingsSection.Skills -> SkillsSection(
                    projectName = currentProjectName,
                    entries = skills,
                    onDelete = { skillToDelete = it },
                    onMove = { name, from, to ->
                        skillMoveTarget = to
                        viewModel.moveSkill(name, from, to)
                    },
                    onExport = { viewModel.exportSkill(it) },
                    onOpenDetail = {
                        selectedSkill = it
                        section = SettingsSection.SkillDetail
                    }
                )
                SettingsSection.SkillMarket -> {
                    val marketSources by viewModel.marketSources.collectAsStateWithLifecycle()
                    val marketSourceId by viewModel.marketSourceId.collectAsStateWithLifecycle()
                    val marketSkills by viewModel.marketSkills.collectAsStateWithLifecycle()
                    val marketLoading by viewModel.marketLoading.collectAsStateWithLifecycle()
                    val marketAlert by viewModel.marketAlert.collectAsStateWithLifecycle()
                    val marketTranslations by viewModel.marketTranslations.collectAsStateWithLifecycle()
                    val marketCategories by viewModel.marketCategories.collectAsStateWithLifecycle()
                    SkillMarketSection(
                        sources = marketSources,
                        selectedSourceId = marketSourceId,
                        skills = marketSkills,
                        translations = marketTranslations,
                        categories = marketCategories,
                        updatedAt = emptyMap(),
                        onNeedCategories = { viewModel.ensureMarketCategories() },
                        loading = marketLoading,
                        alert = marketAlert,
                        listState = marketListState,
                        onSelectSource = { viewModel.selectMarketSource(it) },
                        onInstall = { viewModel.installFromMarket(it, skillImportScope) },
                        onLoadRepo = { viewModel.loadMarketFromRepo(it) },
                        onSearch = { query, address -> viewModel.searchMarket(query, address) },
                        onOpenDetail = { skill ->
                            viewModel.openMarketDetail(skill)
                            section = SettingsSection.SkillMarketDetail
                        }
                    )
                }
                SettingsSection.SkillMarketDetail -> {
                    val detail by viewModel.marketDetail.collectAsStateWithLifecycle()
                    val marketTranslations by viewModel.marketTranslations.collectAsStateWithLifecycle()
                    detail?.let { state ->
                        SkillMarketDetailSection(
                            state = state,
                            translations = marketTranslations,
                            scope = skillImportScope,
                            onScopeChange = { skillImportScope = it },
                            onInstall = { viewModel.installFromMarket(it, skillImportScope) }
                        )
                    }
                }
                SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                    SkillDetailSection(
                        entry = entry,
                        cache = skillMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSkillEnabled(entry.name, enabled, entry.scope)
                            // 同步更新详情页快照，开关立即响应
                            selectedSkill = selectedSkill?.copy(disabled = !enabled)
                        },
                        onMove = { target ->
                            skillMoveTarget = target
                            viewModel.moveSkill(entry.name, entry.scope, target)
                        },
                        onExport = { viewModel.exportSkill(entry) },
                        exporting = skillExportState is SkillExportState.Running
                    )
                }
                SettingsSection.SubAgents -> SubAgentsSection(
                    projectName = currentProjectName,
                    entries = subAgents,
                    onDelete = { subAgentToDelete = it },
                    onOpenDetail = {
                        selectedSubAgent = it
                        section = SettingsSection.SubAgentDetail
                    }
                )
                SettingsSection.Prompts -> {
                    val promptsViewModel: PromptsViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { promptsViewModel.refresh() }
                    PromptsSection(
                        state = promptsState,
                        onOpenFragment = { fragment ->
                            selectedPrompt = fragment
                            section = SettingsSection.PromptDetail
                        },
                        onDeleteFragment = promptsViewModel::deleteFragment,
                        onReorder = promptsViewModel::reorderFragments,
                        onToggleBuiltinDisabled = promptsViewModel::setBuiltinDisabled
                    )
                    if (showPromptsAddSheet) {
                        PromptsAddSheet(
                            onDismiss = { showPromptsAddSheet = false },
                            onAddFragment = {
                                showPromptsAddSheet = false
                                promptEditTarget = PromptEditTarget(null)
                                promptEditorReturn = SettingsSection.Prompts
                                section = SettingsSection.PromptEditor
                            }
                        )
                    }
                }
                SettingsSection.PromptDetail -> {
                    val promptsViewModel: PromptsViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                    selectedPrompt?.let { sel ->
                        promptsState.fragments.firstOrNull { it.number == sel.number }?.let { fragment ->
                            PromptDetailSection(fragment = fragment)
                        }
                    }
                }
                SettingsSection.SubAgentDetail -> selectedSubAgent?.let { entry ->
                    SubAgentDetailSection(
                        entry = entry,
                        cache = subAgentMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSubAgentEnabled(entry.name, enabled, entry.scope)
                            // 同步更新详情页快照，开关立即响应
                            selectedSubAgent = selectedSubAgent?.copy(disabled = !enabled)
                        }
                    )
                }
                SettingsSection.Container -> ContainerSection(
                    profiles = containerProfiles,
                    activeProfileId = activeProfileId,
                    defaultContainerId = defaultContainerId,
                    osMap = containerOsMap,
                    showAddSheetExternal = showContainerAddSheet,
                    onDismissAddSheet = { showContainerAddSheet = false },
                    onSelect = { viewModel.setActiveContainerProfile(it) },
                    onSetDefaultContainer = { viewModel.setDefaultContainerId(it) },
                    onSaveCustom = { viewModel.saveCustomContainerProfile(it) },
                    onEditCustom = { viewModel.editCustomContainerProfile(it) },
                    onDeleteProfile = { viewModel.deleteContainerProfile(it) },
                    onSwitchConfirmed = onStopAllAndCloseTerminal,
                    onResetProfile = { profile ->
                        // 重置与切换容器同等破坏性：rootfs 整体删掉，AI 会话与终端标签必须全部停掉，
                        // 否则它们会继续读写正在被删的目录。
                        onStopAllAndCloseTerminal()
                        viewModel.resetContainer(profile)
                    },
                    resetState = containerReset,
                    onRestoreBuiltin = { viewModel.restoreBuiltinAlpine() },
                    remoteConnections = remoteConnections
                )
                SettingsSection.ContainerDownloads -> ContainerImageDownloadSection(
                    catalog = imageCatalog,
                    state = imageDownload,
                    downloadedImages = downloadedImages,
                    sourceUnavailableIds = sourceUnavailableIds,
                    selectedSourceName = viewModel.sourceDisplayName(selectedImageSource, languageTag),
                    onDownload = { entry -> viewModel.startContainerImageDownload(entry, selectedImageSource) },
                    onCancel = { viewModel.cancelContainerImageDownload() },
                    onImport = { entryId, fileUri -> viewModel.importDownloadedImage(entryId, fileUri) },
                    onDelete = { entryId -> viewModel.deleteDownloadedImage(entryId) }
                )
                SettingsSection.Proxy -> ProxySection(
                    config = proxyConfig,
                    testState = proxyTestState,
                    onTestProxy = viewModel::testProxy,
                    onSetEnabled = viewModel::setProxyEnabled,
                    onSetType = viewModel::setProxyType,
                    onSetHost = viewModel::setProxyHost,
                    onSetPort = viewModel::setProxyPort,
                    onSetUsername = viewModel::setProxyUsername,
                    onSetPassword = viewModel::setProxyPassword,
                    onSetNoProxy = viewModel::setProxyNoProxy
                )
                SettingsSection.Log -> LogSection(
                    current = logLevel,
                    onSelect = { viewModel.setLogLevel(it) },
                    state = logViewerState,
                    onSelectFile = { viewModel.selectLogFile(it) },
                    onClearFilter = { viewModel.refreshLogs(filterServerName = null) },
                    onRefresh = { viewModel.refreshLogs(silent = true) }
                )
                SettingsSection.Permissions -> PermissionsSection(
                    projectName = currentProjectName,
                    projectRules = projectRules,
                    globalRules = globalRules,
                    disableSafetyInterception = disableSafetyInterception,
                    onToggleSafetyInterception = { viewModel.setDisableSafetyInterception(it) },
                    onDeleteProject = { viewModel.deleteProjectRule(it) },
                    onPromote = { viewModel.promoteRuleToGlobal(it) },
                    onDeleteGlobal = { viewModel.deleteGlobalRule(it) }
                )
                SettingsSection.AppPermissions -> {
                    val shizukuViewModel: ShizukuViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val shizukuState by shizukuViewModel.state.collectAsStateWithLifecycle()
                    val hostShellMode by shizukuViewModel.mode.collectAsStateWithLifecycle()
                    val rootAvailable by shizukuViewModel.rootAvailable.collectAsStateWithLifecycle()
                    AppPermissionsSection(
                        shizukuState = shizukuState,
                        hostShellMode = hostShellMode,
                        rootAvailable = rootAvailable,
                        onRequestShizukuPermission = { shizukuViewModel.requestPermission() },
                        onOpenShizuku = { shizukuViewModel.openShizukuApp() },
                        onRefreshHostShell = { shizukuViewModel.refresh() }
                    )
                }
                SettingsSection.BackgroundRun -> BackgroundRunSection(
                    keepaliveEnabled = keepaliveEnabled,
                    onToggleKeepalive = { viewModel.setKeepaliveEnabled(it) },
                    screenOnEnabled = screenOnEnabled,
                    onToggleScreenOn = { viewModel.setScreenOnEnabled(it) },
                    agentSoundEnabled = agentSoundEnabled,
                    onToggleAgentSound = { viewModel.setAgentSoundEnabled(it) }
                )
                SettingsSection.Backup -> {
                    val backupViewModel: com.aharou.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
                }
                SettingsSection.TokenStats -> TokenStatsSection(
                    state = tokenStats,
                    onSelectPeriod = { viewModel.setTokenStatsPeriod(it) },
                    onSelectPage = { viewModel.setTokenStatsPage(it) },
                    onSelectProviderPage = { viewModel.setProviderStatsPage(it) },
                    onSelectModelPage = { viewModel.setModelStatsPage(it) },
                    onSelectFilterProvider = { viewModel.setFilterProviderId(it) },
                    onSelectFilterModel = { viewModel.setFilterModel(it) },
                    onClearFilters = { viewModel.clearTokenStatsFilters() }
                )
                SettingsSection.Storage -> storageViewModel?.let { StorageSectionHost(viewModel = it) }
                SettingsSection.ProviderEditor -> {} // 已在上方 early return 处理
                SettingsSection.SkillEditor -> {} // 已在上方 early return 处理
                SettingsSection.SubAgentEditor -> {} // 已在上方 early return 处理
                SettingsSection.PromptEditor -> {} // 已在上方 early return 处理
                SettingsSection.RemoteServers -> {} // 已在上方 early return 处理
                SettingsSection.About -> AboutSection(
                    updateCheckEnabled = updateCheckEnabled,
                    updateCheckChannel = updateCheckChannel,
                    autoDownloadEnabled = autoDownloadEnabled,
                    autoInstallEnabled = autoInstallEnabled,
                    onToggleUpdateCheck = { viewModel.setUpdateCheckEnabled(it) },
                    onSelectChannel = { viewModel.setUpdateCheckChannel(it) },
                    onToggleAutoDownload = { viewModel.setAutoDownloadEnabled(it) },
                    onToggleAutoInstall = { viewModel.setAutoInstallEnabled(it) },
                    onCheckUpdate = { viewModel.checkUpdate(manual = true) }
                )
            }
        }
    }
        } // else 分支结束（storageViewModel 作用域）
        } // 详情区 when 结束
        } // AnimatedContent 结束
        } // 右栏结束
    } // Row 结束

    if (showMcpDialog) {
        McpServerEditDialog(
            initial = editingMcp?.server,
            initialScope = editingMcp?.scope,
            tools = viewModel.getMcpServerTools(editingMcp?.server?.name),
            onRefreshTools = { editingMcp?.let { viewModel.reloadMcpServer(it.server.name) } },
            onOpenLogs = editingMcp?.let { existing ->
                {
                    showMcpDialog = false
                    logReturnSection = SettingsSection.Mcp
                    viewModel.refreshLogs(filterServerName = existing.server.name)
                    section = SettingsSection.Log
                }
            },
            onDismiss = { showMcpDialog = false },
            onSave = { config, scope ->
                viewModel.upsertMcpServer(editingMcp?.server?.name, editingMcp?.scope, config, scope)
                showMcpDialog = false
            }
        )
    }

    if (showAddProviderSheet) {
        ProviderPresetSheet(
            onDismiss = { showAddProviderSheet = false },
            onSelectCustom = {
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = null
                section = SettingsSection.ProviderEditor
            },
            onSelectOfficial = { preset ->
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = preset
                section = SettingsSection.ProviderEditor
            }
        )
    }

    if (showSkillAddSheet) {
        SkillAddSheet(
            scope = skillImportScope,
            onScopeChange = { skillImportScope = it },
            onManual = {
                showSkillAddSheet = false
                editingSkill = null
                skillEditorReturn = SettingsSection.Skills
                section = SettingsSection.SkillEditor
            },
            onPickFile = {
                showSkillAddSheet = false
                skillFileLauncher.launch(arrayOf("text/*", "application/octet-stream"))
            },
            onPickZip = {
                showSkillAddSheet = false
                skillZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            onMarket = {
                showSkillAddSheet = false
                viewModel.refreshMarketSources()
                viewModel.loadMarketSkills()
                section = SettingsSection.SkillMarket
            },
            onDismiss = { showSkillAddSheet = false }
        )
    }

    if (showThemeSheet) {
        ThemeSelectionSheet(
            selected = themeMode,
            selectedPresetId = themePresetId,
            dynamicColorEnabled = dynamicColorEnabled,
            backgroundImagePath = backgroundImagePath,
            backgroundAlpha = backgroundAlpha,
            onSelected = { viewModel.setThemeMode(it) },
            onPresetSelected = { viewModel.setThemePreset(it) },
            onDynamicColorChanged = { viewModel.setDynamicColorEnabled(it) },
            onPickBackgroundImage = { viewModel.setBackgroundImage(it) },
            onBackgroundAlphaChange = { viewModel.setBackgroundAlpha(it) },
            onRemoveBackgroundImage = { viewModel.clearBackgroundImage() },
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showTerminalSettingsSheet) {
        TerminalSettingsSheet(
            settings = terminalSettings,
            onDismiss = { showTerminalSettingsSheet = false },
            onSelectTheme = { viewModel.setTerminalTheme(it) },
            onChangeFontSize = { viewModel.setTerminalFontSize(it) },
            onChangeCursorStyle = { viewModel.setTerminalCursorStyle(it) },
            onChangeFontPath = { viewModel.setTerminalFontPath(it) }
        )
    }

    if (showLanguageSheet) {
        LanguageSelectionSheet(
            currentTag = languageTag,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showImageSourceSheet) {
        SourceSelectionSheet(
            options = imageSourceOptions.map { it to viewModel.sourceDisplayName(it, languageTag) },
            selected = selectedImageSource,
            onSelected = { viewModel.setImageSource(it) },
            onDismiss = { showImageSourceSheet = false }
        )
    }

    if (showResetTokenStats) {
        AlertDialog(
            onDismissRequest = { showResetTokenStats = false },
            title = { Text(stringResource(R.string.settings_token_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_token_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTokenStats()
                    showResetTokenStats = false
                }) { Text(stringResource(R.string.settings_token_stats_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTokenStats = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    skillToDelete?.let { target ->
        val deleting = skillDeleting == target.name
        // 删除进行中保持弹窗（转圈），完成后才关闭，避免无反馈地「突然消失」。
        var deleteStarted by remember(target) { mutableStateOf(false) }
        LaunchedEffect(deleteStarted, deleting) {
            if (deleteStarted && !deleting) skillToDelete = null
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) skillToDelete = null },
            title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleteStarted = true
                        viewModel.deleteSkill(target.name, target.scope)
                    }
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { skillToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    SkillImportResultDialog(state = skillImportState, onDismiss = { viewModel.clearSkillImportState() })

    subAgentToDelete?.let { target ->
        val deleting = subAgentDeleting == target.name
        var deleteStarted by remember(target) { mutableStateOf(false) }
        LaunchedEffect(deleteStarted, deleting) {
            if (deleteStarted && !deleting) subAgentToDelete = null
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) subAgentToDelete = null },
            title = { Text(stringResource(R.string.subagents_delete_confirm_title)) },
            text = { Text(stringResource(R.string.subagents_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleteStarted = true
                        viewModel.deleteSubAgent(target.name, target.scope)
                    }
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { subAgentToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 「容器与镜像」使用说明公告：首次进入（或内容更新后）自动弹出，右上角 Info 按钮可随时重看。
    if (showContainerAnnouncement) {
        val dismiss = {
            showContainerAnnouncement = false
            viewModel.markContainerAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.container_announcement_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (containerAnnouncementText.isNotBlank()) {
                        // mikepenz Markdown 内部是 Column（非 LazyColumn），本身不可滚动，
                        // 必须由外层提供滚动容器，否则超出弹窗高度的内容被直接裁剪。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = containerAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_announcement_got_it))
                    }
                }
            }
        }
    }
    } // BoxWithConstraints 结束
}

/** 大屏右栏未选中分区时的占位提示。 */
@Composable
private fun SettingsDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.settings_select_section_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** 设置首页：每个分区一个可点击的二级菜单入口。 */
@Composable
internal fun SettingsMenu(
    scrollState: ScrollState,
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(SettingsSection.AiGroup.titleRes),
                onClick = { onOpen(SettingsSection.AiGroup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Smile,
                title = stringResource(SettingsSection.AharouGroup.titleRes),
                onClick = { onOpen(SettingsSection.AharouGroup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(SettingsSection.GeneralGroup.titleRes),
                onClick = { onOpen(SettingsSection.GeneralGroup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.PermissionsGroup.titleRes),
                onClick = { onOpen(SettingsSection.PermissionsGroup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Box,
                title = stringResource(SettingsSection.MoreGroup.titleRes),
                onClick = { onOpen(SettingsSection.MoreGroup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(SettingsSection.HelpGroup.titleRes),
                onClick = { onOpen(SettingsSection.HelpGroup) }
            )
        }

    }

}

/** 导入结果弹窗：导入中显示转圈；完成时展示成功数量与逐个跳过原因（整体失败则直接报错误）。 */
@Composable
private fun SkillImportResultDialog(state: SkillImportState, onDismiss: () -> Unit) {
    when (state) {
        SkillImportState.Idle -> Unit
        SkillImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.skills_import_running)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {}
        )
        is SkillImportState.Done -> {
            val report = state.report
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.skills_import_result_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        val fatal = report.fatal
                        if (fatal != null) {
                            Text(stringResource(fatal.messageRes()))
                        } else {
                            Text(
                                stringResource(
                                    if (report.failures.isEmpty()) R.string.skills_import_success
                                    else R.string.skills_import_partial,
                                    report.imported.size,
                                    report.failures.size
                                )
                            )
                            report.failures.forEach { failure ->
                                Text(
                                    text = stringResource(
                                        R.string.skills_import_failure_line,
                                        failure.name,
                                        stringResource(failure.error.messageRes())
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
                }
            )
        }
    }
}

private fun SkillImportError.messageRes(): Int = when (this) {
    SkillImportError.INVALID_NAME -> R.string.skills_import_error_invalid_name
    SkillImportError.NAME_CONFLICT -> R.string.skills_import_error_name_conflict
    SkillImportError.EMPTY_CONTENT -> R.string.skills_import_error_empty_content
    SkillImportError.NO_SKILL_FOUND -> R.string.skills_import_error_no_skill
    SkillImportError.INVALID_ARCHIVE -> R.string.skills_import_error_invalid_archive
    SkillImportError.UNSUPPORTED_FILE -> R.string.skills_import_error_unsupported_file
    SkillImportError.IO_FAILED -> R.string.skills_import_error_io
}

/**
 * 把导出好的文件交给系统分享（可存到文件管理器、发给别的 App）。
 * 必须走 FileProvider 授权 URI：targetSdk 28+ 直接用 file:// 会抛 FileUriExposedException。
 */
private fun shareFile(context: Context, file: java.io.File, mimeType: String) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
