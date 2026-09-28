package com.aharou

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aharou.core.util.FileLogger
import com.aharou.core.util.PanelScriptImporter
import com.aharou.feature.agent.presentation.SharedIntakeHolder
import com.aharou.feature.agent.presentation.SharedIntakeEntryPoint
import com.aharou.feature.agent.presentation.component.ShareIntakeDialog
import com.aharou.feature.agent.presentation.component.copyUriToWorkspace
import com.aharou.feature.agent.presentation.component.toPendingAttachment
import com.aharou.feature.workspace.domain.FileAccessProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.theme.AppThemePreset
import com.aharou.core.ui.BetaWatermark
import com.aharou.core.ui.ImageSource
import com.aharou.core.ui.ImageViewerHost
import com.aharou.core.ui.ImageViewerRequest
import com.aharou.core.ui.LocalImageViewer
import com.aharou.core.ui.PAGE_MOTION_MS
import com.aharou.core.ui.VerticalSplitHandle
import com.aharou.core.ui.drawerWidth
import com.aharou.core.ui.isExpandedWidth
import com.aharou.core.ui.pageEnter
import com.aharou.core.ui.pageExit
import com.aharou.core.ui.rememberImageViewerState
import com.aharou.core.ui.rememberViewerDecodeSpec
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.component.AIChatPanel
import com.aharou.feature.agent.presentation.component.ChatDrawerContent
import com.aharou.feature.agent.presentation.component.chatImageLoader
import com.aharou.feature.agent.presentation.component.openSentAttachment
import com.aharou.feature.editor.presentation.CodeEditorScreen
import com.aharou.feature.browser.presentation.BrowserScreen
import com.aharou.feature.git.presentation.GitViewModel
import com.aharou.feature.credentials.presentation.component.CredentialScreen
import com.aharou.feature.git.presentation.component.GitScreen
import com.aharou.feature.onboarding.data.OnboardingStatus
import com.aharou.feature.onboarding.domain.OnboardingCoordinator
import com.aharou.feature.onboarding.domain.OnboardingStateHolder
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.LocalOnboardingTargetRegistry
import com.aharou.feature.onboarding.presentation.OnboardingOverlay
import com.aharou.feature.onboarding.presentation.OnboardingTargetRegistry
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.BackgroundSettingsRepository
import com.aharou.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.settings.presentation.FetchState
import com.aharou.feature.settings.presentation.UpdateCheckUiState
import com.aharou.feature.settings.presentation.component.githubReleaseUrl
import com.aharou.feature.settings.presentation.component.installDownloadedApk
import com.aharou.feature.settings.presentation.component.SettingsScreen
import com.aharou.feature.settings.presentation.component.UpdateCheckDialog
import com.aharou.feature.settings.presentation.component.decodeBackgroundBitmap
import com.aharou.feature.settings.presentation.component.openUrl
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import com.aharou.feature.terminal.presentation.TerminalViewModel
import com.aharou.feature.terminal.presentation.component.TerminalScreen
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.aharou.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 用于冷启动时在前台恢复常驻保活通知（App.onCreate 的启动可能被后台 FGS 限制挡掉）。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var backgroundSettings: BackgroundSettingsRepository

    /** 屏幕常亮开关：只在 App 前台约束屏幕，切后台由系统自动失效。 */
    @Inject
    lateinit var screenOnSettings: ScreenOnSettingsRepository

    /** 语言偏好：attachBaseContext 时同步读取以应用 locale，变化时 recreate。 */
    @Inject
    lateinit var languageSettings: com.aharou.feature.settings.data.repository.LanguageSettingsRepository

    /** App 回到前台时，远程模式下若 SSH 断了触发重连。 */
    @Inject
    lateinit var remoteSshConnection: com.aharou.feature.agent.domain.container.RemoteSshConnection

    /** 全局主机指纹提醒：任意路径撞到未确认指纹时，App 根部弹「温柔提示」引导确认。 */
    @Inject
    lateinit var hostKeyVerifier: com.aharou.feature.agent.domain.container.SshHostKeyVerifier

    /** 外部分享文件的中转站；消费方在聊天面板。 */
    @Inject
    internal lateinit var sharedIntakeHolder: SharedIntakeHolder

    /** 分享文件要经它写进当前工作区（本地映射到宿主工作区，远程落到远程）。 */
    @Inject
    lateinit var fileAccessProvider: FileAccessProvider

    @Inject
    lateinit var executionModeHolder: com.aharou.feature.settings.data.repository.ExecutionModeHolder

    /** 三端（UI/AI Bash/交互终端）git 缺凭据统一弹窗桥：在 AIEditorApp 启动后监听 helper 的文件 IPC 请求。 */
    @Inject
    lateinit var credentialRequestBridge: com.aharou.feature.credentials.data.CredentialRequestBridge

    /** 首次启动引导的进度持久化。 */
    @Inject
    lateinit var onboardingRepository: com.aharou.feature.onboarding.data.OnboardingRepository

    override fun attachBaseContext(newBase: android.content.Context) {
        // 在 Activity 创建前同步应用用户选择的语言，确保冷启动也生效。
        // Hilt 尚未注入，直接从 SharedPreferences 同步读取。
        val tag = newBase.getSharedPreferences("language_prefs_sync", android.content.Context.MODE_PRIVATE)
            .getString("language_tag", null)
        val context = if (tag.isNullOrBlank()) {
            newBase
        } else {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    @Suppress("DEPRECATION") // 全局更新 application resources locale，createConfigurationContext 无法替代
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 复用本实例时 getIntent() 还指向旧的，必须显式接过来。
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 接收外部送来的文件（ACTION_SEND / SEND_MULTIPLE / VIEW 打开方式）：拷进工作区 attachments，
     * 投给 [sharedIntakeHolder] 由聊天面板并入输入框附件。失败只记日志，不影响正常启动。
     */
    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val direct = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            // 「打开方式」来的文件把 URI 放在 data 里。
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> return
        }
        // 分享方只把 URI 塞进 clipData 的情况很常见（QQ/微信等按官方推荐这么做，EXTRA_STREAM 为空），
        // 只认 EXTRA_STREAM 会表现为「分享进来什么都没发生」：不导入、也不报错。
        val fromClip = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        val uris = direct.ifEmpty { fromClip }
        FileLogger.i(
            "MainActivity",
            "分享 intent: action=${intent.action} type=${intent.type} " +
                "extraKeys=${intent.extras?.keySet()?.joinToString()} " +
                "clipItems=${intent.clipData?.itemCount ?: 0} data=${intent.data} -> uris=$uris"
        )
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            // 脚本类文件导入 ~/.aharou/scripts/（面板脚本目录），其余当普通附件。
            val (scripts, others) = uris.partition {
                PanelScriptImporter.isPanelScript(PanelScriptImporter.queryDisplayName(this@MainActivity, it))
            }
            val importedScripts = scripts.mapNotNull { PanelScriptImporter.import(this@MainActivity, it) }
            if (importedScripts.isNotEmpty()) {
                FileLogger.i("MainActivity", "已导入面板脚本：${importedScripts.joinToString()}")
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.share_script_imported, importedScripts.joinToString()),
                    Toast.LENGTH_LONG,
                ).show()
            }
            val uploaded = others.mapNotNull { uri ->
                runCatching { copyUriToWorkspace(this@MainActivity, uri, fileAccessProvider) }
                    .onFailure { FileLogger.w("MainActivity", "分享文件导入失败：$uri", it) }
                    .getOrNull()
            }
            if (uploaded.isNotEmpty()) {
                // 文件已落地，但先不投给聊天面板：冷启动时面板可能还没组合完，投进去会落空。
                // 交给用户确认（预览 / 插入输入框 / 取消），点「插入输入框」时再投。
                sharedIntakeHolder.awaitConfirm(uploaded.map { it.toPendingAttachment() })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 从冷启动闪屏主题恢复为应用主主题
        setTheme(R.style.Theme_AICode)
        // 绘制到系统状态栏/导航栏之下，让应用背景与系统栏融为一体（消除割裂的色块）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 冷启动若由分享拉起，直接导入附件（singleTop 复用时走 [onNewIntent]）。
        handleShareIntent(intent)

        // 监听语言偏好变化，更新 Application/Activity locale 后重建。
        lifecycleScope.launch {
            languageSettings.languageFlow.drop(1).distinctUntilChanged().collect { tag ->
                // 同步更新 Application context 的 Configuration，确保非 Activity 来源的
                // Resources（如 ViewModel 中 context.getString）也能拿到正确 locale。
                val app = applicationContext
                if (tag.isNullOrBlank()) {
                    // 跟随系统：重置为系统默认 Configuration
                    val sysConfig = android.content.res.Configuration(resources.configuration)
                    sysConfig.setLocale(java.util.Locale.getDefault())
                    app.resources.updateConfiguration(sysConfig, app.resources.displayMetrics)
                } else {
                    val config = android.content.res.Configuration(app.resources.configuration)
                    config.setLocale(java.util.Locale.forLanguageTag(tag))
                    app.resources.updateConfiguration(config, app.resources.displayMetrics)
                }
                recreate()
            }
        }
        // 屏幕常亮：窗口标志只在 Activity 可见时约束屏幕，退到后台系统自动放开，无需手动释放。
        lifecycleScope.launch {
            screenOnSettings.enabledFlow.collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // API 30+：全局切到 ADJUST_NOTHING，由 rememberImeBottomInset() 接管键盘内边距。
        // 必须在 Activity 级别统一设置，不能在每个 composable 里各自 save/restore——
        // NavHost 过渡动画期间新旧页面共存，旧页面 dispose 恢复 softInputMode 会触发窗口重布局导致白屏。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContent {
            val themeMode by themeSettings.themeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
            val themePresetId by themeSettings.themePresetIdFlow.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColor by themeSettings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.AUTO -> systemDarkTheme
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            AIEditorTheme(
                darkTheme = darkTheme,
                preset = AppThemePreset.findById(themePresetId),
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 全局自定义背景图：叠加在所有页面内容之上（水印效果），透明度可调。
                    // 不拦截触摸事件；弹窗（Dialog 独立窗口）不受层级影响。
                    val bgPath by backgroundSettings.imagePathFlow.collectAsStateWithLifecycle(initialValue = null)
                    val bgAlpha by backgroundSettings.alphaFlow.collectAsStateWithLifecycle(initialValue = BackgroundSettingsRepository.DEFAULT_ALPHA)
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(onboardingRepository = onboardingRepository)
                        // 全局凭据弹窗：覆盖所有页面，命令行 git 缺凭据在任意页面都能弹。
                        com.aharou.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                            bridge = credentialRequestBridge
                        )
                        // 全局主机指纹确认：任意路径撞到未确认的 SSH 指纹时温柔引导确认（而非默默失败）。
                        com.aharou.feature.workspace.presentation.remote.HostKeyGlobalAlert(
                            verifier = hostKeyVerifier
                        )
                        if (bgPath != null && bgAlpha > 0f) {
                            val screen = LocalView.current
                            val bitmap by produceState<ImageBitmap?>(initialValue = null, bgPath) {
                                value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    decodeBackgroundBitmap(
                                        bgPath!!,
                                        screen.width.coerceAtLeast(1),
                                        screen.height.coerceAtLeast(1)
                                    )
                                }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(bgAlpha)
                                )
                            }
                        }
                        // beta 包全局水印，置于最上层，不拦截触摸。
                        BetaWatermark()
                    }
                }
            }
        }

        // 此时处于前台，启动前台服务一定被允许：若用户曾开启常驻保活，补上通知。
        lifecycleScope.launch {
            if (keepaliveSettings.isEnabled()) {
                TerminalKeepaliveService.enablePersistent(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 远程模式回到前台时，若 SSH 断了立即触发重连，不等 supervisor 轮询
        if (executionModeHolder.currentMode() == com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
            lifecycleScope.launch {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}

/** 大屏右栏默认占宽比，以及拖拽分栏的上下限——两边都至少留 30% 宽度。 */
private const val DEFAULT_PANE_SPLIT = 0.5f
private const val MIN_PANE_SPLIT = 0.3f
private const val MAX_PANE_SPLIT = 0.7f

/**
 * 根导航容器。
 *
 * [ModalNavigationDrawer] 放在 [NavHost] **外面**，使 Drawer 的生命周期独立于页面切换。
 *
 * 页面过渡统一走 [pageEnter] / [pageExit]。
 *
 * **注意**：终端页承载的是原生 TerminalView，历史上启用过渡曾导致「退出终端后立即点侧边栏白屏」——
 * 当时过渡期间新旧页面共存，旧页 dispose 把 softInputMode 切回 adjustResize 触发窗口重布局，
 * 与 Drawer 打开动画叠加后渲染管线中断。该路径已由 onCreate 统一设置 softInputMode 与
 * `rememberImeBottomInset` 的空 onDispose 兜住；若白屏复现，先把终端路由的过渡降级为 None 排除嫌疑。
 *
 * ViewModel 提升到这一层创建，以便 Drawer 内容和 AIChatPanel 共享同一实例。
 */
@Composable
fun AppNavigation(
    onboardingRepository: com.aharou.feature.onboarding.data.OnboardingRepository
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 用于判断当前路由：仅在聊天页允许 Drawer 手势。
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // 记录当前路由到全局，崩溃报告用它定位崩溃页面。
    LaunchedEffect(currentRoute) {
        AIEditorApp.currentRoute = currentRoute
    }

    // Activity 级别的 ViewModel——Drawer 和 AIChatPanel 共享同一个实例。
    val agentViewModel: AIAgentViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()

    // ── 首次启动引导 ──
    val onboardingStateHolder = remember { OnboardingStateHolder() }
    val onboardingUiState by onboardingStateHolder.state.collectAsStateWithLifecycle()
    val onboardingRegistry = remember { OnboardingTargetRegistry() }
    val onboardingScope = rememberCoroutineScope()
    val onboardingCoordinator = remember {
        OnboardingCoordinator(
            stateHolder = onboardingStateHolder,
            onPersist = { status ->
                onboardingRepository.markStatus(status)
            },
            scope = onboardingScope
        )
    }
    // 启动判断：IN_PROGRESS 则激活引导。
    LaunchedEffect(Unit) {
        val status = onboardingRepository.status()
        onboardingCoordinator.initialize(status ?: OnboardingStatus.IN_PROGRESS)
    }

    // 自动检查更新：进入主页时异步检测（开关开启且每天最多一次，失败静默）
    LaunchedEffect(Unit) {
        settingsViewModel.checkUpdate(manual = false)
    }

    // 侧边栏打开时，系统返回键先收起侧边栏。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 侧边栏需要的数据。
    val currentWorkspace by workspaceViewModel.current.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(currentWorkspace) {
        // 远程模式连接未就绪时 currentWorkspace 为 null，不触发 setWorkspace，避免空路径点燃 session 加载
        val path = currentWorkspace?.path ?: return@LaunchedEffect
        agentViewModel.setWorkspace(path)
    }

    // 侧边栏按文件夹（工作区）分成两层，要跨工作区的会话，不能只取当前工作区那份。
    val sessionsByWorkspace by agentViewModel.sessionsByWorkspace.collectAsStateWithLifecycle()
    val sessions = remember(sessionsByWorkspace) { sessionsByWorkspace.values.flatten() }
    val allWorkspaces by workspaceViewModel.workspaces.collectAsStateWithLifecycle()
    val currentSessionId by agentViewModel.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agentViewModel.agentStates.collectAsStateWithLifecycle()
    val awaitingPermissionSessionIds by agentViewModel.awaitingPermissionSessionIds.collectAsStateWithLifecycle()
    val subSessionsByParent by agentViewModel.subSessionsByParent.collectAsStateWithLifecycle()
    val expandedPaths by agentViewModel.expandedPaths.collectAsStateWithLifecycle()
    val browseState by agentViewModel.browseState.collectAsStateWithLifecycle()
    val browseClipboard by agentViewModel.browseClipboard.collectAsStateWithLifecycle()
    val pasteConflict by agentViewModel.pasteConflict.collectAsStateWithLifecycle()
    val chatSearchQuery by agentViewModel.chatSearchQuery.collectAsStateWithLifecycle()
    val chatSearchState by agentViewModel.chatSearchState.collectAsStateWithLifecycle()

    // ── 导出会话：SAF 保存文件 ──
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val sessionId = pendingExportSessionId
        if (uri != null && sessionId != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    agentViewModel.exportSession(sessionId, os) { success ->
                        Toast.makeText(
                            context,
                            context.getString(if (success) R.string.chat_export_session_done else R.string.chat_export_session_failed),
                            if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_export_session_failed), Toast.LENGTH_LONG).show()
                }
                pendingExportSessionId = null
            }
        } else {
            pendingExportSessionId = null
        }
    }

    // ── 大屏（平板横屏及以上）布局 ──
    val expanded = isExpandedWidth()
    // 常驻侧栏只在聊天页开：终端 / 编辑器 / 设置都是全屏页，被侧栏挤窄反而难用。
    // 常驻侧栏只看窗口宽度：它只在聊天页展开，切到其他页会滑回去（见下方 sidebarWidth）。
    val permanentDrawer = expanded

    // 侧栏出现时刷一次文件列表：本地模式兼顾 inotify 漏事件，远程模式无 inotify 则完全靠它。
    // 不能直接盯 drawerState.isOpen：大屏常驻侧栏不走 ModalNavigationDrawer，drawerState 永远是 Closed，
    // 那样大屏远程模式下文件树只能靠手动刷新按钮。改取「侧栏可见」：窄窗看抽屉开没开，
    // 大屏看是否停在聊天页（侧栏只在聊天页展开）。
    val sidebarVisible = if (permanentDrawer) currentRoute == "chat" else drawerState.isOpen
    LaunchedEffect(sidebarVisible) {
        if (sidebarVisible) agentViewModel.refreshBrowse()
    }

    // 右栏工作台：大屏下编辑器 / 终端 / Git 与聊天并排，窄窗仍走全屏路由。
    var paneKind by rememberSaveable { mutableStateOf(WorkbenchPaneKind.NONE) }
    var paneEditorPath by rememberSaveable { mutableStateOf("") }
    var paneEditorLine by rememberSaveable { mutableIntStateOf(0) }
    var paneSplit by rememberSaveable { mutableFloatStateOf(DEFAULT_PANE_SPLIT) }

    // Aharou：浏览器围观面板 + 工具消息快捷入口（地球 / 在终端运行）
    var browserSheetOpen by remember { mutableStateOf(false) }
    var terminalInitCommand by rememberSaveable { mutableStateOf<String?>(null) }
    val browserWatchTick by com.aharou.feature.browser.BrowserWatchSignal.tick
        .collectAsStateWithLifecycle()
    LaunchedEffect(browserWatchTick) {
        if (browserWatchTick > 0L) browserSheetOpen = true
    }

    // 右栏打开时返回键先收起它。限定聊天页：其他页面右栏不渲染，不能在那里吞掉返回事件。
    BackHandler(
        enabled = expanded && currentRoute == "chat" &&
            paneKind != WorkbenchPaneKind.NONE && !drawerState.isOpen
    ) {
        paneKind = WorkbenchPaneKind.NONE
    }

    // 打开文件：大屏进右栏，窄窗跳全屏编辑器页。
    val openFile: (String, Int, Boolean) -> Unit = { filePath, line, fromDrawer ->
        if (expanded) {
            paneEditorPath = filePath
            paneEditorLine = line
            paneKind = WorkbenchPaneKind.EDITOR
            // 常驻侧栏不需要关；平板竖屏等仍用 modal 抽屉的情况下选完文件要收起。
            if (!permanentDrawer) scope.launch { drawerState.close() }
        } else {
            scope.launch { drawerState.close() }
            navController.navigate(
                "editor?path=${android.net.Uri.encode(filePath)}&line=$line&drawer=$fromDrawer"
            )
        }
    }

    // 文件树里点文件：图片进内置全屏查看器、APK 走系统安装器，其余仍进编辑器。
    val drawerImageViewerState = rememberImageViewerState()
    val drawerDecodeSpec = rememberViewerDecodeSpec()
    val drawerImageLoad = remember(agentViewModel.fileAccess, drawerDecodeSpec) {
        chatImageLoader(agentViewModel.fileAccess, drawerDecodeSpec.maxEdge, drawerDecodeSpec.maxPixels)
    }
    val openTreeEntry: (String) -> Unit = { path ->
        val name = path.trimEnd('/').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in PREVIEWABLE_IMAGE_EXTS -> drawerImageViewerState.show(
                ImageViewerRequest(listOf(ImageSource.ContainerPath(path)), name)
            )
            ext in INSTALLABLE_APK_EXTS -> scope.launch {
                openSentAttachment(
                    context,
                    AgentAttachment(
                        fileName = name,
                        containerPath = path,
                        localPath = "",
                        mimeType = APK_MIME_TYPE,
                        sizeBytes = 0L,
                        isImage = false
                    ),
                    agentViewModel.fileAccess
                )
            }
            else -> openFile(path, 0, true)
        }
    }

    // 终端 / Git：大屏在右栏内开合切换，窄窗跳全屏页。
    val openWorkbench: (WorkbenchPaneKind) -> Unit = { target ->
        if (expanded) {
            paneKind = if (paneKind == target) WorkbenchPaneKind.NONE else target
        } else {
            // 窄屏：跳全屏路由。若浏览器正以 sheet 形式开着，先收起它——
            // 否则路由过渡那几百毫秒里 sheet 与全屏页会同时合成，同一个 WebView 被抢挂载。
            if (target == WorkbenchPaneKind.BROWSER) browserSheetOpen = false
            val route = when (target) {
                WorkbenchPaneKind.TERMINAL -> "terminal"
                WorkbenchPaneKind.GIT -> "git"
                WorkbenchPaneKind.BROWSER -> "browser"
                else -> "chat"
            }
            navController.navigate(route)
        }
    }

    // Aharou：工具消息两个互联入口的实现
    val browserOpener: (String) -> Unit = { url ->
        runCatching {
            if (url.isNotBlank()) agentViewModel.browserTabPool.selectOrCreateTabForURL(url)
        }
        // 右栏已经是浏览器时不再叠一层 sheet —— 同一个 WebView 被两个宿主同时挂载
        // 会互相抢（BrowserWebView.update 里的 re-parent），输的那个容器空着就是白屏。
        if (paneKind != WorkbenchPaneKind.BROWSER) browserSheetOpen = true
    }
    val terminalOpener: (String) -> Unit = { cmd ->
        terminalInitCommand = cmd
        openWorkbench(WorkbenchPaneKind.TERMINAL)
    }

    // 侧栏内容：modal 抽屉与大屏常驻栏共用同一份，不在两处重复几十行参数。
    val drawerBody: @Composable () -> Unit = {
        ChatDrawerContent(
            sessions = sessions,
            workspaces = allWorkspaces,
            currentWorkspacePath = currentWorkspace?.path ?: "",
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            browseState = browseState,
            expandedPaths = expandedPaths,
            clipboard = browseClipboard,
            pasteConflict = pasteConflict,
            onToggleExpand = { agentViewModel.toggleExpand(it) },
            onOpenFile = openTreeEntry,
            onRefreshBrowse = { agentViewModel.refreshBrowse() },
            onCreateFile = { parent, name ->
                agentViewModel.createBrowseFile(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onCreateFolder = { parent, name ->
                agentViewModel.createBrowseFolder(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onRenameEntry = { path, newName ->
                agentViewModel.renameBrowseEntry(path, newName) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_rename_failed)
                }
            },
            onDeleteEntry = { path ->
                agentViewModel.deleteBrowseEntry(path) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_delete_failed)
                }
            },
            onCopyEntry = { path, name ->
                agentViewModel.copyBrowseEntry(path, name)
            },
            onCutEntry = { path, name ->
                agentViewModel.cutBrowseEntry(path, name)
            },
            onPasteEntry = { targetDir, onResult ->
                agentViewModel.pasteBrowseEntry(targetDir, onResult)
            },
            onClearClipboard = { agentViewModel.clearBrowseClipboard() },
            onPasteOverwrite = { agentViewModel.pasteBrowseEntryOverwrite() },
            onCancelPasteOverwrite = { agentViewModel.clearPasteConflict() },
            onSelect = {
                agentViewModel.selectSession(it.id)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onDelete = { agentViewModel.deleteSession(it.id) },
            onDeleteSessions = { agentViewModel.deleteSessions(it) },
            onRename = { session, title -> agentViewModel.renameSession(session.id, title) },
            onTogglePin = { agentViewModel.togglePinSession(it.id) },
            onExport = { session ->
                pendingExportSessionId = session.id
                val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
                sessionExportLauncher.launch("aharou-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
            },
            onNavigateToSettings = {
                navController.navigate("settings")
                if (!permanentDrawer) scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            },
            searchQuery = chatSearchQuery,
            searchState = chatSearchState,
            onSearchQueryChange = { agentViewModel.updateChatSearchQuery(it) },
            onClearSearch = { agentViewModel.clearChatSearch() },
            onOpenSearchHit = { hit ->
                agentViewModel.openChatSearchHit(hit)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            }
        )
    }

    // 页面主体（NavHost）：大屏放进 Row 的右侧，窄窗放进 modal 抽屉容器里。
    val navBody: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = "chat",
            // 过渡期间退场页会向左移出自身区域，大屏下不夹住会画到左侧常驻会话侧栏上。
            modifier = Modifier.fillMaxSize().clipToBounds(),
            enterTransition = { pageEnter(forward = true) },
            exitTransition = { pageExit(forward = true) },
            popEnterTransition = { pageEnter(forward = false) },
            popExitTransition = { pageExit(forward = false) }
        ) {
            composable("chat") {
                // 聊天区内覆盖 LocalUriHandler：Markdown 链接点击默认经此 handler 派发。
                // 文件路径类链接（无 scheme 或 file://）拦下来跳编辑器，其余（http/https 等）仍走系统浏览器。
                val defaultUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val fileUriHandler = remember(defaultUriHandler, expanded) {
                    FileAwareUriHandler(defaultUriHandler) { filePath, line ->
                        openFile(filePath, line, false)
                    }
                }
                val paneOpen = expanded && paneKind != WorkbenchPaneKind.NONE
                // 拖动把手时要把位移像素换算成分栏比例，所以记下容器实测宽度。
                var rowWidthPx by remember { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { rowWidthPx = it.width }
                ) {
                    Box(modifier = Modifier.weight(if (paneOpen) paneSplit else 1f)) {
                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalUriHandler provides fileUriHandler,
                            com.aharou.feature.browser.presentation.LocalBrowserOpener provides browserOpener,
                            com.aharou.feature.terminal.presentation.component.LocalTerminalOpener provides terminalOpener,
                        ) {
                            AIChatPanel(
                                viewModel = agentViewModel,
                                settingsViewModel = settingsViewModel,
                                workspaceViewModel = workspaceViewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                showMenuButton = !permanentDrawer,
                                onNavigateToTerminal = { openWorkbench(WorkbenchPaneKind.TERMINAL) },
                                onNavigateToGit = { openWorkbench(WorkbenchPaneKind.GIT) },
                                onNavigateToBrowser = { openWorkbench(WorkbenchPaneKind.BROWSER) },
                                onNavigateToSandbox = { navController.navigate("sandbox") },
                                onNavigateToShared = { navController.navigate("shared") },
                                terminalActive = paneOpen && paneKind == WorkbenchPaneKind.TERMINAL,
                                gitActive = paneOpen && paneKind == WorkbenchPaneKind.GIT,
                                browserActive = paneOpen && paneKind == WorkbenchPaneKind.BROWSER,
                                onboardingStep = if (onboardingUiState.active) onboardingUiState.step else null,
                                onSelectModelInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.nextStep()
                                    }
                                },
                                onDismissModelSheetInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.goToStep(OnboardingStep.OPEN_MODEL_PICKER)
                                    }
                                }
                            )
                        }
                    }
                    if (browserSheetOpen && paneKind != WorkbenchPaneKind.BROWSER) {
                        com.aharou.feature.browser.presentation.BrowserSheet(
                            tabPool = agentViewModel.browserTabPool,
                            onDismiss = { browserSheetOpen = false },
                        )
                    }
                    if (paneOpen) {
                        VerticalSplitHandle(
                            onDragDelta = { dx ->
                                if (rowWidthPx > 0) {
                                    paneSplit = (paneSplit + dx / rowWidthPx)
                                        .coerceIn(MIN_PANE_SPLIT, MAX_PANE_SPLIT)
                                }
                            }
                        )
                        WorkbenchPaneContent(
                            kind = paneKind,
                            editorPath = paneEditorPath,
                            editorLine = paneEditorLine,
                            onClose = { paneKind = WorkbenchPaneKind.NONE },
                            onOpenFile = { path -> openFile(path, 0, false) },
                            modifier = Modifier.weight(1f - paneSplit)
                        )
                    }
                }
            }
            composable("settings") {
                // 复用 Activity 级 settingsViewModel（MainActivity 顶部已创建并 init），
                // 避免进入设置页时再建一个 NavBackStackEntry 级实例、重复跑 init 与 9 路 flow 订阅，
                // 这是侧边栏点设置「卡一下」的主因。
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                        // 大屏返回聊天页后侧栏本就常驻，不再弹 modal 抽屉。
                        if (!expanded) scope.launch { drawerState.open() }
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() },
                    onRerunOnboarding = {
                        // 从设置页「重新运行引导」：清状态回 in_progress、激活引导并回聊天页。
                        scope.launch {
                            onboardingRepository.resetToInProgress()
                            onboardingCoordinator.resetAndStart()
                            navController.navigate("chat") {
                                popUpTo("chat") { inclusive = false }
                            }
                        }
                    },
                    onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active },
                    onOnboardingModelAdded = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            navController.popBackStack()
                            onboardingCoordinator.nextStep()
                        }
                    },
                    onOnboardingDismissFetchDialog = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            onboardingCoordinator.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
                        }
                    }
                )
            }
            composable("terminal") {
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    initCommand = terminalInitCommand,
                    onInitCommandConsumed = { terminalInitCommand = null },
                )
            }
            composable("browser") {
                val browserViewModel: com.aharou.feature.browser.presentation.BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    tabPool = browserViewModel.tabPool,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("sandbox") {
                val sandboxContext = androidx.compose.ui.platform.LocalContext.current
                // 浏览根取当前选中容器的 rootfs（容器内 `/`）：写死内置 rootfs 会让自定义镜像用户
                // 点进来只看到空目录。
                val sandboxRootVm: com.aharou.feature.sandbox.SandboxRootViewModel = hiltViewModel()
                val sandboxRoot by sandboxRootVm.rootfsDir.collectAsStateWithLifecycle()
                val sandboxVm = remember(sandboxRoot) {
                    com.aharou.feature.sandbox.FileBrowserViewModel(
                        rootPath = sandboxRoot,
                        rootLabel = "/",
                        appContext = sandboxContext.applicationContext,
                    )
                }
                com.aharou.feature.sandbox.FileBrowserScreen(
                    viewModel = sandboxVm,
                    onBack = { navController.popBackStack() },
                    onPreviewFile = { item ->
                        com.aharou.feature.sandbox.FilePreviewHolder.currentItem = item
                        navController.navigate("sandboxPreview")
                    },
                )
            }
            composable("shared") {
                val sharedContext = androidx.compose.ui.platform.LocalContext.current
                val sharedVm = remember {
                    com.aharou.feature.sandbox.FileBrowserViewModel(
                        rootPath = com.aharou.feature.agent.domain.container.ContainerInstaller
                            .sharedDirOf(sharedContext),
                        appContext = sharedContext.applicationContext,
                    )
                }
                com.aharou.feature.sandbox.FileBrowserScreen(
                    viewModel = sharedVm,
                    onBack = { navController.popBackStack() },
                    onPreviewFile = { item ->
                        com.aharou.feature.sandbox.FilePreviewHolder.currentItem = item
                        navController.navigate("sandboxPreview")
                    },
                )
            }
            composable("sandboxPreview") {
                val previewItem = com.aharou.feature.sandbox.FilePreviewHolder.currentItem
                if (previewItem == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    com.aharou.feature.sandbox.FilePreviewScreen(
                        item = previewItem,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    onNavigateToCredentials = { navController.navigate("credentials") },
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> openFile(path, 0, false) }
                )
            }
            composable("credentials") {
                val credentialViewModel: com.aharou.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                CredentialScreen(
                    viewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "editor?path={path}&line={line}&drawer={drawer}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("line") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("drawer") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                // 从侧边栏文件页进入时返回要重新弹出抽屉（否则看完一个文件就得重新拉开抽屉进目录）；
                // 从聊天区链接进入时返回应回到聊天，不弹抽屉。
                val openDrawerOnBack = entry.arguments?.getBoolean("drawer") ?: false
                CodeEditorScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    initialLine = entry.arguments?.getInt("line") ?: 0,
                    onBack = {
                        navController.popBackStack()
                        if (openDrawerOnBack && !expanded) scope.launch { drawerState.open() }
                    }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalOnboardingTargetRegistry provides onboardingRegistry,
        LocalImageViewer provides drawerImageViewerState
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    // 文件树里点开的图片走这个查看器；聊天面板自带一个，两层互不影响。
    ImageViewerHost(state = drawerImageViewerState, load = drawerImageLoad)
    if (expanded) {
        // 大屏不套 ModalNavigationDrawer：侧栏常驻在左，抽屉那套 scrim / 手势 / 锚点在这里完全用不上。
        // 侧栏只在聊天页展开：设置页自己就是「菜单 + 详情」两栏，外面再套一层会话侧栏就成了三栏。
        // 宽度走补间而不是直接增删，否则右侧区域宽度突变，切页时会明显闪一下；
        // 时长与页面过渡取同一个值，两段动画同时收尾。
        val sidebarWidth by animateDpAsState(
            targetValue = if (currentRoute == "chat") drawerWidth() else 0.dp,
            animationSpec = tween(durationMillis = PAGE_MOTION_MS),
            label = "sidebar-width"
        )
        // 侧栏完全收起后整棵子树离开组合，要拿 SaveableStateHolder 接住内部状态，
        // 否则每次从设置页回来 tab 都跳回「会话」、文件树滚动位置也丢。
        val sidebarStateHolder = rememberSaveableStateHolder()
        Row(modifier = Modifier.fillMaxSize()) {
            if (sidebarWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    // 内容按完整宽度渲染再裁剪：收起过程中不去重排侧栏内部布局。
                    Box(
                        modifier = Modifier
                            .width(drawerWidth())
                            .fillMaxHeight()
                    ) {
                        sidebarStateHolder.SaveableStateProvider("chat-sidebar") {
                            drawerBody()
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(modifier = Modifier.weight(1f)) {
                navBody()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 仅在聊天页启用手势滑出；其他页面禁止（但已打开时始终可关闭）。
            gesturesEnabled = currentRoute == "chat" || drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    drawerContainerColor = settingsPageBackground(),
                    drawerTonalElevation = 0.dp,
                    modifier = Modifier.width(drawerWidth())
                ) {
                    drawerBody()
                }
            }
        ) {
            navBody()
        }
    }

    // 检查更新弹窗（全局宿主：自动检测与关于页手动检查共用，覆盖所有页面）
    val updateCheckState by settingsViewModel.updateCheckState.collectAsStateWithLifecycle()
    val updateDownloadState by settingsViewModel.updateDownloadState.collectAsStateWithLifecycle()
    if (updateCheckState != UpdateCheckUiState.Idle) {
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
        }
        UpdateCheckDialog(
            state = updateCheckState,
            downloadState = updateDownloadState,
            currentVersion = version,
            onDismiss = { settingsViewModel.dismissUpdateCheck() },
            onDownload = { settingsViewModel.downloadUpdate() },
            onCancelDownload = { settingsViewModel.cancelDownload() },
            onInstall = { apkPath -> installDownloadedApk(context, apkPath) },
            onOpenRelease = { tag ->
                openUrl(context, githubReleaseUrl(tag))
                settingsViewModel.dismissUpdateCheck()
            }
        )
    }

    // 外部分享进来的文件：确认「预览 / 插入输入框 / 取消」后才投给聊天面板。
    // 本函数是独立 Composable，够不到 Activity 的注入字段，走 EntryPoint 取同一个单例。
    val intakeContext = LocalContext.current.applicationContext
    val intakeHolder = remember(intakeContext) {
        EntryPointAccessors.fromApplication(intakeContext, SharedIntakeEntryPoint::class.java)
            .sharedIntakeHolder()
    }
    val awaitingShareItems by intakeHolder.awaitingConfirm.collectAsStateWithLifecycle()
    if (awaitingShareItems.isNotEmpty()) {
        ShareIntakeDialog(
            items = awaitingShareItems,
            onPreview = { item ->
                openFile(item.containerPath, 0, false)
                intakeHolder.clearAwaitingConfirm()
            },
            onInsert = {
                intakeHolder.submit(awaitingShareItems)
                intakeHolder.clearAwaitingConfirm()
            },
            onDismiss = { intakeHolder.clearAwaitingConfirm() }
        )
    }

    // 首次启动引导覆层（覆盖所有路由之上，全程常驻顶层，页面切换平滑过渡绝不闪烁）。
    if (onboardingUiState.active) {
        OnboardingOverlay(
            stateHolder = onboardingStateHolder,
            coordinator = onboardingCoordinator,
            registry = onboardingRegistry,
            onNextStep = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            },
            onTargetClick = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            }
        )
    }
    }
    }
}

