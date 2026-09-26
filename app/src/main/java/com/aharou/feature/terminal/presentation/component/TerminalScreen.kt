package com.aharou.feature.terminal.presentation.component

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.rememberImeBottomInset
import com.aharou.feature.agent.domain.container.ContainerInitState
import com.aharou.feature.terminal.data.repository.TerminalSettings
import com.aharou.feature.terminal.domain.RunState
import com.aharou.feature.terminal.domain.TerminalTab
import com.aharou.feature.terminal.domain.font.TerminalFontManager
import com.aharou.feature.terminal.presentation.TerminalViewModel
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Tool
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

// ── Minis 风格终端配色（chrome 层；画布配色仍由主题预设控制）──
private val TerminalBg = Color(0xFF000000)
private val TerminalFg = Color(0xFFD4D4D4)
private val TerminalGreen = Color(0xFF34C759)
private val AccentBlue = Color(0xFF007AFF)
private val TopButtonBg = Color(0xFF2C2C2E)
private val AccessoryBg = Color(0xFF1F1F1F)
private val AccButtonBg = Color(0xFF404040)

/** chrome 文本统一走 JetBrains Mono（fusion 内置字体，与画布同族）。 */
private val TerminalMono = FontFamily(Font(R.font.jetbrains_mono_nl))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false,
    /** [Aharou] 打开时预填到提示符的命令（来自聊天工具消息的“在终端中运行”）。 */
    initCommand: String? = null,
    /** [Aharou] initCommand 已预填后的回调（宿主清空，避免重复预填）。 */
    onInitCommandConsumed: () -> Unit = {},
) {
    val prepareState by viewModel.prepareState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    var showToolsSheet by remember { mutableStateOf(false) }

    // [Aharou] 从聊天工具消息「在终端中运行」进入时：命令预填到提示符（不自动回车，供过目）。
    LaunchedEffect(initCommand, prepareState) {
        if (initCommand.isNullOrBlank()) return@LaunchedEffect
        if (prepareState !is TerminalViewModel.PrepareState.Ready) return@LaunchedEffect
        delay(500)
        viewModel.write(initCommand)
        onInitCommandConsumed()
    }

    Scaffold(
        containerColor = TerminalBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TerminalTopBar(
                embedded = embedded,
                onClose = onNavigateBack,
                onClear = { viewModel.writeBytes(0x0C) },
                onTools = { showToolsSheet = true },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = rememberImeBottomInset())
        ) {
            when (val state = prepareState) {
                is TerminalViewModel.PrepareState.Loading -> StatusView(
                    loading = true,
                    message = containerInitMessage(context, containerInit)
                )

                is TerminalViewModel.PrepareState.Error -> StatusView(
                    loading = false,
                    message = stringResource(R.string.terminal_start_failed, state.message),
                    actionLabel = stringResource(R.string.terminal_retry),
                    onAction = { viewModel.prepare() }
                )

                is TerminalViewModel.PrepareState.Ready -> {
                    @Suppress("UNUSED_EXPRESSION") revision

                    TabBar(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onSelect = { viewModel.activate(it) },
                        onClose = { viewModel.closeTab(it) },
                        onNew = { viewModel.newTab() }
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val active = tabs.firstOrNull { it.id == activeTabId }
                        if (active == null) {
                            StatusView(
                                loading = false,
                                message = stringResource(R.string.terminal_no_open_tabs),
                                actionLabel = stringResource(R.string.common_new_tab),
                                onAction = { viewModel.newTab() }
                            )
                        } else {
                            key(active.id) {
                                TerminalSurface(
                                    tab = active,
                                    viewModel = viewModel,
                                    settings = terminalSettings
                                )
                            }
                        }
                    }

                    if (activeTabId != null) {
                        KeyboardAccessoryBar(
                            viewModel = viewModel,
                            tabs = tabs,
                            activeTabId = activeTabId,
                        )
                    }
                }
            }
        }

        if (showToolsSheet) {
            TerminalSettingsSheet(
                settings = terminalSettings,
                showEnvTool = !viewModel.isRemoteMode,
                onRunInstaller = {
                    showToolsSheet = false
                    viewModel.runEnvInstaller()
                },
                onDismiss = { showToolsSheet = false },
                onSelectTheme = { viewModel.setTheme(it) },
                onChangeFontSize = { viewModel.setFontSize(it) },
                onChangeCursorStyle = { viewModel.setCursorStyle(it) },
                onChangeFontPath = { viewModel.setFontPath(it) }
            )
        }
    }
}

