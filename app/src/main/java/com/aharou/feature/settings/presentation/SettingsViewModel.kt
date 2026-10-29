package com.aharou.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.net.AppProxy
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryArchiveInfo
import com.aharou.feature.agent.domain.memory.MemoryCompactReport
import com.aharou.feature.agent.domain.memory.MemoryOrganizeReport
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModelState
import com.aharou.feature.voice.domain.VoiceModelStatus
import com.aharou.core.util.FileLogger
import com.aharou.core.util.LogLevel
import com.aharou.feature.agent.data.local.dao.RecentCallRecord
import com.aharou.feature.agent.domain.container.ConnectionState
import com.aharou.feature.agent.domain.container.ContainerImageEntry
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerOsDetector
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.agent.domain.container.sha256For
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.mcp.McpToolDescriptor
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.ocr.TessdataManager
import com.aharou.feature.agent.domain.ocr.TessdataState
import com.aharou.feature.agent.domain.ocr.TessdataStatus
import com.aharou.feature.agent.domain.permission.PermissionRule
import com.aharou.feature.agent.domain.permission.PermissionRulesRepository
import com.aharou.feature.agent.domain.skill.SkillForm
import com.aharou.feature.agent.domain.skill.SkillMoveResult
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import com.aharou.feature.agent.domain.skill.market.SkillTranslation
import com.aharou.feature.agent.domain.skill.market.needsTranslation
import com.aharou.feature.agent.domain.skill.market.translationKey
import com.aharou.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinitionForm
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentSaveError
import com.aharou.feature.agent.domain.subagent.InjectPart
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.settings.data.remote.ModelApiService
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.remote.ModelTestResult
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.settings.data.remote.UpdateApkDownloader
import com.aharou.feature.settings.data.remote.UpdateCheckResult
import com.aharou.feature.settings.data.remote.UpdateCheckService
import com.aharou.feature.settings.data.remote.UpdateDownloadSource
import com.aharou.feature.settings.data.repository.UpdateCheckSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceWakeSettingsRepository
import com.aharou.feature.voice.call.VoiceCallService
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.aharou.feature.settings.data.repository.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.voice.assistant.AharouVoiceInteractionService
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.DownloadedImageRecord
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.settings.data.repository.ExecutionModeRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.LanguageSettingsRepository
import com.aharou.feature.settings.data.repository.LogSettingsRepository
import com.aharou.feature.settings.data.repository.ProxyConfig
import com.aharou.feature.settings.data.repository.ProxySettingsRepository
import com.aharou.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aharou.feature.settings.data.repository.StartupSessionMode
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aharou.feature.settings.data.repository.BackgroundSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.workspace.domain.model.RemoteConnection
import com.aharou.feature.workspace.domain.repository.RemoteRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.DashboardContext
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import com.aharou.feature.settings.domain.model.ProviderDashboardState
import com.aharou.feature.settings.domain.service.ProviderDashboardRunner
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ProxyType
import com.aharou.R
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.terminal.data.repository.TerminalSettings
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>, val debugInfo: ModelTestResult? = null) : FetchState()
    data class Error(val message: String, val debugInfo: ModelTestResult? = null) : FetchState()
}

/** 重置容器的进行状态：重置中才有值，[deleted] 为已删除的文件与目录数。 */
data class ContainerResetUiState(val name: String, val deleted: Int)

data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val content: String = "",
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/** 检查更新弹窗状态（全局弹窗宿主在主页 AppNavigation 层渲染，自动/手动共用）。 */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data object UpToDate : UpdateCheckUiState
    data class NewVersion(
        val latestTag: String,
        val changelog: String,
        /** release 里挑出的 APK 资产名；为 null 时只能走浏览器兜底。 */
        val apkAssetName: String? = null,
        val apkAssetSize: Long = 0L,
        /** 该资产的 sha256（剥掉 `sha256:` 前缀）；为 null 时下载后不校验。 */
        val apkAssetDigest: String? = null
    ) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

/** 应用内下载更新包的状态（在检查更新弹窗里展示进度）。 */
sealed interface UpdateDownloadUiState {
    data object Idle : UpdateDownloadUiState

    /** 正在挑选可用的下载源（cnb 发布仓 → 加速镜像 → GitHub 原链）。 */
    data object Resolving : UpdateDownloadUiState

    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        val sourceUrl: String
    ) : UpdateDownloadUiState

    /** 下载完成，等交给系统安装器。 */
    data class Ready(val apkPath: String, val versionTag: String) : UpdateDownloadUiState

    data class Failed(val message: String? = null) : UpdateDownloadUiState
}


/** 子代理列表页的 UI 状态：定义内容 + 来源作用域 + 启停状态。 */
data class SubAgentUiEntry(
    val name: String,
    val description: String,
    val scope: AgentDefinitionScope,
    val disabled: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val mode: AgentMode?,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val inject: Set<InjectPart>,
    val prompt: String,
    val filePath: String?
)

