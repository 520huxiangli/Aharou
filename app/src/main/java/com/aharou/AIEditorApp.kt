package com.aharou

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.aharou.core.net.AppProxy
import com.aharou.core.util.AILogger
import com.aharou.core.util.CrashRecorder
import com.aharou.core.util.FileLogger
import com.aharou.core.util.TombstoneParser
import com.aharou.core.util.WorkManagerFileLogger
import net.schmizz.sshj.common.SecurityUtils
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.LanguageSettingsRepository
import com.aharou.feature.settings.data.repository.LogSettingsRepository
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.terminal.domain.KeepaliveWorker
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application(), Configuration.Provider {

    companion object {
        const val TAG = "AIEditorApp"
        const val LANG_PREFS = "language_prefs_sync"
        const val LANG_KEY = "language_tag"
        /** 崩溃堆栈经 Intent extra 传递，超出 Binder 上限会抛异常，需截断。 */
        const val MAX_CRASH_STACK_CHARS = 50_000
        private const val CRASH_PREFS = "crash_state"
        private const val KEY_CRASH_UI_SHOWING = "crash_ui_showing"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val KEY_CRASH_COUNT = "crash_count"
        /** ApplicationExitInfo 增量游标：已处理过的最大退出时间戳，避免重复上报历史退出。 */
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
        /** ApplicationExitInfo 读取的历史退出记录条数上限。 */
        private const val MAX_EXIT_RECORDS = 20
        /** 单条退出 trace 落盘时保留的字节上限（按字节截断，避免超大 trace 撑爆内存）。 */
        private const val TRACE_HEAD_BYTES = 16 * 1024

        /** 崩溃 tombstone 落盘目录保留的文件数上限（超出则删除最旧的）。 */
        private const val MAX_CRASH_TRACE_FILES = 20
        /**
         * 无 Java 异常、只能靠 ApplicationExitInfo 补记的退出原因（API 30+）。
         * 不包含 REASON_CRASH（Java 崩溃已由 installCrashHandler + CrashActivity 完整记录）。
         */
        private val ABNORMAL_EXIT_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_SIGNALED
        )
        /** 10 秒内的连续崩溃视为密集崩溃。 */
        private const val CRASH_RESET_WINDOW_MS = 10_000L
        /** 密集崩溃达到 3 次触发硬熔断退出，杜绝死循环。 */
        private const val MAX_CONSECUTIVE_CRASHES = 3

        /** 当前导航路由（MainActivity 写入），崩溃时随报告带出，用于定位崩溃页面。 */
        @Volatile
        var currentRoute: String? = null

        /** 当前工作区模式（本地 PRoot / 远程 SSH），崩溃时随报告带出。 */
        @Volatile
        var currentWorkspaceMode: String? = null

        /** 重置崩溃状态（主进程健康运行或用户主动从错误页重启时调用）。 */
        fun resetCrashState(context: android.content.Context) {
            context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CRASH_UI_SHOWING)
                .remove(KEY_CRASH_COUNT)
                .remove(KEY_LAST_CRASH_TIME)
                .apply()
        }

        /** 向后兼容的旧别名，保留供外部调用。 */
        fun resetCrashUiFlag(context: android.content.Context) {
            resetCrashState(context)
        }

        /** 兼容获取当前进程名。 */
        fun getProcessNameCompat(context: android.content.Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            return runCatching {
                val cmdline = java.io.File("/proc/self/cmdline")
                if (cmdline.exists()) {
                    cmdline.readText().trim().trim { it <= ' ' || it == '\u0000' }
                } else null
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val activityThread = Class.forName("android.app.ActivityThread")
                    val method = activityThread.getMethod("currentProcessName")
                    method.invoke(null) as? String
                }.getOrNull()
                ?: context.packageName
        }

        /** 判断当前进程是否为专门渲染崩溃错误页的子进程。 */
        fun isCrashProcess(context: android.content.Context): Boolean =
            getProcessNameCompat(context).endsWith(":crash")
    }

    override fun attachBaseContext(base: android.content.Context) {
        // 最早入口：在任何 Hilt 注入/业务初始化之前就绪日志与崩溃落盘。
        // 启动早期（如 Hilt 注入链实例化 @Singleton 工具）的崩溃若发生在 FileLogger 初始化之前
        // 会不留任何痕迹，故把日志与全局崩溃处理器提到 attachBaseContext 最前。
        FileLogger.init(base)
        AILogger.init(base)
        // WorkManager 的 setLogger 只认首次设置，必须赶在它自己初始化（ContentProvider）之前
        WorkManagerFileLogger.install()
        installCrashHandler()
        // 全局代理入口：必须在任何 Hilt 注入 / OkHttpClient 构建之前设置，
        // 使 App 侧全部 HTTP 链路（对话、MCP、更新检查等）按需走全局/提供商代理。
        // 用 base 而非下文才定义的局部 context：attachBaseContext 阶段 base 即合法 Context。
        AppProxy.applyGlobal(base)
        val tag = base.getSharedPreferences(LANG_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(LANG_KEY, null)
        val context = if (tag.isNullOrBlank()) {
            base
        } else {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            base.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    /** Hilt 字段注入：在 [onCreate] 的 super 调用后即可用。 */
    @Inject
    lateinit var logSettings: LogSettingsRepository

    /** 后台保活开关持久化。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    /** 跨设备协作控制器：启动时按配置恢复前台服务（见 onCreate 的 startIfEnabled 调用）。 */
    @Inject
    lateinit var remoteDeviceController: com.aharou.feature.remotedevice.RemoteDeviceController

    /** WorkManager Worker 工厂：使 @HiltWorker（KeepaliveWorker）能注入依赖。 */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** MCP 生命周期总管：启动即连接已配置的远程 server。 */
    @Inject
    lateinit var mcpManager: McpManager

    /** 编辑器语言服务器管理器：冷启动时按进程账本清扫上次进程被杀后遗留的语言服务器。 */
    @Inject
    lateinit var editorLspManager: EditorLspManager

    /** 应用设置字段注册（语言等）：Agent 经配置通道即可改动，无需任何系统权限。 */
    @Inject
    lateinit var configAppFields: com.aharou.feature.settings.data.ConfigAppFields

    /** 外观 / 编辑器 / 终端 / 声音 / 保活字段注册。 */
    @Inject
    lateinit var configAppearanceFields: com.aharou.feature.settings.data.ConfigAppearanceFields

    /** 全局代理与远程同步偏好字段注册。 */
    @Inject
    lateinit var configNetworkFields: com.aharou.feature.settings.data.ConfigNetworkFields

    /** 容器选择字段注册。 */
    @Inject
    lateinit var configContainerFields: com.aharou.feature.settings.data.ConfigContainerFields

    /** AI 供应商集合注册（动态子路径 + 增删）。 */
    @Inject
    lateinit var configProviderFields: com.aharou.feature.settings.data.ConfigProviderFields

    /** 远程连接与挂载集合注册。 */
    @Inject
    lateinit var configRemoteFields: com.aharou.feature.workspace.data.ConfigRemoteFields

    /** 各角色模型选择字段注册。 */
    @Inject
    lateinit var configModelRoleFields: com.aharou.feature.settings.data.ConfigModelRoleFields

    /** 环境变量集合注册。 */
    @Inject
    lateinit var configEnvFields: com.aharou.feature.settings.data.ConfigEnvFields

    /** 定时任务 → 配置通道。 */
    @Inject
    lateinit var configScheduledTaskFields: com.aharou.feature.agent.data.ConfigScheduledTaskFields
    /** 语音合成（TTS）字段注册。 */
    @Inject
    lateinit var configVoiceFields: com.aharou.feature.settings.data.ConfigVoiceFields

    /** 聊天气泡外观字段注册。 */
    @Inject
    lateinit var configChatFields: com.aharou.feature.agent.data.ConfigChatFields

    /** 浏览器偏好（UA / 视口 / 标签回收 / 页内开发者工具）字段注册。 */
    @Inject
    lateinit var configBrowserFields: com.aharou.feature.browser.data.ConfigBrowserFields

    /** Telegram 机器人字段注册。 */
    @Inject
    lateinit var configImFields: com.aharou.feature.im.data.ConfigImFields

    @Inject
    lateinit var configStewardFields: com.aharou.feature.agent.data.ConfigStewardFields

    @Inject
    lateinit var configSubAgentFields: com.aharou.feature.agent.data.ConfigSubAgentFields

    /** 冷启动时按开关重新拉起 IM 前台服务（否则每次进 App 都要手动关再开）。 */
    @Inject
    lateinit var imServiceRestore: com.aharou.feature.im.ImServiceRestore

    /** 桌面伙伴「小染」字段注册。 */
    @Inject
    lateinit var configPetFields: com.aharou.feature.pet.data.ConfigPetFields

    /** 跨设备协作字段注册（开关/端口/配对码/已配对设备）。 */
    @Inject
    lateinit var configRemoteDeviceFields: com.aharou.feature.remotedevice.data.ConfigRemoteDeviceFields

    /** 记忆同步字段注册（仓库地址 / 分支 / 上次结果与时间）。 */
    @Inject
    lateinit var configMemorySyncFields: com.aharou.feature.agent.data.ConfigMemorySyncFields

    /** 凭据类字段注册（GitHub 令牌 / 自动注入 / SSH 登录密钥 / 凭据库）。 */
    @Inject
    lateinit var configCredentialFields: com.aharou.feature.settings.data.ConfigCredentialFields

    /** 备份导出范围字段注册。 */
    @Inject
    lateinit var configBackupFields: com.aharou.feature.settings.data.ConfigBackupFields

    /** FTP 服务器字段注册。 */
    @Inject
    lateinit var configFtpFields: com.aharou.feature.settings.data.ConfigFtpFields

    /** 记忆的 AI 蒸馏：启动时后台判一次「是否到点」，到点才调模型。 */
    @Inject
    lateinit var memoryDistiller: com.aharou.feature.agent.domain.memory.MemoryDistiller

    /** 备份恢复：启动时清掉上次进程被杀时残留的解密暂存文件。 */
    @Inject
    lateinit var backupManager: com.aharou.feature.backup.domain.BackupManager

    /** MCP 服务器集合注册。 */
    @Inject
    lateinit var configMcpFields: com.aharou.feature.agent.data.ConfigMcpFields

    /** 技能启停集合注册。 */
    @Inject
    lateinit var configSkillFields: com.aharou.feature.agent.data.ConfigSkillFields

    /** 技能市场自定义源集合注册。 */
    @Inject
    lateinit var configSkillMarketFields: com.aharou.feature.agent.data.ConfigSkillMarketFields

    /** MCP 配置仓库：启动即监听 mcp.json 外部直接编辑，改动数秒内刷新列表并触发重连。 */
    @Inject
    lateinit var mcpConfigRepository: com.aharou.feature.agent.domain.mcp.McpConfigRepository

    /** 工具授权规则仓库：启动即监听 permissions.json 外部直接编辑，改动数秒内刷新规则。 */
    @Inject
    lateinit var permissionRulesRepository: com.aharou.feature.agent.domain.permission.PermissionRulesRepository

    /** 子代理任务状态机：把子代理生命周期事件落成持久化任务记录，并在启动时收尾上次进程遗留的任务。 */
    @Inject
    lateinit var subAgentTaskTracker: com.aharou.feature.agent.data.local.task.SubAgentTaskTracker

    /** 技能配置仓库：启动即监听技能目录与 skills.json 外部变更，改动数秒内刷新技能列表。 */
    @Inject
    lateinit var skillConfigRepository: com.aharou.feature.agent.domain.skill.SkillConfigRepository

    /** 共享知识库源清单：启动即后台对账最新清单（失败静默，兜底磁盘缓存与内置 assets）。 */
    @Inject
    lateinit var knowledgeCatalog: com.aharou.feature.agent.domain.knowledge.KnowledgeCatalog

    /** 定时任务调度：启动时按「有无启用任务」决定排周期检查还是取消，避免空转唤醒进程。 */
    @Inject
    lateinit var scheduledTaskScheduler: com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler

    /** 「应用启动」触发：进程启动后投递一次，语义同「开机」（只认启用中的任务）。 */
    @Inject
    lateinit var eventTriggerBus: com.aharou.feature.agent.domain.schedule.trigger.EventTriggerBus

    /** 记忆自动同步调度：默认开启的开关只有在这里排一次才真正生效，否则从没碰过设置页的用户永远不会同步。 */
    @Inject
    lateinit var memorySyncScheduler: com.aharou.feature.agent.domain.memory.MemorySyncScheduler

    /** 旧 Room git 凭据一次性迁移器：启动即把旧表数据写入 git-credentials 文件后删表（真源已迁到文件）。 */
    @Inject
    lateinit var legacyCredentialMigrator: com.aharou.feature.credentials.data.LegacyCredentialMigrator

    /** 敏感字段加密迁移器：启动即把历史明文的 API Key/密码/私钥文件加密回写（幂等）。 */
    @Inject
    lateinit var secretEncryptionMigrator: com.aharou.feature.settings.data.SecretEncryptionMigrator

    /** git 凭据仓库：启动即把旧版明文凭据文件迁移为编码格式。 */
    @Inject
    lateinit var fileCredentialRepository: com.aharou.feature.credentials.data.repository.FileCredentialRepository

    /** 三端 git 缺凭据的统一弹窗桥：监听容器内 credential helper 经文件 IPC 发来的未登录请求，
     *  暴露 StateFlow 供全局弹窗回填后回喂 git。必须在主线程启动（FileObserver 绑定主 Looper）。 */
    @Inject
    lateinit var credentialRequestBridge: com.aharou.feature.credentials.data.CredentialRequestBridge

    /** 执行模式仓库（本地 PRoot / 远程 SSH）。 */
    @Inject
    lateinit var executionModeRepository: com.aharou.feature.settings.data.repository.ExecutionModeRepository

    /** 应用语言偏好仓库：持久化用户选择的语言，供 attachBaseContext 同步读取。 */
    @Inject
    lateinit var languageSettings: LanguageSettingsRepository

    /** 执行模式同步缓存：启动时从 DataStore 读首帧注入 DI。 */
    @Inject
    lateinit var executionModeHolder: com.aharou.feature.settings.data.repository.ExecutionModeHolder

    /** 远程 SSH 连接管理器：远程模式下启动即用配置建立连接。 */
    @Inject
    lateinit var remoteSshConnection: com.aharou.feature.agent.domain.container.RemoteSshConnection

    /** 全局网络可用性监听：网络恢复/切换链路时驱动即时重连。 */
    @Inject
    lateinit var networkConnectivityObserver: com.aharou.core.net.NetworkConnectivityObserver

    /** 当前激活 profile 的连接配置解析器：连接/profile/模式变化时驱动重连。 */
    @Inject
    lateinit var activeRemoteConnectionResolver: com.aharou.feature.agent.domain.container.ActiveRemoteConnectionResolver

    /** 工作区仓库：SSH 重连成功后重新加载工作区。 */
    @Inject
    lateinit var workspaceRepository: com.aharou.feature.workspace.data.repository.WorkspaceRepository

    /** 连接与同步仓库：注入以在启动早期创建 @Singleton 实例，其内部跟随当前工作区自动连接/断开挂载。 */
    @Inject
    lateinit var remoteRepository: com.aharou.feature.workspace.domain.repository.RemoteRepository

    /** 模型元数据服务：启动即异步从本仓库拉取模型目录（12h 缓存，失败静默，兜底内置数据）。 */
    @Inject
    lateinit var modelMetadataService: ModelMetadataService

    /** 提供商级代理注册表：启动即构建 provider→代理 索引，供 AppProxy 按 target host 分派。 */
    @Inject
    lateinit var providerProxyRegistry: com.aharou.feature.settings.data.repository.ProviderProxyRegistry

    /** 可下载镜像目录：启动即异步刷新本仓库清单（12h 缓存，失败静默，兜底磁盘缓存与内置 assets）。 */
    @Inject
    lateinit var containerImageCatalog: com.aharou.feature.agent.domain.container.ContainerImageCatalog

    /** 技能市场源清单：启动后台刷新，加源/换源不必发版。 */
    @Inject
    lateinit var skillMarketCatalog: com.aharou.feature.agent.domain.skill.market.SkillMarketCatalog

    /** 插件市场源清单：同样启动即对账，索引放仓库里那个 JSON 就行。 */
    @Inject
    lateinit var pluginMarketCatalog: com.aharou.feature.plugin.domain.market.PluginMarketCatalog

    /** 灵动岛 / 流体云：把通话、Agent、后台终端三路运行态汇成一条 promoted ongoing 通知。
      * internal：它依赖同为 internal 的 VoiceCallSession，公开属性无法暴露该类型。 */
    @Inject
    internal lateinit var liveUpdateCoordinator: com.aharou.feature.agent.presentation.notification.LiveUpdateCoordinator

    /** 长驻作用域：持续把持久化的日志等级同步到 FileLogger。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        if (isCrashProcess(this)) {
            // 崩溃错误页运行在独立 :crash 进程中。
            // 坚决不执行 super.onCreate()（避免触发 Hilt 的 @Inject 字段注入与隐式业务类加载），
            // 也坚决不启动主进程业务协程、数据库访问或常驻服务，使错误页拥有纯净轻量的渲染环境。
            return
        }
        super.onCreate()
        logDeviceInfo()
        // 补记 Java 未捕获异常处理器覆盖不到的异常退出（native 崩溃 / ANR / 低内存被杀 / 被信号杀死）。
        appScope.launch { reportAbnormalExits() }

        // IM 机器人：开关是打开的就重新拉服务，冷启动不再需要用户手动切开关
        runCatching { imServiceRestore.restore(this, appScope) }
            .onFailure { FileLogger.w(TAG, "恢复 IM 机器人服务失败", it) }
        // Aharou 配置通道 + 人格（SOUL.md）：初始化注册表/审计；首次运行释放默认模板文件（外置可改）
        com.aharou.core.config.ConfigRegistry.init(this)
        // 应用设置字段（app.* / appearance.* / editor.* / terminal.* / sound.* / network.*）注册：Agent 经配置通道改自身设置（无系统权限依赖）
        val configRegistry = com.aharou.core.config.ConfigRegistry.get()
        configAppFields.registerInto(configRegistry)
        configAppearanceFields.registerInto(configRegistry)
        configNetworkFields.registerInto(configRegistry)
        configContainerFields.registerInto(configRegistry)
        configProviderFields.registerInto(configRegistry)
        configRemoteFields.registerInto(configRegistry)
        configMemorySyncFields.registerInto(configRegistry)
        configModelRoleFields.registerInto(configRegistry)
        configEnvFields.registerInto(configRegistry)
        configScheduledTaskFields.registerInto(configRegistry)
        configMcpFields.registerInto(configRegistry)
        configSkillFields.registerInto(configRegistry)
        configSkillMarketFields.registerInto(configRegistry)
        configVoiceFields.registerInto(configRegistry)
        configChatFields.registerInto(configRegistry)
        configBrowserFields.registerInto(configRegistry)
        configImFields.registerInto(configRegistry)
        configStewardFields.registerInto(configRegistry)
        configSubAgentFields.registerInto(configRegistry)
        configPetFields.registerInto(configRegistry)
        configRemoteDeviceFields.registerInto(configRegistry)
        configCredentialFields.registerInto(configRegistry)
        configBackupFields.registerInto(configRegistry)
        configFtpFields.registerInto(configRegistry)
        // 工作区项目级 .aicode 目录 → .aharou 一次性迁移（只搬不删）
        com.aharou.feature.agent.domain.container.ContainerInstaller.migrateProjectDirs(this)
        com.aharou.core.config.audit.ConfigAuditLog.init(this)
        com.aharou.core.soul.SoulStore.ensureExists(this)
        // 记忆的 AI 蒸馏：后台跑，失败静默。默认 7 天才跑一次，所以这里只是一次轻量判活。
        appScope.launch {
            runCatching { memoryDistiller.distillIfDue() }
        }
        // 读取 SOUL.md 到缓存（聊天身份行用）；改名/改图标后由保存路径刷新。
        com.aharou.core.soul.SoulStore.refreshCache(this)
        // 把提供商级代理注册表挂到 AppProxy（applyGlobal 已在 attachBaseContext 完成），
        // 此后按目标 host 分派 provider 专属代理；无 provider 配置时回退全局代理。
        AppProxy.registerProviderProxyRegistry(providerProxyRegistry)
        // 注册完整版 BouncyCastle 放后台 IO 协程，避免在主线程加载大量密码学类阻塞首屏；
        // 远程 SSH 连接在 appScope 中执行，使用前完成注册即可。
        appScope.launch(Dispatchers.IO) {
            registerBouncyCastle()
        }
        createNotificationChannels()
        // 灵动岛 / 流体云：启动即接管一条实时进度通知（不依赖小染开关）。
        liveUpdateCoordinator.start()
        // 启动凭据请求监听：容器内 credential helper 写来的 cred-req-* → 全局弹窗回填 → 回喂 git 续跑。
        credentialRequestBridge.start()
        // 启动即把最新的内置指南手册提取到私有配置目录
        appScope.launch {
            ContainerInstaller.extractDocs(this@AIEditorApp)
        }
        // 启动即把内置提示词全量释放到 ~/.aharou/prompts/（覆盖式，随 App 升级更新）；
        // 用户自定义覆盖放在 ~/.aharou/prompts.custom/，同名即覆盖，不被升级覆盖。
        appScope.launch {
            ContainerInstaller.extractPrompts(this@AIEditorApp)
        }
        // 启动即释放内置子代理定义（Explore）到 ~/.aharou/agents/；已存在不覆盖，用户改过或删掉都不会被升级拉回。
        appScope.launch {
            ContainerInstaller.extractAgents(this@AIEditorApp)
        }
        // 备份导入的解密暂存落在 cacheDir，进程在勾选弹窗期间被杀会留下明文（含凭据与聊天全文），启动即清。
        appScope.launch {
            runCatching { backupManager.cleanupStaleStagingFiles() }
        }
        // 启动即把旧 Room git 凭据一次性迁移到 git-credentials 文件（真源已迁到文件，删表由迁移器完成）。
        appScope.launch {
            legacyCredentialMigrator.migrateIfNeeded()
        }
        // 启动即把历史明文的敏感字段（API Key、密码、私钥文件）加密回写。
        appScope.launch {
            secretEncryptionMigrator.migrateIfNeeded()
        }
        // 启动即处理 git 凭据的改名遗留与格式迁移：先并入旧目录 aicode 下的文件，再统一编码，顺序不能反。
        appScope.launch {
            fileCredentialRepository.migrateFromLegacyDir()
            fileCredentialRepository.migrateToEncoded()
        }
        // 启动即异步刷新本仓库模型元数据（12h 缓存；失败静默，resolve 兜底内置 assets 数据）。
        appScope.launch {
            modelMetadataService.refreshFromNetworkIfStale()
        }
        // 启动即异步刷新仓库 providers.json 预设（12h 缓存；失败静默，兜底本地磁盘与内置 assets）。
        appScope.launch {
            com.aharou.feature.settings.data.local.ProviderPresetLibrary.refreshFromNetworkIfStale(this@AIEditorApp)
        }
        // 启动即异步刷新可下载镜像目录（12h 缓存；失败静默，兜底磁盘缓存与内置 assets；加版本无需发版）。
        appScope.launch {
            containerImageCatalog.refreshFromNetworkIfStale()
            skillMarketCatalog.refreshFromNetworkIfStale()
            pluginMarketCatalog.refreshFromNetworkIfStale()
        }
        // 启动即刷新知识库源清单并同步本地副本（失败静默，沿用上次同步到的内容）。
        appScope.launch {
            knowledgeCatalog.refreshFromNetworkIfStale()
        }
        // 启动即加载持久化等级，并随设置页改动实时生效（唯一同步点）。
        appScope.launch {
            logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }
        }
        // 启动即把首帧执行模式（从 DataStore 读）写入 ExecutionModeHolder，供 DI @Provides 同步读取；委托层每次调用才按 currentMode() 转发，不依赖注入时机。
        // SSH 连接放后台，失败不阻塞 UI（连接失败时首次命令会触发 ensureInstalled 重试）。
        appScope.launch {
            val mode = executionModeRepository.executionModeFlow.first()
            executionModeHolder.setMode(mode)
        }
        // SSH 连接监督只在远程模式下常驻：切回本地时停监督并断开（原因见 RemoteSshConnection.superviseByExecutionMode）
        appScope.launch {
            remoteSshConnection.superviseByExecutionMode(appScope, executionModeHolder.mode) {
                runCatching { workspaceRepository.initialize() }
                    .onFailure { FileLogger.w(TAG, "SSH 重连后重新加载工作区失败", it) }
                syncDocsToRemote()
                releaseBuiltinAssetsToRemote()
            }
        }
        // 当前激活 profile 的连接配置变化（编辑连接/编辑 profile/切 profile/切模式）即重连，改连接即时生效。
        appScope.launch {
            combine(
                activeRemoteConnectionResolver.activeConfigFlow,
                executionModeHolder.mode
            ) { config, mode -> config to mode }.collect { (config, mode) ->
                if (mode != com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) return@collect
                if (config == null || (config == remoteSshConnection.config && remoteSshConnection.isConnected())) return@collect // 配置没变且仍连着才跳过；切回远程时连接已断，需重连
                runCatching {
                    remoteSshConnection.connect(config)
                    // 连接成功后同步内置文档到远程 ~/.aharou/docs/，供 AI 查阅。
                    syncDocsToRemote()
                    releaseBuiltinAssetsToRemote()
                }.onFailure { FileLogger.e(TAG, "SSH 连接失败，将在首次命令时重试", it) }
            }
        }
        // 连接与同步的「跟随当前工作区」由 RemoteRepository 内部监听工作区变化自动执行（启动注入即就绪）：
        // 工作区就绪/切换时，自动断开非当前工作区的挂载，连接当前工作区里勾了「应用启动时自动连接」的挂载。
        // 后台保活常驻通知的唯一反应器：监听开关，启停 TerminalKeepaliveService 的常驻模式。
        // 既覆盖设置页实时切换，也覆盖冷启动恢复。仅在「由开变关」时发 disable，
        // 避免为关闭而凭空拉起从未开过的 Service。
        appScope.launch {
            var last: Boolean? = null
            keepaliveSettings.enabledFlow.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    TerminalKeepaliveService.enablePersistent(this@AIEditorApp)
                    // WorkManager 兜底：周期检查服务存活，被杀后自动拉起。
                    KeepaliveWorker.schedule(this@AIEditorApp)
                } else if (last == true) {
                    TerminalKeepaliveService.disablePersistent(this@AIEditorApp)
                    KeepaliveWorker.cancel(this@AIEditorApp)
                }
                last = enabled
            }
        }
        // 网络恢复或切换链路时立即重连 SSH，不等 supervisor 轮询（并带动远端工作区刷新）。
        appScope.launch {
            networkConnectivityObserver.recovered.collect {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
                    .onFailure { FileLogger.w(TAG, "网络恢复后重连 SSH 失败", it) }
            }
        }
        // 远程 SSH 连接期间维持前台服务：否则远程模式下的空闲会话切后台会被冻结、连接被切断。
        // 用会话计数（而非用户常驻开关）表达，连接断开即释放，不影响用户对保活开关的选择。
        appScope.launch {
            var held = false
            remoteSshConnection.connectionState.collect { state ->
                val connected = state == com.aharou.feature.agent.domain.container.ConnectionState.CONNECTED
                if (connected && !held) {
                    TerminalKeepaliveService.startSession(this@AIEditorApp)
                    held = true
                } else if (!connected && held) {
                    TerminalKeepaliveService.stopSession(this@AIEditorApp)
                    held = false
                }
            }
        }
        // 跨设备协作：配置里开着就恢复前台服务，否则不拉起（关着就不该占一条常驻通知）。
        appScope.launch {
            runCatching { remoteDeviceController.startIfEnabled() }
                .onFailure { FileLogger.e(TAG, "恢复跨设备协作服务失败", it) }
        }
        // 连接已配置的 MCP server，把其工具注册进 ToolRegistry（内部自有 scope，失败不影响启动）。
        mcpManager.start()
        // 语言服务器：App 被系统杀死时 stdio 进程拿不到旧句柄，会在容器里留成孤儿；
        // 这里按进程账本清一次（账本只认自家遗留进程，不会误伤 MCP/终端）。失败只记日志。
        appScope.launch {
            runCatching { editorLspManager.reapOrphanServers() }
                .onFailure { FileLogger.w("AIEditorApp", "清扫孤立语言服务器失败", it) }
        }
        // 子代理任务状态机：启动即收尾上次进程遗留的「运行中」任务（标为中断并通知父会话），
        // 随后常驻订阅子代理事件写状态。走 IO 作用域，失败只记日志，不阻塞启动。
        subAgentTaskTracker.start(appScope)
        // 定时任务：启动时排好周期检查（无启用任务则取消），并投递一次「应用启动」触发。
        appScope.launch {
            runCatching { scheduledTaskScheduler.ensureScheduled(this@AIEditorApp) }
                .onFailure { FileLogger.e("AIEditorApp", "定时任务调度初始化失败", it) }
            eventTriggerBus.postAppStart()
        }
        // 记忆自动同步：启动时按需重排。已有任务能跑就保留周期计时（KEEP），只有任务缺失或
        // 已 FAILED/CANCELLED 才重建——否则每次冷启动都把周期归零，重启得勤的时候任务永远等不到。
        appScope.launch {
            runCatching { memorySyncScheduler.apply() }
                .onFailure { FileLogger.e("AIEditorApp", "记忆同步调度初始化失败", it) }
        }
        // 权限规则由 App 启动即常驻订阅（AI 评估随时要读到最新规则）；MCP 配置与技能列表
        // 改由各自消费方（McpManager / 设置页）按需订阅，无需在这里拉起。
        appScope.launch { permissionRulesRepository.startWatching() }
        // 持续同步工作区模式缓存，崩溃时随报告带出
        appScope.launch {
            executionModeRepository.executionModeFlow.collect { mode ->
                currentWorkspaceMode = mode.name
            }
        }
        // 主进程健康启动 10 秒后，自动清零连续崩溃计数与错误页显示标志，恢复健康状态
        appScope.launch {
            kotlinx.coroutines.delay(10_000)
            resetCrashState(this@AIEditorApp)
        }
        // 语言切换由 MainActivity 的 attachBaseContext + recreate() 统一管理。
        // MainActivity 继承 ComponentActivity（非 AppCompatActivity），
        // AppCompatDelegate.setApplicationLocales 的自动 recreate 不生效，
        // 且两者同时设置 locale 会竞争导致偶发语言错乱。
    }

    /** 记录设备/版本信息：用户报「某系统版本上容器起不来」时，日志里得先有系统与 ABI 上下文。 */
    private fun logDeviceInfo() {
        runCatching {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            FileLogger.i(
                TAG,
                "设备信息：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}" +
                    "(API ${Build.VERSION.SDK_INT})，ABI=${Build.SUPPORTED_ABIS.joinToString(",")}，" +
                    "targetSdk=${applicationInfo.targetSdkVersion}，nativeLibraryDir=${applicationInfo.nativeLibraryDir}，" +
                    "版本=$versionName"
            )
        }.onFailure { FileLogger.w(TAG, "记录设备信息失败", it) }
    }

    /** HiltWorkerFactory：@HiltWorker 的 Worker 经此工厂创建，才能注入 Repository。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * 读取 assets/docs 下所有内置文档（含 guide/ advanced/ 等分类子目录），通过 SSH exec 同步到远程 ~/.aharou/docs/。
     * 远程模式下 AI 查阅 ~/.aharou/docs/ 的设置说明文档时，需要这些文件存在于远程服务器。
     * 连接成功与重连成功后调用，保证远程文档随 App 升级更新。失败仅记日志，不阻断流程。
     */
    private suspend fun syncDocsToRemote() {
        runCatching {
            val docs = linkedMapOf<String, String>()
            collectAssetDocs("docs", "", docs)
            remoteSshConnection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "同步内置文档到远程失败", it) }
    }

    /**
     * 释放内置子代理定义（Explore）到远程 `~/.aharou/agents`。远程模式下全局 agents 以远端为准，
     * 内置默认需在远端存在才能开箱即用；仅补齐缺失、不覆盖用户修改。
     */
    private suspend fun releaseBuiltinAssetsToRemote() {
        remoteSshConnection.uploadBuiltinFiles("agents", collectAssetBytes("agents"))
    }

    /** 收集 assets 目录下的扁平文件（name → 字节）。目录项会被跳过。 */
    private fun collectAssetBytes(assetDir: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        assets.list(assetDir)?.forEach { entry ->
            runCatching { assets.open("$assetDir/$entry").use { out[entry] = it.readBytes() } }
                .onFailure { FileLogger.w(TAG, "读取内置资源失败: $assetDir/$entry", it) }
        }
        return out
    }

    /** 递归收集 assets 文档，key 为相对 docs/ 的路径（如 guide/terminal.md）。 */
    private fun collectAssetDocs(assetDir: String, relativePrefix: String, out: MutableMap<String, String>) {
        val entries = assets.list(assetDir) ?: return
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val relativePath = if (relativePrefix.isEmpty()) entry else "$relativePrefix/$entry"
            try {
                out[relativePath] = assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                // 目录项：assets.open 对目录抛 IOException，递归处理
                collectAssetDocs(assetPath, relativePath, out)
            }
        }
    }

    /** 注册完整版 BouncyCastle 取代 Android 自带的裁剪版。
     *  sshj 0.38.0 用 X25519 做密钥交换，Android 自带的 BC provider 不含 X25519 算法，
     *  需先移除裁剪版再注册 bcprov-jdk18on（sshj 传递依赖）的完整版，并告诉 sshj 使用它。
     *  必须在任何 sshj 调用之前完成。 */
    private fun registerBouncyCastle() {
        // 先移除 Android 自带的裁剪版 BC，再注册完整版 bcprov-jdk18on。
        // 用 addProvider 而非 insertProviderAt(…, 1)：BC 只需存在于 Provider 列表中供 sshj
        // 通过 SecurityUtils.setSecurityProvider("BC") 按名查到即可，无需排到最高优先级。
        // 若抬到第 1 位，会抢占 OkHttp/Conscrypt 初始化默认 SSLContext 时的 KeyStore 查找，
        // BC 注册了 BKS 类型却没有配套默认 truststore，导致抛 KeyStoreException: BKS not found
        // （表现为检测更新等 HTTPS 请求崩溃）。放末尾让系统自带 provider 继续负责 TLS。
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }

    /**
     * 捕获未处理异常：落盘后拉起全屏错误页（展示崩溃详情、支持复制），
     * 随即杀掉进程让系统重启并恢复错误页。
     *
     * 必须杀进程：主线程崩溃后其 Looper 已死，不杀进程则错误页窗口虽出现
     * 但 onCreate 永不执行（白屏），最终被系统当 ANR 杀掉。杀掉后 AMS 检测到
     * 进程死在 Activity 创建途中，会自动重启进程并恢复 CrashActivity，
     * 新进程主线程健康，错误页才能正常渲染。
     *
     * 防递归：错误页自身崩溃时落盘标志仍在，直接交回系统默认处理器，避免无限重启。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val isCrash = isCrashProcess(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val processName = getProcessNameCompat(this)
            FileLogger.e("CRASH", "线程 ${thread.name} 未捕获异常 (进程=$processName)", throwable)
            // 崩溃前同步 flush 缓冲日志，避免最后一段（含本行错误）留在内存中丢失。
            FileLogger.flushSync()
            // 结构化留档：写进 filesDir/aharou/crash/（容器内 /root/.aharou/crash），
            // 以后 agent 能直接读现场，不必再让用户手抄堆栈。
            CrashRecorder.record(
                context = this,
                threadName = thread.name,
                throwable = throwable,
                screen = currentRoute,
                workspaceMode = currentWorkspaceMode
            )

            // 1. 若 :crash 进程自身发生异常：交回系统默认处理器并彻底退出，绝不二次拉起错误页
            if (isCrash) {
                FileLogger.e(TAG, "崩溃错误页进程发生异常，强制终止进程，避免递归")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            // 2. 主进程崩溃：检查崩溃频次与状态，防止死循环无限拉起
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val isUiShowing = prefs.getBoolean(KEY_CRASH_UI_SHOWING, false)
            val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            val now = System.currentTimeMillis()
            val recentCrashCount = if (now - lastCrashTime < CRASH_RESET_WINDOW_MS) {
                prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            } else {
                1
            }
            prefs.edit()
                .putLong(KEY_LAST_CRASH_TIME, now)
                .putInt(KEY_CRASH_COUNT, recentCrashCount)
                .apply()

            // 熔断保护：错误页正在展示又发生崩溃，或短时间内连续崩溃达到阈值
            if (isUiShowing || recentCrashCount >= MAX_CONSECUTIVE_CRASHES) {
                FileLogger.e(TAG, "触发崩溃熔断保护 (isUiShowing=$isUiShowing, count=$recentCrashCount)，终止自启交由系统处理")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            prefs.edit().putBoolean(KEY_CRASH_UI_SHOWING, true).apply()
            try {
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
                        putExtra(CrashActivity.EXTRA_STACK, stackTraceOf(throwable))
                        putExtra(CrashActivity.EXTRA_SCREEN, currentRoute)
                        putExtra(CrashActivity.EXTRA_WORKSPACE_MODE, currentWorkspaceMode)
                        putExtra(CrashActivity.EXTRA_MEMORY, memorySummary())
                    }
                )
            } catch (t: Throwable) {
                // 错误页启动失败（如系统限制）：交回默认处理器，不让崩溃被吞掉
                FileLogger.e(TAG, "启动崩溃错误页失败", t)
                prefs.edit().remove(KEY_CRASH_UI_SHOWING).apply()
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * 读取历史进程退出原因（ApplicationExitInfo，API 30+），把 Java 未捕获异常处理器覆盖不到的
     * 异常退出（native 崩溃、ANR、低内存被杀、初始化失败、被信号杀死）补记到日志。
     *
     * 用 SharedPreferences 记录已处理的最大时间戳做增量：首次运行只建立基线，不回捞旧记录；
     * 与最近一次 Java 崩溃时间窗重叠的记录跳过，避免与 CrashActivity 链路重复上报。
     * 低版本（< API 30）无此 API，静默跳过。
     */
    private fun reportAbnormalExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val records = am.getHistoricalProcessExitReasons(packageName, 0, MAX_EXIT_RECORDS)
            if (records.isEmpty()) return@runCatching
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
            // 首次运行（尚无游标）只建立基线，不回捞安装前的历史退出，避免一次刷出大量旧记录。
            if (lastSeen == 0L) {
                prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, records.maxOf { it.timestamp }).apply()
                return@runCatching
            }
            val lastJavaCrash = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            var maxTimestamp = lastSeen
            records.sortedBy { it.timestamp }.forEach { info ->
                if (info.timestamp <= lastSeen) return@forEach
                maxTimestamp = maxOf(maxTimestamp, info.timestamp)
                if (info.reason !in ABNORMAL_EXIT_REASONS) return@forEach
                // 与最近一次 Java 崩溃属同一次退出（时间窗内）时跳过，避免两条链路重复上报。
                if (lastJavaCrash > 0 &&
                    kotlin.math.abs(info.timestamp - lastJavaCrash) < CRASH_RESET_WINDOW_MS * 3
                ) {
                    return@forEach
                }
                FileLogger.e(TAG, "检测到上次异常退出：\n${describeExit(info)}", null)
            }
            if (maxTimestamp != lastSeen) {
                prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, maxTimestamp).apply()
            }
        }.onFailure { FileLogger.w(TAG, "读取历史进程退出原因失败", it) }
    }

    /** 组装单条异常退出的可读描述：原因、时间、状态、重要级、内存、描述与 trace 首部。 */
    private fun describeExit(info: ApplicationExitInfo): String = buildString {
        append("原因=").append(exitReasonName(info.reason))
        append("，时间=").append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(info.timestamp))
        )
        append("，status=").append(info.status)
        append("，importance=").append(info.importance)
        append("，pss=").append(info.pss / 1024 / 1024).append("MB")
        append("，rss=").append(info.rss / 1024 / 1024).append("MB")
        info.description?.let { append("，描述=").append(it) }
        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            append("\n").append(describeNativeTrace(info.traceInputStream, info.timestamp))
        } else {
            readTraceHead(info.traceInputStream)?.let { append("\n").append(it) }
        }
    }

    /**
     * native 崩溃的 trace 是 tombstone 的 protobuf（见 [TombstoneParser]）。原样落盘一份（无损，
     * 便于线下用 protobuf 工具重解析），并解析成可读文本内联——不再走 [readTraceHead] 的可打印过滤，
     * 那条路径会丢掉线程边界、无法定位到崩溃线程。
     */
    private fun describeNativeTrace(stream: java.io.InputStream?, timestamp: Long): String {
        val bytes = runCatching { stream?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return "trace: (无)"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(timestamp))
        val dir = FileLogger.logDirectory()?.let { File(it, "crash-traces").apply { mkdirs() } }
        val rawPath = dir?.let { d ->
            File(d, "tombstone-$stamp.pb").also { f ->
                runCatching { f.writeBytes(bytes) }
                    .onFailure { FileLogger.w(TAG, "落盘原始 tombstone 失败", it) }
            }.absolutePath
        }
        pruneCrashTraces(dir)
        val body = TombstoneParser.parse(bytes)
            ?: readTraceHead(bytes.inputStream())
            ?: "(无可读内容)"
        val where = rawPath?.let { "\n原始 tombstone: $it" } ?: ""
        return "trace:\n$body$where"
    }

    /** 崩溃 tombstone 目录只保留最新的 [MAX_CRASH_TRACE_FILES] 个文件，避免无上限增长。 */
    private fun pruneCrashTraces(dir: File?) {
        if (dir == null) return
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("tombstone-") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (files.size <= MAX_CRASH_TRACE_FILES) return
            files.drop(MAX_CRASH_TRACE_FILES).forEach { it.delete() }
        }
    }

    /**
     * 读取 trace 前 [TRACE_HEAD_BYTES] 字节（按字节限长，避免超大 trace 撑爆内存），
     * 并只保留其中的可打印文本片段。用于 ANR 等非 native 退出（trace 可能是压缩流），
     * 或 tombstone 解析失败时的兜底；直接按 UTF-8 转字符串会变成大量乱码并撑大日志。
     * 无 trace 或无可见内容时返回 null。
     */
    private fun readTraceHead(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return runCatching {
            stream.use { input ->
                val buffer = ByteArray(TRACE_HEAD_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) return@use null
                val sb = StringBuilder()
                val current = StringBuilder()
                fun flush() {
                    if (current.length >= 4) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(current)
                    }
                    current.setLength(0)
                }
                for (i in 0 until read) {
                    val b = buffer[i].toInt() and 0xFF
                    if (b in 0x20..0x7E || b == 0x09) {
                        current.append(b.toChar())
                    } else {
                        flush()
                    }
                }
                flush()
                sb.toString().trim().takeIf { it.isNotEmpty() }?.let { "trace:\n$it" }
            }
        }.getOrNull()
    }

    /** ApplicationExitInfo 原因码 → 可读名称。 */
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
        ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存被系统杀死"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        ApplicationExitInfo.REASON_EXIT_SELF -> "主动退出"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束"
        ApplicationExitInfo.REASON_OTHER -> "其它原因"
        else -> "未知（$reason）"
    }

    /**
     * 崩溃进程的内存快照，随报告带出。必须在主进程采集——错误页跑在 :crash 独立进程，
     * 在那里读到的只是汇报进程的堆，对定位 OOM 无意义。
     */
    private fun memorySummary(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss?.toLong()?.div(1024)
        }.getOrNull()
        buildString {
            append("heap ").append(usedMb).append('/').append(maxMb).append("MB")
            if (pssMb != null) append(", PSS ").append(pssMb).append("MB")
            append(", memoryClass ").append(am.memoryClass).append("MB")
            append(", lowRam ").append(am.isLowRamDevice)
        }
    }.getOrDefault("unavailable")

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > MAX_CRASH_STACK_CHARS) {
            text.take(MAX_CRASH_STACK_CHARS) + "\n...[truncated]"
        } else {
            text
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "terminal_service",
                getString(R.string.notification_channel_terminal_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_terminal_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            // Agent 完成通知：需要弹窗+声音，区别于 terminal_service 的静默常驻通知。
            val agentChannel = NotificationChannel(
                "agent_complete",
                getString(R.string.notification_channel_agent_complete),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_agent_complete_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(agentChannel)
        }
    }
}