/** 侧边栏文件页写操作失败提示（新建 / 重命名 / 删除共用）。 */
private fun toastFileOpFailed(context: android.content.Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}

/**
 * 聊天区 Markdown 链接处理：把「容器文件路径」链接拦下来交给 [onOpenFile] 打开编辑器，
 * 其余（http/https/mailto 等网址）委托给系统默认 [delegate]（浏览器）。
 *
 * 约定：AI 用 `[显示文本](路径)` 输出可点击路径，路径可带 `:行号` 后缀（如 `~/workspace/a.kt:42`）。
 */
private class FileAwareUriHandler(
    private val delegate: androidx.compose.ui.platform.UriHandler,
    private val onOpenFile: (path: String, line: Int) -> Unit
) : androidx.compose.ui.platform.UriHandler {
    override fun openUri(uri: String) {
        val filePath = asChatFilePath(uri)
        if (filePath == null) {
            delegate.openUri(uri)
            return
        }
        val (path, line) = splitPathAndLine(filePath)
        onOpenFile(path, line)
    }
}

/** 带 `//` 的层级式 scheme：`http://`、`https://`、`file://`、`content://` 等。 */
private val HIERARCHICAL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * 不带 `//` 的网址型 scheme 只能白名单枚举：scheme 语法允许 `.`，因此工作区根目录下的
 * 文件链接 `notes.md:12` 与 `mailto:x` 在语法上无从区分，按「有冒号即网址」判定会让
 * 这类链接被丢给浏览器而点不开。
 */