/** 子代理编辑页的保存结果：UI 据此决定是退回列表还是就地报错。 */
sealed interface SubAgentSaveState {
    data object Idle : SubAgentSaveState
    data object Saving : SubAgentSaveState
    data object Saved : SubAgentSaveState
    data class Failed(val error: AgentSaveError) : SubAgentSaveState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val backgroundSettingsRepository: BackgroundSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val toolSafetySettingsRepository: ToolSafetySettingsRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentDefinitionConfigRepository: AgentDefinitionConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val voiceSttSettingsRepository: VoiceSttSettingsRepository,
    private val voiceTtsSettingsRepository: VoiceTtsSettingsRepository,
    private val voiceWakeSettingsRepository: VoiceWakeSettingsRepository,
    private val voiceModelManager: VoiceModelManager,
    private val tessdataManager: TessdataManager,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    private val updateCheckSettingsRepository: UpdateCheckSettingsRepository,
    private val updateCheckService: UpdateCheckService,
    private val updateApkDownloader: UpdateApkDownloader,
    private val hostShell: HostShellManager,
    private val providerDashboardRunner: ProviderDashboardRunner,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val proxySettingsRepository: ProxySettingsRepository,
    private val memorySettings: MemorySettingsController,
    private val tokenStatsController: TokenStatsController,
    private val containerImageController: ContainerImageController,
    private val skillController: SkillSettingsController,
) : ViewModel() {

    // ── 记忆浏览与编辑（设置页「记忆」分区） ──
    // 状态与逻辑都在 MemorySettingsController，这里只转发，免得这个 ViewModel 继续膨胀。

    val memoryScope: StateFlow<MemoryScope> get() = memorySettings.scope
    val memoryEntries: StateFlow<List<Memory>> get() = memorySettings.entries
    val memoryArchives: StateFlow<List<MemoryArchiveInfo>> get() = memorySettings.archives
    val memoryActionError: StateFlow<MemoryActionError?> get() = memorySettings.actionError
    val compacting: StateFlow<Boolean> get() = memorySettings.compacting
    val compactResult: StateFlow<MemoryCompactReport?> get() = memorySettings.compactResult
    val distilling: StateFlow<Boolean> get() = memorySettings.distilling
    val distillResult: StateFlow<DistillOutcome?> get() = memorySettings.distillResult
    val memoryRecallTesting: StateFlow<Boolean> get() = memorySettings.recallTesting
    val memoryRecallReport: StateFlow<MemoryRecallReport?> get() = memorySettings.recallReport

    fun memoryProjectRoot(): String? = memorySettings.projectRoot()

    fun setMemoryScope(scope: MemoryScope) = memorySettings.setScope(scope)

    fun refreshMemory() = memorySettings.refresh()

    fun consumeMemoryActionError() = memorySettings.consumeActionError()

    suspend fun saveMemoryEntry(
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        pending: Boolean? = null
    ): Boolean = memorySettings.save(name, description, content, type, pending)

    suspend fun deleteMemoryEntry(name: String): Boolean = memorySettings.delete(name)

    suspend fun restoreMemoryArchive(fileName: String): Boolean = memorySettings.restore(fileName)

    fun compactMemory() {
        if (memorySettings.compacting.value) return
        viewModelScope.launch { memorySettings.compact() }
    }

    fun consumeCompactResult() = memorySettings.consumeCompactResult()

    val memoryAutoOrganize: StateFlow<Boolean> get() = memorySettings.autoOrganize
    val memoryOrganizeReport: StateFlow<MemoryOrganizeReport?> get() = memorySettings.organizeReport

    fun setMemoryAutoOrganize(on: Boolean) {
        viewModelScope.launch { memorySettings.setAutoOrganize(on) }
    }

    fun reloadMemoryAutoOrganize() = memorySettings.reloadAutoOrganize()

    fun distillNow() {
        if (memorySettings.distilling.value) return
        viewModelScope.launch { memorySettings.distillNow() }
    }

    fun consumeDistillResult() = memorySettings.consumeDistillResult()

    fun testMemoryRecall(query: String) {
        if (memorySettings.recallTesting.value) return
        viewModelScope.launch { memorySettings.testRecall(query) }
    }

    fun clearMemoryRecallReport() = memorySettings.clearRecallReport()

    private companion object {
        /** Shizuku 静默安装的超时（大包 pm install 要几十秒）。 */
        const val SHIZUKU_INSTALL_TIMEOUT_MS = 120_000L
        const val MAX_LOG_LINES = 1200
        /** 背景透明度停止拖动后的落盘延迟。 */
        const val BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS = 80L
    }

    /** 终端个性化配置。 */
    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalSettings())

    fun setTerminalTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setTerminalCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setTerminalFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    // ── 全局代理 ──

    /** 全局代理配置快照（SharedPreferences 即时读写，改即对新连接生效）。 */
    val proxyConfig: StateFlow<ProxyConfig> = proxySettingsRepository.config

    /** 代理连通性测试状态。 */
    sealed interface ProxyTestUiState {
        data object Idle : ProxyTestUiState
        data object Testing : ProxyTestUiState
        data class Success(val message: String) : ProxyTestUiState
        data class Error(val message: String) : ProxyTestUiState
    }

    private val _proxyTestState = MutableStateFlow<ProxyTestUiState>(ProxyTestUiState.Idle)
    val proxyTestState: StateFlow<ProxyTestUiState> = _proxyTestState.asStateFlow()

    fun setProxyEnabled(enabled: Boolean) = proxySettingsRepository.setEnabled(enabled)
    fun setProxyType(type: ProxyType) = proxySettingsRepository.setType(type)
    fun setProxyHost(host: String) = proxySettingsRepository.setHost(host)
    fun setProxyPort(port: Int) = proxySettingsRepository.setPort(port)
    fun setProxyUsername(username: String) = proxySettingsRepository.setUsername(username)
    fun setProxyPassword(password: String) = proxySettingsRepository.setPassword(password)
    fun setProxyNoProxy(noProxy: String) = proxySettingsRepository.setNoProxy(noProxy)

    fun testProxy(probeUrl: String) = testProxy(proxySettingsRepository.config.value, probeUrl)

    /** 测任意一份代理配置（供提供商代理独立页复用表单态配置）。 */
    fun testProxy(cfg: ProxyConfig, probeUrl: String) {
        if (_proxyTestState.value is ProxyTestUiState.Testing) return
        _proxyTestState.value = ProxyTestUiState.Testing
        viewModelScope.launch {
            val result = AppProxy.testProxy(context, cfg, probeUrl)
            _proxyTestState.value = if (result.ok) {
                ProxyTestUiState.Success(result.message)
            } else {
                ProxyTestUiState.Error(result.message)
            }
        }
    }

    private val _providers = MutableStateFlow<List<AIProviderConfig>>(emptyList())
    val providers: StateFlow<List<AIProviderConfig>> = _providers.asStateFlow()

    /** 新会话默认模型（主页空会话中选择后记忆），供未绑定会话回退。 */
    val defaultModelProviderId: StateFlow<String> = defaultModelSettingsRepository.providerIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultModel: StateFlow<String> = defaultModelSettingsRepository.modelFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 识图模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _visionProviderId = MutableStateFlow("")
    val visionProviderId: StateFlow<String> = _visionProviderId.asStateFlow()

    private val _visionModel = MutableStateFlow("")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    /** 压缩专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _compactionProviderId = MutableStateFlow("")
    val compactionProviderId: StateFlow<String> = _compactionProviderId.asStateFlow()

    private val _compactionModel = MutableStateFlow("")
    val compactionModel: StateFlow<String> = _compactionModel.asStateFlow()

    /** 标题总结专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _titleProviderId = MutableStateFlow("")
    val titleProviderId: StateFlow<String> = _titleProviderId.asStateFlow()

    private val _titleModel = MutableStateFlow("")
    val titleModel: StateFlow<String> = _titleModel.asStateFlow()

    /** 生图专用模型选择：providerId 为空即未配置（生图不跟随聊天模型）。 */
    private val _imageGenProviderId = MutableStateFlow("")
    val imageGenProviderId: StateFlow<String> = _imageGenProviderId.asStateFlow()

    private val _imageGenModel = MutableStateFlow("")
    val imageGenModel: StateFlow<String> = _imageGenModel.asStateFlow()

    /** 语音识别（STT）专用模型：providerId 为空即未配置（回退内置离线模型）。 */
    private val _voiceSttProviderId = MutableStateFlow("")
    val voiceSttProviderId: StateFlow<String> = _voiceSttProviderId.asStateFlow()

    private val _voiceSttModel = MutableStateFlow("")
    val voiceSttModel: StateFlow<String> = _voiceSttModel.asStateFlow()

    /** 语音合成（TTS）专用模型：providerId 为空即未配置（不朗读）。 */
    private val _voiceTtsProviderId = MutableStateFlow("")
    val voiceTtsProviderId: StateFlow<String> = _voiceTtsProviderId.asStateFlow()

    private val _voiceTtsModel = MutableStateFlow("")
    val voiceTtsModel: StateFlow<String> = _voiceTtsModel.asStateFlow()

    /** 合成音色名；不同服务商格式不同，原样透传，为空时用服务端默认。 */
    private val _voiceTtsVoice = MutableStateFlow("")
    val voiceTtsVoice: StateFlow<String> = _voiceTtsVoice.asStateFlow()

    private val _autoReadAloud = MutableStateFlow(false)

    /** 自动朗读开关：开了之后 AI 每条回复落地就自动念。 */
    val autoReadAloud: StateFlow<Boolean> = _autoReadAloud.asStateFlow()

    private val _voiceWakeEnabled = MutableStateFlow(false)

    /** 语音唤醒开关：开了麦克风常驻听唤醒词。 */
    val voiceWakeEnabled: StateFlow<Boolean> = _voiceWakeEnabled.asStateFlow()

    private val _isDefaultAssistant = MutableStateFlow(false)

    /** 本应用当前是不是系统的默认数字助手。 */
    val isDefaultAssistant: StateFlow<Boolean> = _isDefaultAssistant.asStateFlow()

    fun toggleVoiceWake() {
        viewModelScope.launch {
            val next = !voiceWakeSettingsRepository.isEnabled()
            voiceWakeSettingsRepository.setEnabled(next)
            _voiceWakeEnabled.value = next
            if (next) VoiceCallService.start(context, wakeMode = true) else VoiceCallService.stop(context)
        }
    }

    /**
     * 刷新「是否默认助手」。
     *
     * 只认 RoleManager 是不够的：国产 ROM 多数不把 ASSISTANT 角色开放给第三方，
     * 但通过特权通道写 `secure.assistant` 同样能让系统把我们当默认助手用，
     * 此时 `isRoleHeld` 仍是 false——只按它判断就会一直显示「未设置」。
     * 所以两者取或，任一成立就算已设置。
     */
    fun refreshDefaultAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                _isDefaultAssistant.value = true
                return
            }
        }
        _isDefaultAssistant.value = assistantComponentName() ==
            Settings.Secure.getString(context.contentResolver, "assistant")
    }

    /** 我们的语音交互服务组件名，格式包名/类全名。 */
    private fun assistantComponentName(): String =
        "${context.packageName}/${AharouVoiceInteractionService::class.java.name}"

    /**
     * 用特权通道直接把默认助手指向我们自己。
     *
     * ColorOS 这类 ROM 的助手设置页不认第三方应用，走 `RoleManager` 或系统设置页
     * 都落不了地；有 Shizuku/root 时直接写 secure 设置最可靠，写完即刻生效。
     *
     * @return true 表示写入成功（调用方随后应调 [refreshDefaultAssistant]）。
     */
    suspend fun applyDefaultAssistant(): Boolean {
        val component = assistantComponentName()
        val command = "settings put secure assistant $component" +
            "; settings put secure voice_interaction_service $component"
        val result = runCatching { shizukuManager.runCommand(command, 5000) }.getOrNull()
            ?: return false
        if (result.exitCode != 0) return false
        return Settings.Secure.getString(context.contentResolver, "assistant") == component
    }

    /**
     * 构造「设为默认助手」要打开的页面。
     *
     * 只返回 Intent、**由界面层用 Activity 的 context 启动**：早先在这里用 application
     * context + NEW_TASK 直接 startActivity，系统（尤其 ColorOS）会默默拦掉后台启动，
     * 表现就是「点了没反应」。
     *
     * 返回 null 表示确实无处可去。
     */
    fun defaultAssistantIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        // 不少 ROM（如 ColorOS）不给应用开放 ASSISTANT 角色，退到系统的语音输入设置页
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val _ocrForTextOnlyModels = MutableStateFlow(true)

    /** 为不支持图片输入的模型自动做本机 OCR 的开关。 */
    val ocrForTextOnlyModels: StateFlow<Boolean> = _ocrForTextOnlyModels.asStateFlow()

    /** 离线语音模型的当前状态（打开设置页时构造 VM 即算好）。 */
    private val _voiceModelStatus = MutableStateFlow(voiceModelManager.statusAll())
    val voiceModelStatus: StateFlow<VoiceModelStatus> = _voiceModelStatus.asStateFlow()

    /** 进行中的下载/解压状态；[VoiceModelState.Idle] 表示没在跑。 */
    private val _voiceModelState = MutableStateFlow<VoiceModelState>(VoiceModelState.Idle)
    val voiceModelState: StateFlow<VoiceModelState> = _voiceModelState.asStateFlow()

    fun refreshVoiceModelStatus() {
        _voiceModelStatus.value = voiceModelManager.statusAll()
    }

    /** OCR 语言包的当前状态（打开设置页时构造 VM 即算好）。 */
    private val _tessdataStatus = MutableStateFlow(tessdataManager.status())
    val tessdataStatus: StateFlow<TessdataStatus> = _tessdataStatus.asStateFlow()

    /** OCR 语言包下载状态；[TessdataState.Idle] 表示没在跑。 */
    private val _tessdataState = MutableStateFlow<TessdataState>(TessdataState.Idle)
    val tessdataState: StateFlow<TessdataState> = _tessdataState.asStateFlow()

    fun refreshTessdataStatus() {
        _tessdataStatus.value = tessdataManager.status()
    }

    /** 下载（已下载则重新下载）OCR 语言包；进度与结果都走 [tessdataState]。 */
    fun downloadTessdata() {
        if (_tessdataState.value is TessdataState.Downloading) return
        viewModelScope.launch {
            _tessdataState.value = TessdataState.Downloading(0f)
            runCatching { tessdataManager.redownload { _tessdataState.value = it } }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        _tessdataState.value = TessdataState.Failed(e.message ?: "")
                    }
                }
            refreshTessdataStatus()
        }
    }

    /** 下载（已下载则重新下载）全部离线语音模型；进度与结果都走 [voiceModelState]。 */
    fun downloadVoiceModel() {
        val running = _voiceModelState.value
        if (running is VoiceModelState.Downloading || running is VoiceModelState.Extracting) return
        viewModelScope.launch {
            val ok = voiceModelManager.redownloadAll { _voiceModelState.value = it }
            // 中途失败的模型会被后面成功那个的回调冲成 Ready，这里保留失败态让界面提示重试
            if (!ok) _voiceModelState.value = VoiceModelState.Failed("")
            refreshVoiceModelStatus()
        }
    }

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    private val _updateDownloadState = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadUiState> = _updateDownloadState.asStateFlow()
    private var updateDownloadJob: Job? = null
    private var shizukuInstallJob: Job? = null

    private val _updateCheckEnabled = MutableStateFlow(updateCheckSettingsRepository.autoCheckEnabled)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    private val _updateCheckChannel = MutableStateFlow(updateCheckSettingsRepository.channel)
    val updateCheckChannel: StateFlow<UpdateChannel> = _updateCheckChannel.asStateFlow()

    private val _autoDownloadEnabled = MutableStateFlow(updateCheckSettingsRepository.autoDownloadEnabled)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    private val _autoInstallEnabled = MutableStateFlow(updateCheckSettingsRepository.autoInstallEnabled)
    val autoInstallEnabled: StateFlow<Boolean> = _autoInstallEnabled.asStateFlow()
    /** 关于页只读展示：最近一次检查结果（含手动检查），初值来自持久化记录。 */
    val lastVersionCheck = updateCheckSettingsRepository.lastCheckSnapshot

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _screenOnEnabled = MutableStateFlow(false)
    val screenOnEnabled: StateFlow<Boolean> = _screenOnEnabled.asStateFlow()

    private val _agentSoundEnabled = MutableStateFlow(false)
    val agentSoundEnabled: StateFlow<Boolean> = _agentSoundEnabled.asStateFlow()

    private val _contextTrimRounds = MutableStateFlow(0)
    val contextTrimRounds: StateFlow<Int> = _contextTrimRounds.asStateFlow()

    private val _autoRemoveStaleModels = MutableStateFlow(true)
    val autoRemoveStaleModels: StateFlow<Boolean> = _autoRemoveStaleModels.asStateFlow()

    private val _startupSessionMode = MutableStateFlow(StartupSessionMode.NEW_SESSION)
    val startupSessionMode: StateFlow<StartupSessionMode> = _startupSessionMode.asStateFlow()

    private val _firstByteTimeoutSec = MutableStateFlow(120)
    val firstByteTimeoutSec: StateFlow<Int> = _firstByteTimeoutSec.asStateFlow()

    private val _streamIdleTimeoutSec = MutableStateFlow(120)
    val streamIdleTimeoutSec: StateFlow<Int> = _streamIdleTimeoutSec.asStateFlow()

    private val _maxNetworkRetries = MutableStateFlow(6)
    val maxNetworkRetries: StateFlow<Int> = _maxNetworkRetries.asStateFlow()

    private val _enterToSend = MutableStateFlow(false)
    val enterToSend: StateFlow<Boolean> = _enterToSend.asStateFlow()

    private val _compactionThresholdPercent = MutableStateFlow(90)
    val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()

    private val _softCompactionThresholdPercent = MutableStateFlow(55)
    val softCompactionThresholdPercent: StateFlow<Int> = _softCompactionThresholdPercent.asStateFlow()

    private val _sendFileMaxSizeMb = MutableStateFlow(100)
    val sendFileMaxSizeMb: StateFlow<Int> = _sendFileMaxSizeMb.asStateFlow()

    private val _deleteExternalWorkspaceSessions = MutableStateFlow(false)
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = _deleteExternalWorkspaceSessions.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _themePresetId = MutableStateFlow<String?>(null)
    val themePresetId: StateFlow<String?> = _themePresetId.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    /** 全局自定义背景图文件路径（null=未设置），供设置弹窗展示与预览。 */
    private val _backgroundImagePath = MutableStateFlow<String?>(null)
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /** 背景图不透明度（0~0.2，实际范围由 BackgroundSettingsRepository 的 MIN/MAX 决定），实时写 DataStore，全局背景同步变化。 */
    private val _backgroundAlpha = MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** 透明度落盘的节流 job，见 [setBackgroundAlpha]。 */
    private var backgroundAlphaWriteJob: Job? = null

    /** 用户选择的应用语言 tag（null 表示跟随系统）。 */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    // ── 技能与技能市场：整块在 [SkillSettingsController] ──
    val skills: StateFlow<List<SkillUiEntry>> get() = skillController.skills
    val skillSaveState: StateFlow<SkillSaveState> get() = skillController.saveState
    val skillImportState: StateFlow<SkillImportState> get() = skillController.importState
    val skillDeleting: StateFlow<String?> get() = skillController.deleting
    val skillMoving: StateFlow<String?> get() = skillController.moving
    val skillMoveResult: StateFlow<SkillMoveResult?> get() = skillController.moveResult
    val skillExportState: StateFlow<SkillExportState> get() = skillController.exportState
    val marketSources: StateFlow<List<Pair<String, String>>> get() = skillController.marketSources
    val marketSourceId: StateFlow<String> get() = skillController.marketSourceId
    val marketSkills: StateFlow<List<MarketSkillUi>> get() = skillController.marketSkills
    val marketTranslations: StateFlow<Map<String, SkillTranslation>> get() = skillController.marketTranslations
    val marketCategories: StateFlow<Map<String, List<String>>> get() = skillController.marketCategories
    val marketLoading: StateFlow<Boolean> get() = skillController.marketLoading
    val marketAlert: StateFlow<MarketAlert?> get() = skillController.marketAlert
    val marketDetail: StateFlow<MarketDetailUi?> get() = skillController.marketDetail
    val marketSearching: StateFlow<Boolean> get() = skillController.marketSearching

    private val _subAgents = MutableStateFlow<List<SubAgentUiEntry>>(emptyList())
    val subAgents: StateFlow<List<SubAgentUiEntry>> = _subAgents.asStateFlow()

    private val _subAgentSaveState = MutableStateFlow<SubAgentSaveState>(SubAgentSaveState.Idle)
    val subAgentSaveState: StateFlow<SubAgentSaveState> = _subAgentSaveState.asStateFlow()

    /** 正在删除的子代理名（非 null 表示删除进行中，用于弹窗转圈与禁用按钮）。 */
    private val _subAgentDeleting = MutableStateFlow<String?>(null)
    val subAgentDeleting: StateFlow<String?> = _subAgentDeleting.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    /** 模型元数据缓存，键为 [modelMetadataKey]（渠道 + 模型）——单价与能力都可能因渠道而异。 */
    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _dashboardTestState = MutableStateFlow<ProviderDashboardState>(ProviderDashboardState.Idle)
    val dashboardTestState: StateFlow<ProviderDashboardState> = _dashboardTestState.asStateFlow()

    private val _dashboardScripts = MutableStateFlow<List<String>>(emptyList())
    /** 可用面板脚本文件名（打开脚本选择器时经 [loadDashboardScripts] 异步加载）。 */
    val dashboardScripts: StateFlow<List<String>> = _dashboardScripts.asStateFlow()

    private val _providerDashboards = MutableStateFlow<Map<String, ProviderDashboardState>>(emptyMap())
    val providerDashboards: StateFlow<Map<String, ProviderDashboardState>> = _providerDashboards.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _disableSafetyInterception = MutableStateFlow(false)
    val disableSafetyInterception: StateFlow<Boolean> = _disableSafetyInterception.asStateFlow()

    private val _activeProfileId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _defaultContainerId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val defaultContainerId: StateFlow<String> = _defaultContainerId.asStateFlow()

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** 全部 profile（内置 Alpine 也作为普通一项持久化在列表里，首次启动自动写入）。 */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, listOf(ContainerProfile.BUILTIN_ALPINE))

    /** 各容器已识别的系统类型（profile id → os id），UI 据此显示对应系统图标。 */
    val containerOsMap: StateFlow<Map<String, String>> = containerOsDetector.osMap

    /** 公告内容（跟随界面语言），与 [containerAnnouncementOutdated] 配套供弹窗渲染。 */
    private val _containerAnnouncementText = MutableStateFlow("")
    val containerAnnouncementText: StateFlow<String> = _containerAnnouncementText.asStateFlow()

    /** 公告是否需要弹出：本地未存哈希或与当前内容哈希不一致（内容更新过）时为 true。 */
    private val _containerAnnouncementOutdated = MutableStateFlow(false)
    val containerAnnouncementOutdated: StateFlow<Boolean> = _containerAnnouncementOutdated.asStateFlow()

    /** 正在重置的容器（含已删条目数），为 null 说明不在重置。UI 据此弹进度框。 */
    private val _containerReset = MutableStateFlow<ContainerResetUiState?>(null)
    val containerReset: StateFlow<ContainerResetUiState?> = _containerReset.asStateFlow()

    // ── 容器镜像：目录 / 下载源 / 已下载记录整块在 [ContainerImageController] ──
    val imageCatalog: StateFlow<List<ContainerImageEntry>> get() = containerImageController.imageCatalog
    val imageSourceOptions: StateFlow<List<String>> get() = containerImageController.sourceOptions
    val selectedImageSource: StateFlow<String> get() = containerImageController.selectedSource
    val downloadedImages: StateFlow<Map<String, DownloadedImageRecord>> get() = containerImageController.downloaded
    val sourceUnavailableIds: StateFlow<Set<String>> get() = containerImageController.unavailableIds
    val containerImageDownload: StateFlow<ContainerImageDownloadUiState> get() = containerImageController.download


    /** 当前执行模式（本地 PRoot / 远程 SSH），供 UI 判断是否显示远程连接指示器。 */
    val executionMode: StateFlow<ExecutionMode> = executionModeHolder.mode

    /** 远程 SSH 连接状态，供 UI 显示指示器。 */
    val connectionState: StateFlow<ConnectionState> = remoteSshConnection.connectionState

    /** Token 统计：整块状态与操作已搬到 [TokenStatsController]，这里只做转发。 */
    val tokenStats: StateFlow<TokenStatsUiState> = tokenStatsController.state
    val filterProviderId: StateFlow<String?> = tokenStatsController.filterProviderId
    val filterModel: StateFlow<String?> = tokenStatsController.filterModel

    /** 工作区已配置的远程连接通道，供容器镜像 SSH 模式下拉复用。 */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        // Token 统计自己订阅 Room 的调用记录：首次建 ViewModel 才启动，避免开机就挂一串 Flow。
        tokenStatsController.ensureStarted()
        skillController.ensureStarted()
        containerImageController.ensureStarted()
        // 公告按「内容哈希比对」判断是否弹出：首次（无哈希）或内容更新（哈希不一致）即弹。
        viewModelScope.launch {
            combine(_languageTag, containerSettingsRepository.announcementShownHashFlow) { tag, storedHash ->
                val text = loadContainerAnnouncement(context, tag)
                _containerAnnouncementText.value = text
                _containerAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        viewModelScope.launch {
            launch {
                repository.getAllProviders().collectLatest {
                    _providers.value = it
                }
            }

            launch {
                visionModelSettingsRepository.providerIdFlow.collectLatest {
                    _visionProviderId.value = it
                }
            }

            launch {
                visionModelSettingsRepository.modelFlow.collectLatest {
                    _visionModel.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.providerIdFlow.collectLatest {
                    _compactionProviderId.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.modelFlow.collectLatest {
                    _compactionModel.value = it
                }
            }

            launch {
                titleModelSettingsRepository.providerIdFlow.collectLatest {
                    _titleProviderId.value = it
                }
            }

            launch {
                titleModelSettingsRepository.modelFlow.collectLatest {
                    _titleModel.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.providerIdFlow.collectLatest {
                    _imageGenProviderId.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.modelFlow.collectLatest {
                    _imageGenModel.value = it
                }
            }

            launch {
                voiceSttSettingsRepository.providerIdFlow.collectLatest {
                    _voiceSttProviderId.value = it
                }
            }

            launch {
                voiceSttSettingsRepository.modelFlow.collectLatest {
                    _voiceSttModel.value = it
                }
            }

            launch {
                voiceTtsSettingsRepository.providerIdFlow.collectLatest {
                    _voiceTtsProviderId.value = it
                }
            }

            launch {
                voiceTtsSettingsRepository.modelFlow.collectLatest {
                    _voiceTtsModel.value = it
                }
            }

            launch {
                voiceTtsSettingsRepository.voiceFlow.collectLatest {
                    _voiceTtsVoice.value = it
                }
            }

            // 每个 flow 必须各起一个协程：collectLatest 挂起后永不返回，
            // 串在同一个 launch 里的话，后面的根本不会执行——
            // 表现就是开关值回不到界面，界面显示的永远是初始值（与真实状态相反）。
            launch {
                voiceTtsSettingsRepository.autoReadAloudFlow.collectLatest {
                    _autoReadAloud.value = it
                }
            }
            launch {
                voiceWakeSettingsRepository.enabledFlow.collectLatest {
                    _voiceWakeEnabled.value = it
                }
            }

            refreshDefaultAssistant()

            launch {
                generalSettingsRepository.ocrForTextOnlyModelsFlow.collectLatest {
                    _ocrForTextOnlyModels.value = it
                }
            }

            launch {
                logSettingsRepository.levelFlow.collectLatest {
                    _logLevel.value = it
                }
            }

            launch {
                keepaliveSettingsRepository.enabledFlow.collectLatest {
                    _keepaliveEnabled.value = it
                }
            }

            launch {
                screenOnSettingsRepository.enabledFlow.collectLatest {
                    _screenOnEnabled.value = it
                }
            }

            launch {
                agentSoundSettingsRepository.enabledFlow.collectLatest {
                    _agentSoundEnabled.value = it
                }
            }

            launch {
                generalSettingsRepository.contextTrimRoundsFlow.collectLatest {
                    _contextTrimRounds.value = it
                }
                generalSettingsRepository.autoRemoveStaleModelsFlow.collectLatest {
                    _autoRemoveStaleModels.value = it
                }
            }

            launch {
                generalSettingsRepository.startupSessionModeFlow.collectLatest {
                    _startupSessionMode.value = it
                }
            }

            launch {
                generalSettingsRepository.firstByteTimeoutSecFlow.collectLatest {
                    _firstByteTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.streamIdleTimeoutSecFlow.collectLatest {
                    _streamIdleTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.maxNetworkRetriesFlow.collectLatest {
                    _maxNetworkRetries.value = it
                }
            }

            launch {
                generalSettingsRepository.enterToSendFlow.collectLatest {
                    _enterToSend.value = it
                }
            }

            launch {
                generalSettingsRepository.compactionThresholdPercentFlow.collectLatest {
                    _compactionThresholdPercent.value = it
                }
            }

            launch {
                generalSettingsRepository.softCompactionThresholdPercentFlow.collectLatest {
                    _softCompactionThresholdPercent.value = it
                }
            }

            launch {
                generalSettingsRepository.sendFileMaxSizeMbFlow.collectLatest {
                    _sendFileMaxSizeMb.value = it
                }
            }

            launch {
                generalSettingsRepository.deleteExternalWorkspaceSessionsFlow.collectLatest {
                    _deleteExternalWorkspaceSessions.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                themeSettingsRepository.themePresetIdFlow.collectLatest {
                    _themePresetId.value = it
                }
            }

            launch {
                themeSettingsRepository.dynamicColorFlow.collectLatest {
                    _dynamicColorEnabled.value = it
                }
            }

            launch {
                backgroundSettingsRepository.imagePathFlow.collectLatest {
                    _backgroundImagePath.value = it
                }
            }

            launch {
                backgroundSettingsRepository.alphaFlow.collectLatest {
                    _backgroundAlpha.value = it
                }
            }

            launch {
                languageSettingsRepository.languageFlow.collectLatest {
                    _languageTag.value = it
                }
            }

            launch {
                containerSettingsRepository.activeProfileIdFlow.collectLatest {
                    _activeProfileId.value = it
                }
            }

            launch {
                containerSettingsRepository.defaultContainerIdFlow.collectLatest {
                    _defaultContainerId.value = it
                }
            }

            launch {
                // 先做存量顺序归一，再开始收集，避开升级后第一帧闪出旧顺序：补内置项 + 把历史「新建在前」的显示序写回存储。
                containerSettingsRepository.ensureBuiltinDefault()
                containerSettingsRepository.ensureProfileDisplayOrder()
                // 此后存储顺序即显示顺序：新增插到最前、编辑原地替换、拖拽重排由 reorderCustomProfiles 落库
                containerSettingsRepository.customProfilesFlow.collectLatest { _customProfiles.value = it }
            }

            launch {
                mcpConfigRepository.effectiveEntriesFlow.collectLatest {
                    _mcpEntries.value = it
                }
            }

            launch {
                // 技能配置文件变更（含外部改动）→ 刷新列表；数据源在 [SkillSettingsController]。
                skillController.changes.collectLatest {
                    refreshSkills()
                }
            }

            launch {
                agentDefinitionConfigRepository.changes.collectLatest {
                    refreshSubAgents()
                }
            }

            // 全局技能/子代理在远程模式下以远端为源，本地目录监听（skills.changes）不会触发；
            // 执行模式切换或远程连接就绪后需重扫，否则列表停留在旧环境的内容。
            launch {
                executionMode.collectLatest {
                    refreshSkills()
                    refreshSubAgents()
                }
            }

            launch {
                connectionState.collectLatest { state ->
                    if (state == ConnectionState.CONNECTED) {
                        refreshSkills()
                        refreshSubAgents()
                    }
                }
            }

            launch {
                refreshSkills()
            }

            launch {
                permissionRulesRepository.globalRulesFlow.collectLatest {
                    _globalRules.value = it
                }
            }

            launch {
                permissionRulesRepository.currentProjectRulesFlow.collectLatest {
                    _projectRules.value = it
                }
            }

            launch {
                toolSafetySettingsRepository.disableSafetyInterceptionFlow.collectLatest {
                    _disableSafetyInterception.value = it
                }
            }

        }
    }

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        viewModelScope.launch {
            // 作用域迁移：仅当保存作用域与原来不同时，从原作用域移除旧条目，
            // 避免残留条目在合并时（项目优先）继续覆盖新作用域的配置。
            if (originalName != null && initialScope != null && initialScope != scope) {
                val oldBase = if (initialScope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
                val oldUpdated = oldBase.filterNot { it.name == originalName }
                if (initialScope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(oldUpdated) else mcpConfigRepository.setProjectServers(oldUpdated)
            }
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val ordered = LinkedHashMap<String, McpServerConfig>()
            base.forEach { ordered[it.name] = it }
            if (originalName != null && originalName != config.name) {
                ordered.remove(originalName)
            }
            ordered[config.name] = config
            val updated = ordered.values.toList()
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                // 仅重连被改动的 server，其他 server 不受影响；重命名时先断开旧名。
                if (originalName != null && originalName != config.name) {
                    mcpManager.removeServer(originalName)
                }
                mcpManager.reloadServer(config.name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name == name }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.removeServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 批量删除 MCP server：配置一次写回（列表只随这一次写入刷新），再逐个断开连接。 */
    fun deleteMcpServers(names: Collection<String>, scope: McpScope) {
        val targets = names.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name in targets }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                targets.forEach { mcpManager.removeServer(it) }
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 批量启用/停用 MCP server：配置一次写回，再逐个重连受影响的 server。 */
    fun setMcpServersEnabled(names: Collection<String>, enabled: Boolean, scope: McpScope) {
        val targets = names.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name in targets) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                targets.forEach { mcpManager.reloadServer(it) }
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 批量重连选中的 MCP server（只重连这些，不整体 reload）。 */
    fun reloadMcpServers(names: Collection<String>) {
        val targets = names.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                targets.forEach { mcpManager.reloadServer(it) }
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun reloadMcp() {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 仅重连指定 server（编辑弹窗右上角刷新工具用）。 */
    fun reloadMcpServer(name: String) {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    // ── 技能与技能市场：操作一律转到 [SkillSettingsController] ──

    fun refreshMarketSources() = skillController.refreshMarketSources()

    fun selectMarketSource(sourceId: String) = skillController.selectMarketSource(sourceId)

    fun refreshMarket() = skillController.refreshMarket()

    fun searchMarket(query: String, address: String) = skillController.searchMarket(query, address)

    fun loadMarketFromRepo(input: String) = skillController.loadMarketFromRepo(input)

    fun loadMarketSkills(force: Boolean = false) = skillController.loadMarketSkills(force)

    fun ensureMarketCategories() = skillController.ensureMarketCategories()

    fun openMarketDetail(skill: MarketSkill) = skillController.openMarketDetail(skill)

    fun installFromMarket(skill: MarketSkill, scope: SkillScope) = skillController.installFromMarket(skill, scope)

    fun refreshSkills() = skillController.refreshSkills()

    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) =
        skillController.setSkillEnabled(name, enabled, scope)

    fun deleteSkill(name: String, scope: SkillScope) = skillController.deleteSkill(name, scope)

    /** 批量删除同一作用域下的技能：逐条走控制器（含市场安装记录清理），删完由控制器刷新列表。 */
    fun deleteSkills(names: Collection<String>, scope: SkillScope) {
        val targets = names.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        skillController.deleteSkills(targets, scope)
    }

    /** 批量启用/停用同一作用域下的技能：控制器一次写完再刷新，避免 N 条就重读 N 次。 */
    fun setSkillsEnabled(names: Collection<String>, enabled: Boolean, scope: SkillScope) {
        val targets = names.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        skillController.setSkillsEnabled(targets, enabled, scope)
    }

    fun moveSkill(name: String, fromScope: SkillScope, toScope: SkillScope) =
        skillController.moveSkill(name, fromScope, toScope)

    fun clearSkillMoveResult() = skillController.clearMoveResult()

    fun saveSkill(form: SkillForm, scope: SkillScope, originalName: String? = null) =
        skillController.saveSkill(form, scope, originalName)

    fun clearSkillSaveState() = skillController.clearSaveState()

    fun importSkillFromMarkdown(uri: Uri, scope: SkillScope) = skillController.importSkillFromMarkdown(uri, scope)

    fun importSkillsFromZip(uri: Uri, scope: SkillScope) = skillController.importSkillsFromZip(uri, scope)

    fun clearSkillImportState() = skillController.clearImportState()

    fun exportSkill(entry: SkillUiEntry) = skillController.exportSkill(entry)

    fun clearSkillExportState() = skillController.clearExportState()

    fun refreshSubAgents() {
        viewModelScope.launch {
            _subAgents.value = withContext(Dispatchers.IO) {
                val disabled = agentDefinitionConfigRepository.disabledNames()
                agentDefinitionRepository.listAll().map { entry ->
                    SubAgentUiEntry(
                        name = entry.definition.name,
                        description = entry.definition.description,
                        scope = entry.scope,
                        disabled = entry.definition.name.lowercase() in disabled,
                        providerId = entry.definition.providerId,
                        model = entry.definition.model,
                        reasoningEffort = entry.definition.reasoningEffort,
                        mode = entry.definition.mode,
                        allowedTools = entry.definition.allowedTools,
                        disallowedTools = entry.definition.disallowedTools,
                        inject = entry.definition.inject,
                        prompt = entry.definition.prompt,
                        filePath = entry.definition.filePath
                    )
                }
            }
        }
    }

    /** 删除指定作用域的子代理定义文件（不可恢复），随后立即刷新列表。 */
    /** 切换子代理启用/禁用（写入对应作用域的 agents.json）；禁用后不再进主代理的可派发清单。 */
    fun setSubAgentEnabled(name: String, enabled: Boolean, scope: AgentDefinitionScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agentDefinitionRepository.setDisabled(name, !enabled, scope) }
            refreshSubAgents()
        }
    }

    /**
     * 保存子代理定义。[originalName] 为编辑前的名称，新建时为 null；
     * 结果转成 [subAgentSaveState]，编辑页据此退回列表或就地报错。
     */
    fun saveSubAgent(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ) {
        if (_subAgentSaveState.value is SubAgentSaveState.Saving) return
        _subAgentSaveState.value = SubAgentSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                agentDefinitionRepository.save(form, scope, originalName)
            }
            _subAgentSaveState.value = if (error == null) {
                SubAgentSaveState.Saved
            } else {
                SubAgentSaveState.Failed(error)
            }
            if (error == null) refreshSubAgents()
        }
    }

    fun clearSubAgentSaveState() {
        _subAgentSaveState.value = SubAgentSaveState.Idle
    }

    /** 当前已注册的全部工具名（含 MCP 动态工具），供编辑页勾选工具白名单与黑名单。 */
    fun availableToolNames(): List<String> = toolRegistry.getAvailableTools().map { it.name }

    fun deleteSubAgent(name: String, scope: AgentDefinitionScope) {
        if (_subAgentDeleting.value != null) return
        _subAgentDeleting.value = name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { agentDefinitionRepository.delete(name, scope) }
                refreshSubAgents()
            } finally {
                _subAgentDeleting.value = null
            }
        }
    }

    /** 批量删除同一作用域的子代理定义：逐条删文件，全部删完只刷新一次列表。 */
    fun deleteSubAgents(names: Collection<String>, scope: AgentDefinitionScope) {
        val targets = names.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { agentDefinitionRepository.delete(it, scope) }
            }
            refreshSubAgents()
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch {
            logSettingsRepository.setLevel(level)
        }
    }

    // ── 检查更新 ──

    /**
     * 检查更新。手动模式（关于页点击）不受开关与每日限制；自动模式（主页启动）
     * 需开关开启且当天未检测过，失败时静默（不弹错误窗），无论结果都记录当天已检。
     */
    fun checkUpdate(manual: Boolean) {
        if (!manual) {
            if (!updateCheckSettingsRepository.autoCheckEnabled) return
            if (updateCheckSettingsRepository.hasCheckedToday()) return
        }
        if (_updateCheckState.value == UpdateCheckUiState.Checking) return
        viewModelScope.launch {
            // 手动检查显示「检测中」反馈；自动检查全程无感，仅检测到新版本才弹窗
            if (manual) {
                _updateCheckState.value = UpdateCheckUiState.Checking
            }
            val result = updateCheckService.checkForUpdate(
                currentVersion = updateCheckSettingsRepository.currentVersionName(),
                channel = updateCheckSettingsRepository.channel
            )
            _updateCheckState.value = when (result) {
                is UpdateCheckResult.UpToDate -> {
                    if (manual) UpdateCheckUiState.UpToDate else UpdateCheckUiState.Idle
                }
                is UpdateCheckResult.NewVersion -> {
                    // 同一版本只自动提示一次：用户点过「稍后」后不该被反复打扰；手动检查不受此限。
                    if (!manual && result.info.latestTag == updateCheckSettingsRepository.lastNotifiedTag) {
                        UpdateCheckUiState.Idle
                    } else {
                        updateCheckSettingsRepository.lastNotifiedTag = result.info.latestTag
                        UpdateCheckUiState.NewVersion(
                            latestTag = result.info.latestTag,
                            changelog = result.info.changelog,
                            apkAssetName = result.info.apkAssetName,
                            apkAssetSize = result.info.apkAssetSize,
                            apkAssetDigest = result.info.apkAssetDigest
                        )
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (manual) UpdateCheckUiState.Error(result.message) else UpdateCheckUiState.Idle
                }
            }
            // 自动下载：检测到新版本就提前把包下好，用户之后只需点一下「立即安装」
            if (result is UpdateCheckResult.NewVersion && updateCheckSettingsRepository.autoDownloadEnabled) {
                autoDownloadIfNeeded(result.info.latestTag, result.info.apkAssetDigest)
            }
            // 无论结果如何，都刷新 ~/.aharou/update-info.json 供 AI 读取
            updateCheckSettingsRepository.writeUpdateInfo(
                currentVersion = updateCheckSettingsRepository.currentVersionName(),
                channel = updateCheckSettingsRepository.channel,
                result = result
            )
            if (!manual) {
                updateCheckSettingsRepository.markCheckedToday()
            }
        }
    }

    /** 关闭更新弹窗（连同已下未装的进度一起收起）。 */
    fun dismissUpdateCheck() {
        _updateCheckState.value = UpdateCheckUiState.Idle
        cancelDownload()
    }

    /**
     * 应用内下载更新包：候选源依次尝试（cnb 发布仓 → 加速镜像 → GitHub 原链），
     * 进度与当前来源写入 [updateDownloadState] 供弹窗展示。
     */
    fun downloadUpdate() {
        val state = _updateCheckState.value as? UpdateCheckUiState.NewVersion ?: return
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = viewModelScope.launch {
            _updateDownloadState.value = UpdateDownloadUiState.Resolving
            // 资产可能在「发现新版本」那一刻还没同步到位（发布主源的资产滞后于 release）。
            // 不能拿状态里那个 null 直接报错：那样用户退出重进、再点下载都是一样的失败。
            // 这里现场重查一次，拿到资产就用（并回写状态，让后续点击也正常）。
            var effective = state
            if (effective.apkAssetName.isNullOrBlank()) {
                val fresh = runCatching {
                    updateCheckService.checkForUpdate(
                        currentVersion = updateCheckSettingsRepository.currentVersionName(),
                        channel = updateCheckSettingsRepository.channel
                    )
                }.getOrNull()
                val freshInfo = (fresh as? UpdateCheckResult.NewVersion)?.info
                if (freshInfo?.apkAssetName.isNullOrBlank()) {
                    _updateDownloadState.value = UpdateDownloadUiState.Failed("no apk asset in release")
                    return@launch
                }
                effective = UpdateCheckUiState.NewVersion(
                    latestTag = freshInfo!!.latestTag,
                    changelog = freshInfo.changelog,
                    apkAssetName = freshInfo.apkAssetName,
                    apkAssetSize = freshInfo.apkAssetSize,
                    apkAssetDigest = freshInfo.apkAssetDigest
                )
                _updateCheckState.value = effective
            }
            val assetName = effective.apkAssetName.orEmpty()
            val candidates = UpdateDownloadSource.candidates(effective.latestTag, assetName)
            // 记下实际命中的候选（候选列表失败会静默换下一个源，不记就无从得知走的哪条）
            var usedSource = ""
            try {
                val file = updateApkDownloader.download(
                    candidates = candidates,
                    fileName = assetName,
                    expectedSize = effective.apkAssetSize,
                    expectedSha256 = effective.apkAssetDigest,
                    onSource = { url ->
                        usedSource = url
                        _updateDownloadState.value = UpdateDownloadUiState.Downloading(
                            bytesRead = 0L,
                            totalBytes = effective.apkAssetSize,
                            sourceUrl = url
                        )
                    },
                    onProgress = { read, total ->
                        val source = (_updateDownloadState.value as? UpdateDownloadUiState.Downloading)?.sourceUrl.orEmpty()
                        _updateDownloadState.value = UpdateDownloadUiState.Downloading(read, total, source)
                    }
                )
                updateCheckSettingsRepository.downloadedTag = effective.latestTag
                updateCheckSettingsRepository.downloadedPath = file.absolutePath
                updateCheckSettingsRepository.writeDownloadRecord(effective.latestTag, usedSource, file.absolutePath)
                FileLogger.i("UpdateDownload", "已下载 $effective.latestTag，来源=$usedSource")
                _updateDownloadState.value = UpdateDownloadUiState.Ready(file.absolutePath, effective.latestTag)
                maybeAutoInstall(file.absolutePath, effective.latestTag)
            } catch (e: CancellationException) {
                _updateDownloadState.value = UpdateDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                FileLogger.w("UpdateDownload", "download failed: ${e.message}")
                _updateDownloadState.value = UpdateDownloadUiState.Failed(e.message)
            }
        }
    }

    /** 取消进行中的下载（半成品由下载器删除）。 */
    fun cancelDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        _updateDownloadState.value = UpdateDownloadUiState.Idle
    }

    /**
     * 检测到新版本就提前把包装好（开关默认开）。已下好同一版本则直接进入可安装状态，不重复下载。
     */
    private fun autoDownloadIfNeeded(tag: String, expectedSha256: String?) {
        val savedPath = updateCheckSettingsRepository.downloadedPath
        // 复用上次下好的包同样要校验：它接着就被送去安装，不能因为「上次下过」而免检。
        if (updateCheckSettingsRepository.downloadedTag == tag && savedPath != null &&
            File(savedPath).isFile &&
            runCatching { updateApkDownloader.matchesDigest(File(savedPath), expectedSha256) }
                .getOrDefault(false)
        ) {
            _updateDownloadState.value = UpdateDownloadUiState.Ready(savedPath, tag)
            maybeAutoInstall(savedPath, tag)
            return
        }
        downloadUpdate()
    }

    /** 开关开启时交给 Shizuku 静默安装；未开启/未授权则什么都不做，等用户点「立即安装」。 */
    private fun maybeAutoInstall(path: String, tag: String) {
        if (!updateCheckSettingsRepository.autoInstallEnabled) return
        installUpdateWithShizuku(path, tag)
    }

    /**
     * 用宿主通道（有 root 走 root，否则 adb shell）静默安装已下载的更新包。
     * 失败不弹错误窗：UI 上的「立即安装」按钮仍在，可回落到系统安装器。
     */
    fun installUpdateWithShizuku(path: String, tag: String) {
        if (shizukuInstallJob?.isActive == true) return
        shizukuInstallJob = viewModelScope.launch {
            val result = runCatching {
                hostShell.run("pm install -r \"$path\"", SHIZUKU_INSTALL_TIMEOUT_MS)
            }.getOrNull()
            val ok = result != null && result.exitCode == 0 && !result.output.contains("Failure")
            FileLogger.i(
                "UpdateInstall",
                "静默安装 tag=$tag exit=${result?.exitCode} ok=$ok"
            )
            // 成功时 App 马上被替换重启，不必再改状态；失败就保持 Ready，让用户点按钮走系统安装器
        }
    }

    /** 自动检查更新开关（默认开启）。 */
    fun setUpdateCheckEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoCheckEnabled = enabled
        _updateCheckEnabled.value = enabled
    }

    /** 自动下载更新包开关：检测到新版本就用国内源提前下好。 */
    fun setAutoDownloadEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoDownloadEnabled = enabled
        _autoDownloadEnabled.value = enabled
    }

    /** 下载后自动静默安装开关（需 Shizuku 授权；覆盖安装会重启 App）。 */
    fun setAutoInstallEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoInstallEnabled = enabled
        _autoInstallEnabled.value = enabled
    }

    /** 更新通道：正式版 / 测试版。 */
    fun setUpdateCheckChannel(channel: UpdateChannel) {
        updateCheckSettingsRepository.channel = channel
        _updateCheckChannel.value = channel
    }

    /**
     * 判断日志行是否属于某个 MCP server：只认 MCP 组件写下的日志（tag 以 `Mcp` 开头，
     * 如 McpManager/McpClient/McpStdioTransport/McpTool），且消息里引用了该 server——
     * 要么是消息前缀 `[名字]`，要么是命名空间工具名 `mcp__名字__`。
     * 不能只按 server 名做子串匹配：那会把任何恰好提到该名字的无关日志一并捞进来。
     */
    private fun isMcpServerLogLine(line: String, serverName: String): Boolean {
        if (!line.contains("[Mcp")) return false
        return line.contains("[$serverName]") || line.contains("mcp__${serverName}__")
    }

    /**
     * 标准日志行的开头（时间 + 等级），用于区分堆栈续行。与 FileLogger 落盘格式对齐：
     * 头行固定以 `yyyy-MM-dd HH:mm:ss.SSS LEVEL [tag] ` 起头，堆栈续行（`\tat ...`、`Caused by:`）不带该前缀。
     * 锚定行首：时间戳只会出现在行首，不锚定就会把续行中间碰巧出现的“时间 + 等级”误当成新头行，
     * 连带把它后面的堆栈当成“另一条不保留的行”丢掉。
     * 注意：续行自身以时间戳开头时（MCP 把服务端原始日志原样写进消息）仍会被当成头行，
     * 这是无标记格式的固有歧义，与上游行为一致。
     */
    private val logLinePrefixRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} (?:VERBOSE|DEBUG|INFO|WARN|ERROR) """)

    /**
     * 按 MCP server 过滤日志：命中的标准日志行连同其后的堆栈续行一并保留。
     * 堆栈续行本身不含 tag 与 server 名，逐行匹配会把报错的堆栈整段丢掉。
     */
    private fun filterMcpServerLines(rawLines: List<String>, serverName: String): List<String> {
        val result = ArrayList<String>()
        var keepContinuation = false
        for (line in rawLines) {
            if (logLinePrefixRegex.containsMatchIn(line)) {
                keepContinuation = isMcpServerLogLine(line, serverName)
                if (keepContinuation) result.add(line)
            } else if (keepContinuation) {
                result.add(line)
            }
        }
        return result
    }

    fun refreshLogs(filterServerName: String? = _logViewerState.value.filterServerName, silent: Boolean = false) {
        loadLogs(
            filterServerName = filterServerName?.takeIf { it.isNotBlank() },
            preferredFileName = _logViewerState.value.selectedFileName,
            silent = silent
        )
    }

    fun selectLogFile(fileName: String) {
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = fileName
        )
    }

    private fun loadLogs(filterServerName: String?, preferredFileName: String?, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _logViewerState.update {
                    it.copy(
                        loading = true,
                        filterServerName = filterServerName,
                        error = null
                    )
                }
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val files = FileLogger.listLogFiles()
                    val todayName = "log-${java.time.LocalDate.now()}.txt"
                    val selected = files.firstOrNull { it.name == preferredFileName }
                        ?: files.firstOrNull { it.name == todayName }
                        ?: files.lastOrNull()
                    if (selected == null) {
                        return@runCatching LogViewerUiState(
                            filterServerName = filterServerName,
                            error = context.getString(R.string.settings_log_no_files)
                        )
                    }

                    val rawLines = selected.readLines(Charsets.UTF_8)
                    val filteredLines = if (filterServerName.isNullOrBlank()) {
                        rawLines
                    } else {
                        filterMcpServerLines(rawLines, filterServerName)
                    }
                    val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

                    LogViewerUiState(
                        files = files.map { it.name },
                        selectedFileName = selected.name,
                        filterServerName = filterServerName,
                        content = visibleLines.joinToString("\n"),
                        totalLines = filteredLines.size,
                        shownLines = visibleLines.size
                    )
                }.getOrElse { e ->
                    LogViewerUiState(
                        filterServerName = filterServerName,
                        error = context.getString(R.string.settings_log_read_failed, e.message ?: "")
                    )
                }
            }
            _logViewerState.value = state
        }
    }

    // 仅持久化标志位——启停 Service 由 AIEditorApp 监听 enabledFlow 统一完成。
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keepaliveSettingsRepository.setEnabled(enabled)
        }
    }

    // 仅持久化标志位——窗口 FLAG_KEEP_SCREEN_ON 的增删由 MainActivity 监听 enabledFlow 统一完成。
    fun setScreenOnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            screenOnSettingsRepository.setEnabled(enabled)
        }
    }

    fun setAgentSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentSoundSettingsRepository.setEnabled(enabled)
        }
    }

    /** 拉取模型成功后是否自动移除远端已不存在的本地模型。 */
    fun setAutoRemoveStaleModels(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setAutoRemoveStaleModels(enabled)
        }
    }

    /** 启动（含切换工作区）时进入新会话还是最近会话。 */
    fun setStartupSessionMode(mode: StartupSessionMode) {
        viewModelScope.launch {
            generalSettingsRepository.setStartupSessionMode(mode)
        }
    }

    /** 流式请求首字超时（秒）；0 表示不限制。 */
    fun setFirstByteTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setFirstByteTimeoutSec(sec)
        }
    }

    /** 流式响应相邻数据块间隔超时（秒）；0 表示不限制。 */
    fun setStreamIdleTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setStreamIdleTimeoutSec(sec)
        }
    }

    /** 网络请求最大重试次数；0 表示不重试。 */
    fun setMaxNetworkRetries(count: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setMaxNetworkRetries(count)
        }
    }

    /** 回车键是否直接发送消息。 */
    fun setEnterToSend(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setEnterToSend(enabled)
        }
    }

    /** 自动压缩触发阈值（上下文窗口百分比，1..100）。 */
    fun setCompactionThresholdPercent(percent: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setCompactionThresholdPercent(percent)
        }
    }

    /** 上下文保留轮数：只把最近 N 轮发给模型，0 表示不裁剪。 */
    fun setContextTrimRounds(rounds: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setContextTrimRounds(rounds)
        }
    }

    /** 软精简触发阈值（上下文窗口百分比，1..100）：达到即先精简历史工具输出，不调摘要模型。 */
    fun setSoftCompactionThresholdPercent(percent: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setSoftCompactionThresholdPercent(percent)
        }
    }

    /** sendFile 单个文件大小上限（MB，不小于 1）。 */
    fun setSendFileMaxSizeMb(mb: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setSendFileMaxSizeMb(mb)
        }
    }

    /** 移除外部本地工作区时是否一并删除其聊天记录。 */
    fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setDeleteExternalWorkspaceSessions(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    fun setThemePreset(id: String) {
        viewModelScope.launch {
            themeSettingsRepository.setThemePresetId(id)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themeSettingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    /** 选择新背景图（拷贝到私有目录后替换）。 */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            backgroundSettingsRepository.setBackgroundImage(uri)
        }
    }

    /** 移除背景图。 */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundSettingsRepository.clearBackground()
        }
    }

    /**
     * 调节背景图透明度（0~0.2，实际范围由 BackgroundSettingsRepository 的 MIN_ALPHA/MAX_ALPHA 决定）。
     *
     * 滑块拖动会连续打进数十个值，DataStore 的写是串行的，逐个落盘会积压出肉眼可见的延迟，
     * 回读又会把整个设置页带着重组。此处只保留最后一个值，停手约 80ms 后写一次。
     */
    fun setBackgroundAlpha(alpha: Float) {
        backgroundAlphaWriteJob?.cancel()
        backgroundAlphaWriteJob = viewModelScope.launch {
            delay(BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS)
            backgroundSettingsRepository.setBackgroundAlpha(alpha)
        }
    }

    /** 设置应用语言；tag 为空字符串或 null 表示跟随系统。 */
    fun setLanguage(tag: String?) {
        viewModelScope.launch {
            languageSettingsRepository.setLanguage(tag?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * 切换当前选中的容器 profile，并按其 [ContainerProfile.mode] 同步切全局执行模式。
     *
     * 本地镜像 → [ExecutionMode.LOCAL_PROOT]；远程 SSH 镜像 → [ExecutionMode.REMOTE_SSH]，
     * 连接参数由 ActiveRemoteConnectionResolver 按激活 profile 实时解析，AIEditorApp 的常驻订阅据此重连。
     * 委托层每次调用读 holder，切换即时生效，无需重启。
     */
    fun setActiveContainerProfile(id: String) {
        viewModelScope.launch {
            applyProfile(id)
        }
    }

    /** 设置远程工作区模式下的默认容器（仅本地 PRoot 容器可选）。 */
    fun setDefaultContainerId(id: String) {
        viewModelScope.launch {
            containerSettingsRepository.setDefaultContainerId(id)
        }
    }

    /** 把 [id] 应用为当前激活 profile：持久化 + 按 mode 切执行模式。找不到时回退列表第一个，再兜底内置。 */
    private suspend fun applyProfile(id: String) {
        val profile = _customProfiles.value.firstOrNull { it.id == id }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == id }
            ?: _customProfiles.value.firstOrNull()
            ?: return
        containerSettingsRepository.setActiveProfile(profile.id)
        when (profile.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
            }

            ExecutionMode.REMOTE_SSH -> {
                if (profile.rootfsSource !is RootfsSource.RemoteSsh) return
                // 连接参数由 ActiveRemoteConnectionResolver 按激活 profile 实时解析，AIEditorApp 的常驻订阅据此重连。
                executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
            }
        }
    }

    // ── 容器镜像：操作一律转到 [ContainerImageController] ──

    /** 切换全局下载源（官方/华为云/阿里云/腾讯云等）。 */
    fun setImageSource(sourceId: String) = containerImageController.setSource(sourceId)

    /** 源显示名：按界面语言取目录 JSON 里定义的 name，跟随系统语言时按系统语言。 */
    fun sourceDisplayName(sourceId: String, languageTag: String?): String =
        containerImageController.sourceDisplayName(sourceId, languageTag)

    fun startContainerImageDownload(entry: ContainerImageEntry, sourceId: String) =
        containerImageController.startDownload(entry, sourceId)

    fun cancelContainerImageDownload() = containerImageController.cancelDownload()

    fun importDownloadedImage(entryId: String, fileUri: String) =
        containerImageController.importDownloaded(entryId, fileUri)

    fun deleteDownloadedImage(entryId: String) = containerImageController.deleteDownloaded(entryId)

    /** 批量删除已下载/已安装的镜像（连同本地 rootfs 文件；正在下载的会被取消）。 */
    fun deleteDownloadedImages(entryIds: Collection<String>) {
        containerImageController.deleteDownloaded(entryIds)
    }

    /** 重置容器：内置恢复出厂（清覆盖配置 + 删 rootfs），自定义本地镜像删 rootfs 下次重新解压。远程 SSH 无本地数据，UI 不提供入口。 */
    fun resetContainer(profile: ContainerProfile) {
        if (_containerReset.value != null) return
        viewModelScope.launch {
            if (profile.isBuiltin) {
                containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
            }
            withResetProgress(profile) { onProgress ->
                containerInstaller.resetRootfs(profile, onProgress)
            }
            // rootfs 重建后 stdio server 还挂在旧根上；同 profile 重置不触发 profile flow，需主动重载。
            mcpManager.reloadStdioServers()
        }
    }

    /** 删 profile 附带的 rootfs 清理，带进度；内置与远程 SSH 没本地 rootfs 可删，不弹进度框。 */
    private suspend fun cleanRootfsWithProgress(profile: ContainerProfile) {
        if (profile.isBuiltin || profile.rootfsSource is RootfsSource.RemoteSsh) {
            containerInstaller.deleteCustomRootfs(profile)
            return
        }
        withResetProgress(profile) { onProgress ->
            containerInstaller.deleteCustomRootfs(profile, onProgress)
        }
    }

    /**
     * 包住一次 rootfs 删除：期间把已删条目数写进 [_containerReset] 驱动进度框，结束后清掉。
     * 大容器删一遍要几十秒，删除本体在 IO 线程跑，界面只等不卡。
     */
    private suspend fun withResetProgress(
        profile: ContainerProfile,
        block: suspend ((Int) -> Unit) -> Unit
    ) {
        _containerReset.value = ContainerResetUiState(profile.name, 0)
        try {
            block { deleted -> _containerReset.value = ContainerResetUiState(profile.name, deleted) }
        } finally {
            _containerReset.value = null
        }
    }

    /** 空态恢复：把内置 Alpine 默认配置重新加回列表。 */
    fun restoreBuiltinAlpine() {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        }
    }

    /** 保存（新增/覆盖）自定义容器 profile。 */
    fun saveCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 容器列表长按拖拽排序：内存先重排（列表已重排 UI 才重绘，避免松手瞬间回跳），再异步持久化新顺序。 */
    fun reorderContainerProfiles(fromIndex: Int, toIndex: Int) {
        val reordered = _customProfiles.value.moved(fromIndex, toIndex) ?: return
        _customProfiles.value = reordered
        viewModelScope.launch { containerSettingsRepository.reorderCustomProfiles(reordered.map { it.id }) }
    }

    /** 编辑自定义 profile：覆盖配置；若镜像来源变了则删旧 rootfs 触发重新解压。 */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                cleanRootfsWithProgress(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 删除容器 profile（内置 Alpine 也可删，删光后由空态恢复），连带清理本地 rootfs。 */
    fun deleteContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            cleanRootfsWithProgress(profile)
            if (_activeProfileId.value == profile.id) {
                // 删除的是当前激活项：切到剩余第一个；列表空则回退内置 id（引擎 Alpine 兜底）
                val remaining = _customProfiles.value.filterNot { it.id == profile.id }
                applyProfile(remaining.firstOrNull()?.id ?: ContainerProfile.BUILTIN_ID)
            }
        }
    }

    /** 标记公告已展示：把当前内容哈希写入存储，下次内容不变则不再弹；内容更新哈希变自然重新弹。 */
    fun markContainerAnnouncementShown() {
        viewModelScope.launch {
            containerSettingsRepository.markAnnouncementShown(sha256(_containerAnnouncementText.value))
        }
    }

    /** 设置识图模型；providerId 留空等同 [clearVisionModel]（跟随聊天模型）。 */
    fun setVisionModel(providerId: String, model: String) {
        viewModelScope.launch {
            visionModelSettingsRepository.setVisionModel(providerId, model)
        }
    }

    /** 清空识图模型——回退到跟随当前聊天模型。 */
    fun clearVisionModel() {
        viewModelScope.launch {
            visionModelSettingsRepository.clear()
        }
    }

    /** 设置语音识别模型；providerId 留空等同 [clearVoiceSttModel]（回退内置离线模型）。 */
    fun setVoiceSttModel(providerId: String, model: String) {
        viewModelScope.launch {
            voiceSttSettingsRepository.setSttModel(providerId, model)
        }
    }

    /** 清空语音识别模型——回退到内置离线模型。 */
    fun clearVoiceSttModel() {
        viewModelScope.launch {
            voiceSttSettingsRepository.clear()
        }
    }

    /** 设置语音合成模型与音色；providerId 留空等同 [clearVoiceTtsModel]。 */
    fun setVoiceTtsModel(providerId: String, model: String, voice: String) {
        viewModelScope.launch {
            voiceTtsSettingsRepository.setTtsModel(providerId, model, voice)
        }
    }

    /**
     * 切换「为无视觉模型自动识别图片」：同样以存储里的真实值为准取反，
     * 不按界面上的 checked 算（界面状态可能过期，会写成同一个值）。
     */
    fun toggleOcrForTextOnlyModels() {
        viewModelScope.launch {
            val current = runCatching { generalSettingsRepository.ocrForTextOnlyModels() }.getOrDefault(true)
            generalSettingsRepository.setOcrForTextOnlyModels(!current)
            _ocrForTextOnlyModels.value = !current
        }
    }

    /** 切换自动朗读。 */
    fun setAutoReadAloud(enabled: Boolean) {
        viewModelScope.launch {
            voiceTtsSettingsRepository.setAutoReadAloud(enabled)
        }
    }

    /**
     * 切换自动朗读：以存储里的真实值为准取反。
     *
     * 不能按界面上的值取反——界面状态一旦过期（收流断过），它显示的是错的值，
     * 用户每点一次都会写回同一个值，表现就是「开关点不动、一直关不掉」。
     */
    fun toggleAutoReadAloud() {
        viewModelScope.launch {
            val current = runCatching { voiceTtsSettingsRepository.isAutoReadAloud() }.getOrDefault(false)
            voiceTtsSettingsRepository.setAutoReadAloud(!current)
            _autoReadAloud.value = !current
        }
    }

    /** 只改音色，保留当前合成模型选择。 */
    fun setVoiceTtsVoice(voice: String) {
        viewModelScope.launch {
            voiceTtsSettingsRepository.setVoice(voice)
        }
    }

    /** 清空语音合成模型——AI 回复不再朗读。 */
    fun clearVoiceTtsModel() {
        viewModelScope.launch {
            voiceTtsSettingsRepository.clear()
        }
    }

    /** 设置压缩专用模型；providerId 留空等同 [clearCompactionModel]（跟随聊天模型）。 */
    fun setCompactionModel(providerId: String, model: String) {
        viewModelScope.launch {
            compactionModelSettingsRepository.setCompactionModel(providerId, model)
        }
    }

    /** 清空压缩专用模型——回退到跟随当前聊天模型。 */
    fun clearCompactionModel() {
        viewModelScope.launch {
            compactionModelSettingsRepository.clear()
        }
    }

    /** 设置标题总结专用模型；providerId 留空等同 [clearTitleModel]（跟随聊天模型）。 */
    fun setTitleModel(providerId: String, model: String) {
        viewModelScope.launch {
            titleModelSettingsRepository.setTitleModel(providerId, model)
        }
    }

    /** 清空标题总结专用模型——回退到跟随当前聊天模型。 */
    fun clearTitleModel() {
        viewModelScope.launch {
            titleModelSettingsRepository.clear()
        }
    }

    /** 设置生图专用模型；providerId 留空等同 [clearImageGenModel]（未配置）。 */
    fun setImageGenModel(providerId: String, model: String) {
        viewModelScope.launch {
            imageGenModelSettingsRepository.setImageGenModel(providerId, model)
        }
    }

    /** 清空生图专用模型——回到未配置状态（生图工具将提示先配置）。 */
    fun clearImageGenModel() {
        viewModelScope.launch {
            imageGenModelSettingsRepository.clear()
        }
    }

    // ── Token 统计：状态与聚合都在 [TokenStatsController]，这里只转发操作 ──

    fun setTokenStatsPeriod(period: TokenStatsPeriod) = tokenStatsController.setPeriod(period)

    /** 设置供应商筛选（null 为全部）。 */
    fun setFilterProviderId(providerId: String?) = tokenStatsController.setFilterProviderId(providerId)

    /** 设置模型筛选（null 为全部）。 */
    fun setFilterModel(model: String?) = tokenStatsController.setFilterModel(model)

    /** 清除所有筛选条件。 */
    fun clearTokenStatsFilters() = tokenStatsController.clearFilters()

    /** 调用明细翻页；越界时钳制到合法范围。 */
    fun setTokenStatsPage(page: Int) = tokenStatsController.setCallPage(page)

    /** 渠道统计翻页；越界时钳制到合法范围。 */
    fun setProviderStatsPage(page: Int) = tokenStatsController.setProviderPage(page)

    /** 模型统计翻页；越界时钳制到合法范围。 */
    fun setModelStatsPage(page: Int) = tokenStatsController.setModelPage(page)

    /** 清空全部调用记录（Token 统计页右上角重置）；Room Flow 自动把各聚合刷新为空。 */
    fun resetTokenStats() = tokenStatsController.reset()

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            val removedModels = (repository.getProviderById(provider.id)?.models ?: emptyList())
                .toSet() - provider.models.toSet()
            repository.saveProvider(provider)
            if (removedModels.isNotEmpty()) cleanupRemovedModels(provider, removedModels)
            // 保存后重置该提供商在内存中的面板状态，以便主页即时以最新脚本与配置重新加载
            _providerDashboards.update { it - provider.id }
        }
    }

    /**
     * 模型被移出列表后，清掉仍指向它的各处选中态，否则主页与各专用模型仍会拿已删除的模型发请求。
     * 会话绑定模型不在此清理，由 resolveProviderConfig / AIChatPanel 读取时校验回退，避免遍历全部历史会话。
     */
    private suspend fun cleanupRemovedModels(provider: AIProviderConfig, removed: Set<String>) {
        if (provider.selectedModel in removed) {
            repository.setSelectedModel(provider.id, provider.models.firstOrNull() ?: "")
        }
        if (defaultModelSettingsRepository.getDefaultProviderId() == provider.id &&
            defaultModelSettingsRepository.getDefaultModel() in removed
        ) {
            defaultModelSettingsRepository.clear()
        }
        if (visionModelSettingsRepository.getVisionProviderId() == provider.id &&
            visionModelSettingsRepository.getVisionModel() in removed
        ) {
            visionModelSettingsRepository.clear()
        }
        if (voiceSttSettingsRepository.getSttProviderId() == provider.id &&
            voiceSttSettingsRepository.getSttModel() in removed
        ) {
            voiceSttSettingsRepository.clear()
        }
        if (voiceTtsSettingsRepository.getTtsProviderId() == provider.id &&
            voiceTtsSettingsRepository.getTtsModel() in removed
        ) {
            voiceTtsSettingsRepository.clear()
        }
        if (imageGenModelSettingsRepository.getImageGenProviderId() == provider.id &&
            imageGenModelSettingsRepository.getImageGenModel() in removed
        ) {
            imageGenModelSettingsRepository.clear()
        }
        if (compactionModelSettingsRepository.getCompactionProviderId() == provider.id &&
            compactionModelSettingsRepository.getCompactionModel() in removed
        ) {
            compactionModelSettingsRepository.clear()
        }
        if (titleModelSettingsRepository.getTitleProviderId() == provider.id &&
            titleModelSettingsRepository.getTitleModel() in removed
        ) {
            titleModelSettingsRepository.clear()
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    /**
     * 批量删除提供商。DAO 目前只有单行删除，这里在一次协程里逐条删；
     * 列表由 Room Flow 推送，删完自然是最新结果（无需手动刷新）。
     */
    fun deleteProviders(ids: Collection<String>) {
        val targets = ids.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch { repository.deleteProviders(targets) }
    }

    /** 批量启用/停用提供商，同一次协程里写完，列表由 Room Flow 推送。 */
    fun setProvidersEnabled(ids: Collection<String>, enabled: Boolean) {
        val targets = ids.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch { repository.setProvidersEnabled(targets, enabled) }
    }

    /** 提供商列表长按拖拽排序：内存先重排（reorderable 库要求 onMove 返回前列表已更新，否则拖拽项闪烁），再异步持久化 sortOrder。 */
    fun reorderProviders(fromIndex: Int, toIndex: Int) {
        val reordered = _providers.value.moved(fromIndex, toIndex) ?: return
        _providers.value = reordered
        viewModelScope.launch { repository.reorderProviders(reordered) }
    }

    fun fetchModels(provider: AIProviderConfig) {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            modelApiService.fetchModels(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.customHeaders)
                .onSuccess { result ->
                    _fetchState.value = FetchState.Success(result.models, result.debugInfo)
                    resolveModelMetadata(provider.id, provider.type, result.models)
                }
                .onFailure { error ->
                    val debug = (error as? com.aharou.feature.settings.data.remote.FetchModelsException)?.debugInfo
                    _fetchState.value = FetchState.Error(
                        error.message ?: context.getString(R.string.settings_models_fetch_failed),
                        debug
                    )
                }
        }
    }

    fun resolveModelMetadata(providerId: String, type: ProviderType, modelIds: List<String>) {
        val normalizedIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(providerId, type, normalizedIds)
            _modelMetadata.update { current ->
                current + metadata.mapKeys { (model, _) -> modelMetadataKey(providerId, model) }
            }
        }
    }

    /**
     * 加载所有已启用 provider 的全部模型元数据，合并进 [modelMetadata]。
     * 供「识图模型」等需要展示跨 provider 模型能力标签的页面在进入时调用--
     * 这些页面不像 ProviderEditor 那样会在编辑单个 provider 时顺带 resolve，
     * 不主动加载则 map 为空、所有模型都被误判为不支持图片。
     *
     * 实现要点（避免设置页卡顿）：
     * - 单协程顺序处理各 provider：首个 resolveAll 触发 catalog 加载（内存/磁盘 24h 缓存/内置 assets）并写入内存缓存，
     *   后续 provider 命中缓存。resolve 链路不发网络请求，models.dev 刷新统一由 App 启动时异步触发。
     * - 全部解析完一次性 update，避免多次 emit 导致设置页反复重组。
     */
    fun loadAllModelMetadata() {
        val enabled = _providers.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, ModelMetadata>()
            for (provider in enabled) {
                val ids = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                modelMetadataService.resolveAll(provider.id, provider.type, ids)
                    .forEach { (model, meta) -> resolved[modelMetadataKey(provider.id, model)] = meta }
            }
            if (resolved.isNotEmpty()) {
                _modelMetadata.update { it + resolved }
            }
        }
    }

    fun resetFetchState() {
        _fetchState.value = FetchState.Idle
    }

    fun testModel(provider: AIProviderConfig, model: String) {
        viewModelScope.launch {
            _testing.update { it + model }
            val result = modelApiService.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    /**
     * 加载可用面板脚本列表（打开脚本选择器时调用）。经引擎在 IO 线程枚举，
     * 远程模式下即远端 `~/.aharou/scripts`，避免在主线程上做 SFTP 枚举。
     */
    fun loadDashboardScripts() {
        viewModelScope.launch {
            _dashboardScripts.value = withContext(Dispatchers.IO) {
                providerDashboardRunner.listAvailableScripts()
            }
        }
    }

    /** 删除 `~/.aharou/scripts/` 下的一个面板脚本；返回是否真的删掉。 */
    fun deleteDashboardScript(name: String): Boolean {
        return providerDashboardRunner.deleteScript(name)
    }

    fun testDashboardScript(provider: AIProviderConfig, scriptPath: String) {
        viewModelScope.launch {
            _dashboardTestState.value = ProviderDashboardState.Loading
            providerDashboardRunner.runScript(provider, scriptPath)
                .onSuccess { result ->
                    _dashboardTestState.value = ProviderDashboardState.Success(result)
                }
                .onFailure { error ->
                    _dashboardTestState.value = ProviderDashboardState.Error(
                        error.message ?: context.getString(R.string.dashboard_exec_failed),
                        error.localizedMessage ?: ""
                    )
                }
        }
    }

    fun clearDashboardTestState() {
        _dashboardTestState.value = ProviderDashboardState.Idle
    }

    fun refreshProviderDashboard(
        provider: AIProviderConfig,
        context: DashboardContext? = null,
        force: Boolean = false
    ) {
        if (provider.dashboardScriptPath.isBlank()) {
            _providerDashboards.update { it - provider.id }
            return
        }
        val current = _providerDashboards.value[provider.id]
        if (!force && current is ProviderDashboardState.Success) return
        if (current is ProviderDashboardState.Loading) return

        // 已有 Success 时不切 Loading，避免 UI 高度塌缩再恢复（收起→展开闪烁）
        val showLoading = current !is ProviderDashboardState.Success
        viewModelScope.launch {
            if (showLoading) {
                _providerDashboards.update { it + (provider.id to ProviderDashboardState.Loading) }
            }
            providerDashboardRunner.runScript(provider, context = context)
                .onSuccess { result ->
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Success(result)) }
                }
                .onFailure { error ->
                    // 函数参数 context: DashboardContext 遮蔽了类的 ApplicationContext；
                    // 且此处位于 launch{} 内，this 已变为 CoroutineScope，必须用类标签限定
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Error(error.message ?: this@SettingsViewModel.context.getString(R.string.dashboard_query_failed))) }
                }
        }
    }

    fun deleteGlobalRule(rule: PermissionRule) {
        viewModelScope.launch { permissionRulesRepository.removeGlobalRule(rule) }
    }

    fun deleteProjectRule(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.removeProjectRule(name, rule) }
    }

    fun promoteRuleToGlobal(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.promoteToGlobal(name, rule) }
    }

    fun setDisableSafetyInterception(disabled: Boolean) {
        viewModelScope.launch { toolSafetySettingsRepository.setDisableSafetyInterception(disabled) }
    }
}

/** 读取公告 md（zh 用中文版，其余英文版）；跟随系统语言时按系统语言判断。读取失败返回空串（空内容不弹窗）。 */
private fun loadContainerAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/container-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** SHA-256 十六进制摘要，用于公告内容比对：内容更新则哈希变，自动重新弹出。 */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** 文件名的扩展名判定已随技能域搬到 SkillModels.kt。 */

/** 拖拽排序后的列表：原位或越界返回 null，否则返回移除后插入的新列表。 */
private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T>? =
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) null
    else toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