/** 可横滑的标签栏：每个标签显示状态点 + 标题 + 关闭；末尾「+」新建。激活标签变化时自动滚动，让当前标签可见。 */
@Composable
private fun TabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    val scrollState = rememberScrollState()
    val tabBounds = remember { mutableStateMapOf<String, Rect>() }
    var barWidth by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeTabId, barWidth) {
        val id = activeTabId ?: return@LaunchedEffect
        if (barWidth <= 0) return@LaunchedEffect
        val bounds = snapshotFlow { tabBounds[id] }.filterNotNull().first()
        val left = bounds.left.toInt()
        val right = bounds.right.toInt()
        val visibleStart = scrollState.value
        val visibleEnd = visibleStart + barWidth
        val target = when {
            left < visibleStart -> left
            right > visibleEnd -> right - barWidth
            else -> return@LaunchedEffect
        }
        scrollState.animateScrollTo(target.coerceAtLeast(0))
    }

    Surface(
        color = AccessoryBg,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { barWidth = it.width }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    selected = tab.id == activeTabId,
                    onClick = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                    modifier = Modifier.onGloballyPositioned { tabBounds[tab.id] = it.boundsInParent() }
                )
            }
            IconButton(onClick = onNew, modifier = Modifier.size(32.dp)) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.common_new_tab),
                    tint = TerminalGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    tab: TerminalTab,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = tab.runState is RunState.Running
    val dot = when {
        running -> TerminalGreen // 鲜明活跃绿
        tab.isBackground -> TerminalFg.copy(alpha = 0.5f)
        else -> TerminalFg.copy(alpha = 0.35f)
    }

    // Minis 风格深色 chrome：选中 = 蓝调高亮 + 加粗，未选中 = 深灰胶囊
    val bg = if (selected) AccentBlue.copy(alpha = 0.22f) else AccButtonBg.copy(alpha = 0.5f)
    val borderColor = if (selected) AccentBlue else Color.Transparent
    val fg = if (selected) Color.White else TerminalFg

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 运行状态指示点（激活且运行时带微发光外圈）
        Box(
            modifier = Modifier
                .size(if (selected && running) 10.dp else 8.dp)
                .clip(CircleShape)
                .background(dot)
                .then(
                    if (selected && running) Modifier.border(1.5.dp, dot.copy(alpha = 0.4f), CircleShape)
                    else Modifier
                )
        )
        Text(
            text = tab.title,
            color = fg,
            fontFamily = TerminalMono,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.terminal_close_tab),
                tint = if (selected) AccentBlue else TerminalFg.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun applyTerminalColors(
    view: TerminalView,
    tab: TerminalTab,
    settings: TerminalSettings
) {
    val preset = settings.theme
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
    for (i in 0 until minOf(16, preset.ansiColors.size)) {
        TerminalColors.COLOR_SCHEME.mDefaultColors[i] = preset.ansiColors[i]
    }

    tab.session.emulator?.mColors?.let { colors ->
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
        colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
        colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
        for (i in 0 until minOf(16, preset.ansiColors.size)) {
            colors.mCurrentColors[i] = preset.ansiColors[i]
        }
    }

    (tab.client as? AppTerminalSessionClient)?.cursorStyle = settings.cursorStyle
    tab.session.emulator?.setCursorStyle()

    view.onScreenUpdated()
    view.invalidate()
}

/** Termux TerminalView 的 Compose 包装：渲染与输入全部由该开源组件负责。 */
@Composable
private fun TerminalSurface(
    tab: TerminalTab,
    viewModel: TerminalViewModel,
    settings: TerminalSettings
) {
    val preset = settings.theme
    val bgColor = Color(preset.background)

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        factory = { ctx ->
            val view = TerminalView(ctx, null)
            view.setBackgroundColor(preset.background)
            val density = ctx.resources.displayMetrics.density
            view.setTextSize((settings.fontSizeSp * density).toInt())
            view.setTypeface(TerminalFontManager.loadTypeface(ctx, settings.fontPath) ?: Typeface.MONOSPACE)
            view.tag = (settings.fontSizeSp * density).toInt() to settings.fontPath
            view.setTerminalViewClient(
                AppTerminalViewClient(
                    context = ctx,
                    viewProvider = { view },
                    modifiers = viewModel.modifiers
                )
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            tab.view = view
            view.attachSession(tab.session)

            applyTerminalColors(view, tab, settings)
            view.requestFocus()
            view
        },
        update = { view ->
            view.setBackgroundColor(preset.background)
            val density = view.context.resources.displayMetrics.density
            val targetTextSize = (settings.fontSizeSp * density).toInt()
            val targetFontKey = targetTextSize to settings.fontPath
            if (view.tag != targetFontKey) {
                view.setTextSize(targetTextSize)
                view.setTypeface(TerminalFontManager.loadTypeface(view.context, settings.fontPath) ?: Typeface.MONOSPACE)
                view.tag = targetFontKey
            }

            applyTerminalColors(view, tab, settings)
        },
        onRelease = { view ->
            if (tab.view === view) tab.view = null
        }
    )
}

@Composable
private fun StatusView(
    loading: Boolean,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.md))
        }
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 把容器初始化进度状态映射为 Loading 阶段展示给用户的文案。 */
private fun containerInitMessage(context: Context, state: ContainerInitState): String = when (state) {
    is ContainerInitState.CleaningOldRootfs ->
        context.getString(R.string.terminal_cleaning_old_container, state.processed)
    is ContainerInitState.ExtractingRootfs ->
        context.getString(R.string.terminal_extracting_env, state.processed)
    is ContainerInitState.InstallingPackages ->
        context.getString(R.string.terminal_installing_packages, state.line ?: "")
    is ContainerInitState.Failed ->
        context.getString(R.string.terminal_preparing_env_failed, state.reason)
    ContainerInitState.Idle, ContainerInitState.Ready ->
        context.getString(R.string.terminal_preparing_env_first_run)
}