private val OPAQUE_URL_SCHEME = Regex(
    "^(mailto|tel|sms|smsto|geo|data|market|intent|about|javascript):",
    RegexOption.IGNORE_CASE
)

private val TRAILING_LINE = Regex("^(.*):(\\d+)$")

/** 文件树里点击即在内置查看器预览的图片格式（与 sendFile 的图片判定一致）。 */
private val PREVIEWABLE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** 文件树里点击即调系统安装器的安装包格式。 */
private val INSTALLABLE_APK_EXTS = setOf("apk", "apks", "xapk")

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** 聊天区链接目标 → 归一化后的文件路径；判定为网址（非 `file` scheme）时返回 null。 */
internal fun asChatFilePath(uri: String): String? {
    val hierarchical = HIERARCHICAL_SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
    return when {
        hierarchical == "file" -> android.net.Uri.decode(uri.removePrefix("file://"))
        hierarchical != null -> null // http/https/content 等交给系统
        OPAQUE_URL_SCHEME.containsMatchIn(uri) -> null // mailto/tel 等
        else -> uri // 容器绝对路径或相对工作区路径，含 `notes.md:12` 这种纯文件名
    }
}

/** 拆出可选的 `:行号` 后缀；无则行号为 0。 */
internal fun splitPathAndLine(path: String): Pair<String, Int> {
    val cleaned = path.substringBefore('#').trim()
    val m = TRAILING_LINE.find(cleaned) ?: return cleaned to 0
    return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
}