/** Minis 风格终端顶栏：圆形按钮 + 居中 Mono 标题（Aharou 移植）。 */
@Composable
private fun TerminalTopBar(
    embedded: Boolean,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onTools: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = FeatherIcons.X,
            contentDescription = stringResource(if (embedded) R.string.common_close else R.string.common_back),
            tint = TerminalFg,
            onClick = onClose,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_title),
            color = TerminalFg,
            fontFamily = TerminalMono,
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        CircleIconButton(
            icon = Icons.Default.Brush,
            contentDescription = stringResource(R.string.terminal_clear_screen),
            tint = TerminalGreen,
            onClick = onClear,
        )
        Spacer(modifier = Modifier.width(8.dp))
        CircleIconButton(
            icon = FeatherIcons.Tool,
            contentDescription = stringResource(R.string.terminal_tools_title),
            tint = TerminalFg,
            onClick = onTools,
        )
    }
}

/** 圆形图标按钮（Minis 顶栏样式）。 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TopButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Minis 风格触控键栏（移植版）：深色胶囊 + 可横滑按键。
 * 在 AiCode 原有键集上补齐 Minis 的「⏎ / 键盘开关」，其余机制
 * （CTRL/ALT 走 Termux modifiers、方向键长按连发）沿用。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyboardAccessoryBar(
    viewModel: TerminalViewModel,
    tabs: List<TerminalTab>,
    activeTabId: String?,
) {
    val scrollState = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current

    fun toggleKeyboard() {
        val v = tabs.firstOrNull { it.id == activeTabId }?.view ?: return
        if (v.hasFocus()) {
            v.clearFocus()
            keyboard?.hide()
        } else {
            v.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccessoryBg),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccChip("⌨") { toggleKeyboard() }
            AccChip("ESC") { viewModel.write("\u001b") }
            AccChip("TAB") { viewModel.write("\t") }
            AccChip("⏎") { viewModel.writeBytes(0x0D) }
            AccChip("CTRL", active = viewModel.modifiers.ctrl) {
                viewModel.modifiers.ctrl = !viewModel.modifiers.ctrl
            }
            AccChip("ALT", active = viewModel.modifiers.alt) {
                viewModel.modifiers.alt = !viewModel.modifiers.alt
            }
            AccChip("↑", repeatOnHold = true) { viewModel.write("\u001b[A") }
            AccChip("↓", repeatOnHold = true) { viewModel.write("\u001b[B") }
            AccChip("←", repeatOnHold = true) { viewModel.write("\u001b[D") }
            AccChip("→", repeatOnHold = true) { viewModel.write("\u001b[C") }
            AccChip("C-c") { viewModel.writeBytes(0x03) }
            AccChip("C-d") { viewModel.writeBytes(0x04) }
            AccChip("C-z") { viewModel.writeBytes(0x1A) }
            AccChip("C-l") { viewModel.writeBytes(0x0C) }
            AccChip("|") { viewModel.write("|") }
            AccChip("~") { viewModel.write("~") }
            AccChip("/") { viewModel.write("/") }
            AccChip("-") { viewModel.write("-") }
            AccChip("_") { viewModel.write("_") }
            AccChip(">") { viewModel.write(">") }
            AccChip(":") { viewModel.write(":") }
            AccChip("$") { viewModel.write("$") }
            AccChip("\"") { viewModel.write("\"") }
            AccChip("'") { viewModel.write("'") }
        }
    }
}

/** 方向键长按重复节奏：按下后延迟多久开始连续发送，以及每次发送的间隔。 */
private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
private const val KEY_REPEAT_INTERVAL_MS = 50L

/** Minis 风格按键胶囊。 */
@Composable
private fun AccChip(
    label: String,
    active: Boolean = false,
    repeatOnHold: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (active) AccentBlue else AccButtonBg
    val fg = if (active) Color.White else TerminalGreen

    var pressed by remember { mutableStateOf(false) }
    var sentRepeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        sentRepeated = false
        delay(KEY_REPEAT_INITIAL_DELAY_MS)
        while (pressed) {
            onClick()
            sentRepeated = true
            delay(KEY_REPEAT_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .height(28.dp)
            .widthChip(label)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(
                if (repeatOnHold) {
                    Modifier.pointerInput(onClick) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            waitForUpOrCancellation()
                            pressed = false
                            if (!sentRepeated) onClick()
                        }
                    }
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = TerminalMono,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/** 单字符按键给固定宽度，多字符按键自适应。 */
private fun Modifier.widthChip(label: String): Modifier =
    if (label.length <= 1) this.width(36.dp) else this
