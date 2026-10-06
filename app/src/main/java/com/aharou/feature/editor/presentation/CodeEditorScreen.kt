package com.aharou.feature.editor.presentation

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.domain.EditorSessionManager
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.editor.domain.diagnostics.EditorDiagnostics
import com.aharou.feature.editor.presentation.component.MarkdownPreviewWebView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Code
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Navigation
import compose.icons.feathericons.Search
import compose.icons.feathericons.Save
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 独立全屏代码编辑页。支持语法高亮、撤销/重做、底部符号快捷栏与保存。
 * 保存直接覆盖写回，不做外部并发写检测——AI 工具或终端可能同时改同一文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    path: String,
    onBack: () -> Unit,
    initialLine: Int = 0,
    embedded: Boolean = false,
    onAddSelectionToInput: (String) -> Unit,
    viewModel: CodeEditorViewModel = hiltViewModel()
) {
    // 标签页会话：外部每次带新 path 进编辑器都开（或激活）一个标签页，
    // 之前打开过的文件留在标签栏里，切文件不再丢未保存的改动。
    val tabs by EditorSessionManager.tabs.collectAsStateWithLifecycle()
    val sessionActivePath by EditorSessionManager.activePath.collectAsStateWithLifecycle()
    val snapshots by EditorSessionManager.snapshots.collectAsStateWithLifecycle()
    LaunchedEffect(path) { EditorSessionManager.open(path) }
    val activePath = sessionActivePath.ifBlank { path }
    // 定义跳转用「工作区内为 ~/workspace/…」的统一写法，当前路径也归一化后比对，
    // 免得同一个文件因为写法不同被当成两个路径、在标签栏里开出两份。
    val normalizedActivePath = viewModel.displayPath(activePath)
    LaunchedEffect(activePath) { viewModel.load(activePath) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // 已保存（或刚加载）的内容。脏标记按与它的差异判定，撤销回原样时才能自行复位。
    val baselineText = remember { mutableStateOf("") }
    var pendingSaveText by remember { mutableStateOf("") }
    var pendingExit by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showCloseTabDialog by remember { mutableStateOf(false) }
    var pendingCloseTab by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val editorFind = remember { EditorFindState() }
    // 语言服务器挂载状态：只有挂上之后才提供「跳转到定义」（没装服务器 / 非收录语言的入口不该露出来）。
    var lspAttached by remember(activePath) { mutableStateOf(false) }
    // 跳定义拿到的目标（路径到行号，行号 1 基，供 [EditorSurface] 定位）；跨文件时先等目标文件加载完。
    var jumpTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var previewMode by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    val isMarkdown = remember(activePath) {
        activePath.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    var editorBackground by remember { mutableStateOf<Color?>(null) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    // 切换标签页时重置编辑态，避免旧文件的撤销/脏标记残留到新文件；带未保存快照的页恢复脏标记与光标。
    LaunchedEffect(activePath) {
        canUndo = false
        canRedo = false
        dirty = snapshots[activePath]?.dirty == true
        pendingExit = false
        cursorLine = snapshots[activePath]?.cursorLine ?: 1
        cursorColumn = snapshots[activePath]?.cursorColumn ?: 1
        editorFind.close()
        previewMode = false
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.editor_save_success)
    val saveFailedText = stringResource(R.string.editor_save_failed)
    val noDefinitionText = stringResource(R.string.editor_goto_definition_not_found)
    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { result ->
            when (result) {
                is SaveResult.Success -> {
                    // 写盘完成后对应的未保存快照作废（内容已经落到磁盘上）。
                    EditorSessionManager.forget(result.path)
                    if (result.path == activePath) {
                        baselineText.value = pendingSaveText
                        dirty = false
                    }
                    Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                    if (pendingExit && result.path == activePath) {
                        pendingExit = false
                        onBack()
                    }
                }
                is SaveResult.Error -> {
                    pendingExit = false
                    Toast.makeText(
                        context,
                        result.detail ?: saveFailedText,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun requestSave() {
        val editor = editorRef.value ?: return
        pendingSaveText = editor.text.toString()
        viewModel.save(activePath, pendingSaveText)
    }

    fun handleBack() {
        if (editorFind.visible) {
            editorFind.close()
        } else if (dirty) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    /** 把当前编辑器的内容/光标/滚动存进会话，供切走后再切回来恢复。 */
    fun persistCurrentTab() {
        val editor = editorRef.value ?: return
        EditorSessionManager.saveSnapshot(
            activePath,
            EditorSessionManager.Snapshot(
                content = editor.text.toString(),
                dirty = dirty,
                cursorLine = cursorLine,
                cursorColumn = cursorColumn,
                scrollY = editor.scrollY
            )
        )
    }

    fun switchTab(target: String) {
        if (target == activePath) return
        persistCurrentTab()
        editorFind.close()
        EditorSessionManager.activate(target)
    }

    fun performCloseTab(target: String) {
        EditorSessionManager.close(target)
        if (EditorSessionManager.tabs.value.isEmpty()) onBack()
    }

    fun closeTab(target: String) {
        if (target == activePath) persistCurrentTab()
        if (EditorSessionManager.snapshot(target)?.dirty == true) {
            pendingCloseTab = target
            showCloseTabDialog = true
        } else {
            performCloseTab(target)
        }
    }

    BackHandler(enabled = !showSettings && !previewMode) { handleBack() }
    BackHandler(enabled = showSettings) { showSettings = false }

    /**
     * 跳转到光标处符号的定义：同一个文件直接定位，跨文件先经 [EditorSessionManager] 打开目标文件再定位。
     * 没挂语言服务器、服务器超时、或没找到定义都只提示一句，不动编辑器内容。
     */
    fun jumpToDefinition() {
        val editor = editorRef.value ?: return
        scope.launch {
            val target = viewModel.findDefinition(editor)
            if (target == null) {
                Toast.makeText(context, noDefinitionText, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (target.path != normalizedActivePath) {
                EditorSessionManager.open(target.path)
            }
            jumpTarget = target.path to (target.line + 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(
                                    if (embedded) R.string.common_close else R.string.common_back
                                )
                            )
                        }
                    },
                    actions = {
                        val editable = state is EditorUiState.Success
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = editorFind::open, enabled = editable && !previewMode) {
                                Icon(FeatherIcons.Search, contentDescription = stringResource(R.string.editor_find))
                            }
                            if (lspAttached) {
                                IconButton(
                                    onClick = { jumpToDefinition() },
                                    enabled = editable && !previewMode
                                ) {
                                    Icon(
                                        FeatherIcons.Navigation,
                                        contentDescription = stringResource(R.string.editor_goto_definition)
                                    )
                                }
                            }
                            if (isMarkdown && editable) {
                                IconButton(onClick = {
                                    editorFind.close()
                                    previewMode = !previewMode
                                }) {
                                    Icon(
                                        if (previewMode) FeatherIcons.Code else FeatherIcons.Eye,
                                        contentDescription = stringResource(
                                            if (previewMode) R.string.editor_md_show_source
                                            else R.string.editor_md_preview
                                        )
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editorRef.value?.undo() },
                                enabled = editable && canUndo
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = stringResource(R.string.editor_undo)
                                )
                            }
                            IconButton(
                                onClick = { editorRef.value?.redo() },
                                enabled = editable && canRedo
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = stringResource(R.string.editor_redo)
                                )
                            }
                            IconButton(
                                onClick = { requestSave() },
                                enabled = editable && dirty && !saving
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.Save,
                                        contentDescription = stringResource(R.string.common_save)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showSettings = true },
                                enabled = editable
                            ) {
                                Icon(
                                    FeatherIcons.Settings,
                                    contentDescription = stringResource(R.string.editor_settings)
                                )
                            }
                        }
                    }
                )
                if (editorFind.visible && state is EditorUiState.Success && !previewMode) {
                    EditorFindBar(editorFind)
                }
                HorizontalDivider(thickness = 0.5.dp)
                FileTitleBar(
                    fileName = activePath.substringAfterLast('/'),
                    dirty = dirty,
                    line = cursorLine,
                    column = cursorColumn
                )
                if (tabs.size > 1) {
                    HorizontalDivider(thickness = 0.5.dp)
                    EditorTabBar(
                        tabs = tabs,
                        activePath = activePath,
                        activeDirty = dirty,
                        snapshots = snapshots,
                        onSelect = { switchTab(it) },
                        onClose = { closeTab(it) }
                    )
                }
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (state is EditorUiState.Success && !previewMode) {
                EditorSymbolBar(
                    backgroundColor = editorBackground ?: MaterialTheme.colorScheme.background,
                    onInsert = { editorRef.value?.commitText(it) },
                    onIndent = { editorRef.value?.commitText("\t") },
                    onMoveLeft = { editorRef.value?.let { moveCursorHorizontally(it, forward = false) } },
                    onMoveRight = { editorRef.value?.let { moveCursorHorizontally(it, forward = true) } }
                )
            }
        }
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        when (val s = state) {
            is EditorUiState.Loading -> CenterBox(content) { CircularProgressIndicator() }
            is EditorUiState.Success -> Box(modifier = content) {
                // 有未保存快照时用快照文本而不是磁盘内容——切走再切回来，改动不能消失。
                val surfaceState = remember(activePath, s.content) {
                    snapshots[activePath]?.let { s.copy(content = it.content) } ?: s
                }
                key(activePath) {
                EditorSurface(
                    state = surfaceState,
                    modifier = Modifier.fillMaxSize(),
                    editorRef = editorRef,
                    settings = settings,
                    baselineText = baselineText,
                    initialLine = jumpTarget?.takeIf { it.first == normalizedActivePath }?.second ?: initialLine,
                    onAddSelectionToInput = onAddSelectionToInput,
                    onBackgroundResolved = { editorBackground = it },
                    onCursorChanged = { l, c ->
                        cursorLine = l
                        cursorColumn = c
                    },
                    onContentChanged = { undo, redo, changed ->
                        canUndo = undo
                        canRedo = redo
                        dirty = changed
                    },
                    editorFind = editorFind
                )
                }

                // 有语言服务器（Lua 等）时换上挂着 LSP 的编辑器语言，TextMate 继续负责着色；
                // 没装服务器 / 非收录语言则不上，保持原语言。
                val lspEditor = editorRef.value
                LaunchedEffect(lspEditor, activePath, surfaceState.scopeName) {
                    val editor = lspEditor ?: return@LaunchedEffect
                    val scope = surfaceState.scopeName ?: return@LaunchedEffect
                    lspAttached = viewModel.attachLsp(editor, TextMateLanguage.create(scope, false))
                }
                // 没有语言服务器时（如 Python）退回容器内的轻量语法检查，同样画波浪线；
                // 两种都不影响正常编辑：容器未就绪、检查器未装、文件过大都安静跳过。
                LaunchedEffect(lspEditor, activePath, baselineText.value, lspAttached) {
                    if (lspAttached) return@LaunchedEffect
                    EditorDiagnostics.refresh(context, lspEditor, activePath, baselineText.value.length)
                }
                if (previewMode) {
                    // 预览覆盖在编辑器上方（编辑器保留在组合中不销毁，切回源码不丢状态）。
                    // 进入预览时快照编辑器当前文本，确保反映未保存的编辑。
                    val previewText = remember(previewMode) {
                        editorRef.value?.text?.toString() ?: surfaceState.content
                    }
                    MarkdownPreviewWebView(
                        text = previewText,
                        path = activePath,
                        onExitPreview = { previewMode = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
            is EditorUiState.TooLarge -> CenterBox(content) {
                HintText(
                    stringResource(
                        R.string.editor_file_too_large,
                        s.sizeBytes / 1024 / 1024
                    )
                )
            }
            is EditorUiState.Binary -> CenterBox(content) {
                HintText(stringResource(R.string.editor_binary_unsupported))
            }
            is EditorUiState.Error -> CenterBox(content) {
                HintText(s.detail ?: stringResource(R.string.editor_load_failed))
            }
        }
    }

    if (showCloseTabDialog) {
        AlertDialog(
            onDismissRequest = {
                showCloseTabDialog = false
                pendingCloseTab = null
            },
            title = { Text(stringResource(R.string.editor_close_tab_unsaved_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.editor_close_tab_unsaved_message,
                        pendingCloseTab?.substringAfterLast('/') ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingCloseTab
                    showCloseTabDialog = false
                    pendingCloseTab = null
                    target?.let { performCloseTab(it) }
                }) { Text(stringResource(R.string.editor_close_tab_discard)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCloseTabDialog = false
                    pendingCloseTab = null
                }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    pendingExit = true
                    requestSave()
                }) { Text(stringResource(R.string.editor_save_and_exit)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text(stringResource(R.string.editor_dont_save)) }
            }
        )
    }

    if (showSettings) {
        EditorSettingsScreen(onBack = { showSettings = false })
    }
    }
}

/** 顶部标签栏：多文件时才出现；点选切换、点 × 关闭，未保存的页名前带圆点。 */
@Composable
private fun EditorTabBar(
    tabs: List<String>,
    activePath: String,
    activeDirty: Boolean,
    snapshots: Map<String, EditorSessionManager.Snapshot>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = tab == activePath
            val tabDirty = if (selected) activeDirty else snapshots[tab]?.dirty == true
            Row(
                modifier = Modifier
                    .padding(end = Spacing.xs)
                    .clip(RoundedCornerShape(Spacing.sm))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .clickable { onSelect(tab) }
                    .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tabDirty) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }
                Text(
                    text = tab.trimEnd('/').substringAfterLast('/'),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { onClose(tab) },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.editor_close_tab),
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 导航栏下方的文件名小栏：左侧文件名（未保存时名前加星号），右侧行:列与文件编码。 */
@Composable
private fun FileTitleBar(fileName: String, dirty: Boolean, line: Int, column: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(FILE_TITLE_BAR_HEIGHT)
            .padding(horizontal = FILE_TITLE_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dirty) {
            Text(
                text = "*",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$line:$column",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
        Text(
            text = FILE_ENCODING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )
    }
}

@Composable
private fun EditorSurface(
    state: EditorUiState.Success,
    modifier: Modifier,
    editorRef: MutableState<CodeEditor?>,
    settings: EditorSettings,
    baselineText: MutableState<String>,
    initialLine: Int = 0,
    onAddSelectionToInput: (String) -> Unit,
    onBackgroundResolved: (Color) -> Unit,
    onCursorChanged: (line: Int, column: Int) -> Unit,
    onContentChanged: (canUndo: Boolean, canRedo: Boolean, dirty: Boolean) -> Unit,
    editorFind: EditorFindState
) {
    // 与实际渲染出的 Compose 主题保持一致，而非跟随系统设置——应用内可单独切换主题。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeSurface = MaterialTheme.colorScheme.surface
    // sora 的语法分析在后台线程跑，首帧必然还没上色。用一次渐显护住这段窗口，
    // 把「先纯文本后突然上色」的跳变变成内容渐现。
    var revealed by remember(state.content) { mutableStateOf(false) }
    LaunchedEffect(state.content) { revealed = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = HIGHLIGHT_REVEAL_MS),
        label = "editor-reveal"
    )

    // 用户自己滚过之后就不再自动定位到目标行。View 只创建一次，标记要用实例稳定的 State
    // 承载（factory 闭包捕获的是首次那个实例），换文件时由下面的 effect 显式复位。
    val scrolledByUser = remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = contentAlpha },
        factory = { ctx ->
            TextMateSetup.applyTheme(dark)
            // sora 默认把 8 秒内的连续输入并成一条撤销记录，一次撤销会吞掉整段输入。
            UndoManager.setMergeTimeLimit(UNDO_MERGE_WINDOW_MS)
            CodeEditor(ctx).apply {
                // sora 的 getScrollMaxY() 在 LayoutParams 为空或高度是 WRAP_CONTENT 时会跳过
                // verticalExtraSpaceFactor，底部额外滚动空间（下面那行设的半屏留白）就没了。
                // Compose 的 AndroidView 添加子 View 时用的正是默认 WRAP_CONTENT，必须显式改掉。
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = true
                typefaceText = Typeface.MONOSPACE
                setTextSize(settings.fontSizeSp.toFloat())
                setWordwrap(settings.wordWrap)
                // 关闭光标移动动画：切换行/列时当前行高亮原位消失、目标位出现，不逐行滑动。
                isCursorAnimationEnabled = false
                // 行号左侧固定预留一点间距，避免数字贴边。
                setLineNumberMarginLeft(spToPx(ctx, LINE_NUMBER_MARGIN_SP))
                // 滚到底后仍可再上滑一段：额外视口空间为半屏（sora 默认值，显式固定）。
                verticalExtraSpaceFactor = VERTICAL_EXTRA_SPACE_FACTOR
                isBlockLineEnabled = settings.showIndentGuide
                nonPrintablePaintingFlags =
                    nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                state.scopeName?.let { scope ->
                    setEditorLanguage(TextMateLanguage.create(scope, false))
                }
                // 先 setText 再订阅：初始设置不计入脏标记，只有用户后续编辑才触发。
                setText(state.content)
                val editorView = this
                subscribeAlways(ContentChangeEvent::class.java) {
                    // UndoManager 先改文本、后移动栈指针，事件回调里同帧读 canUndo/canRedo 拿到的
                    // 是移动前的旧值（撤销后重做键不亮、撤销到底后撤销键仍亮），延后一帧才准。
                    editorView.post {
                        val baseline = baselineText.value
                        val text = editorView.text
                        onContentChanged(
                            editorView.canUndo(),
                            editorView.canRedo(),
                            text.length != baseline.length || text.toString() != baseline
                        )
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) {
                    onCursorChanged(cursor.leftLine + 1, cursor.leftColumn + 1)
                }
                subscribeAlways(ScrollEvent::class.java) { event ->
                    // sora 在尺寸变化后会经 touchHandler 做一次滚动 clamp，事件 cause 同样是
                    // CAUSE_USER_DRAG 但首尾坐标相同（y=0->0）。编辑器刚打开就会连发几条这种
                    // 零位移事件，只看 cause 会把它们当成「用户已手动滚动」而放弃行号定位。
                    val moved = event.startY != event.endY || event.startX != event.endX
                    if (moved &&
                        (event.cause == ScrollEvent.CAUSE_USER_DRAG ||
                            event.cause == ScrollEvent.CAUSE_USER_FLING)
                    ) {
                        scrolledByUser.value = true
                    }
                }
                editorRef.value = this
                editorFind.attach(this)
                installAddToInputAction(this, ctx.getString(R.string.common_add_to_input), onAddSelectionToInput)
            }
        },
        onRelease = {
            editorFind.release(it)
            editorRef.value = null
            it.release()
        }
    )

    LaunchedEffect(state.content) { baselineText.value = state.content }

    // 深浅主题切换时重设配色，而非重建编辑器——保住未保存内容与撤销栈。
    val editor = editorRef.value
    LaunchedEffect(dark, themeSurface, editor) {
        editor ?: return@LaunchedEffect
        TextMateSetup.applyTheme(dark)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        applyThemedBackground(editor, themeSurface)
        applyLineNumberBackground(editor, dark)
        editor.invalidate()
        onBackgroundResolved(Color(editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)))
    }
    LaunchedEffect(settings.fontSizeSp, editor) {
        editor?.setTextSize(settings.fontSizeSp.toFloat())
    }
    LaunchedEffect(settings.wordWrap, editor) {
        editor?.setWordwrap(settings.wordWrap)
    }
    // 从聊天区链接带行号打开时跳到目标行并滚动到可视区（行号 1 基，sora 为 0 基）。
    //
    // 必须等编辑器完成首次布局：ensurePositionVisible 按 getHeight() 算滚动量，而 onSizeChanged
    // 会按新尺寸 clamp 滚动偏移、自动换行下还会重建整个布局——宽高仍为 0 时跳的那一下会被随后的
    // 首次布局抹掉，表现就是「停在文件开头没跳过去」。
    //
    // 字号与自动换行读自 DataStore，真实值往往比首帧晚到，setTextSize / setWordwrap 同样重建布局
    // 打乱位置，所以它们也进 key 重新定位一次；用户自己滚动过之后（scrolledByUser）不再拽回目标行。
    LaunchedEffect(state.content, initialLine) { scrolledByUser.value = false }
    LaunchedEffect(editor, initialLine, state.content, settings.wordWrap, settings.fontSizeSp) {
        val editorView = editor ?: return@LaunchedEffect
        if (initialLine <= 0 || scrolledByUser.value) return@LaunchedEffect
        var frames = 0
        while ((editorView.width == 0 || editorView.height == 0) &&
            frames < LINE_JUMP_LAYOUT_WAIT_FRAMES
        ) {
            withFrameNanos { }
            frames++
        }
        if (editorView.width == 0 || editorView.height == 0 || scrolledByUser.value) {
            return@LaunchedEffect
        }
        val line = (initialLine - 1).coerceIn(0, (editorView.lineCount - 1).coerceAtLeast(0))
        // 定位后校验目标行是否真的停在期望位置，没停上就重试：布局重建（设置迟到、语法
        // 分析完成、自动换行重排）会把刚定好的位置冲掉，单次定位不够。
        var attempt = 0
        while (attempt < LINE_JUMP_MAX_ATTEMPTS && !scrolledByUser.value) {
            editorView.setSelection(line, 0)
            val desired = centerScrollOffset(editorView, line)
            if (desired != null) {
                scrollVerticallyTo(editorView, desired)
            } else {
                // 拿不到布局信息时退回官方的「仅保证可见」，无动画避免被布局重建打断在半路。
                editorView.ensurePositionVisible(line, 0, true)
            }
            repeat(LINE_JUMP_VERIFY_FRAMES) { withFrameNanos { } }
            val settled = if (desired != null) {
                abs(editorView.offsetY - desired) <= editorView.rowHeight
            } else {
                runCatching {
                    val layout = editorView.layout
                    line in layout.getLineNumberForRow(editorView.firstVisibleRow)..
                        layout.getLineNumberForRow(editorView.lastVisibleRow)
                }.getOrDefault(true)
            }
            if (settled) break
            attempt++
        }
    }
    LaunchedEffect(settings.showIndentGuide, editor) {
        editor?.isBlockLineEnabled = settings.showIndentGuide
    }
    LaunchedEffect(settings.showWhitespace, settings.showWrapArrow, editor) {
        editor?.nonPrintablePaintingFlags =
            nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
    }
}

@Composable
private fun CenterBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl)
    )
}

/** 符号栏方向键：把光标水平移动一个字符，跨越行首/行尾时换到相邻行。 */
private fun moveCursorHorizontally(editor: CodeEditor, forward: Boolean) {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (forward) {
        val lineLength = editor.text.getColumnCount(line)
        when {
            column < lineLength -> editor.setSelection(line, column + 1)
            line < editor.lineCount - 1 -> editor.setSelection(line + 1, 0)
        }
    } else {
        when {
            column > 0 -> editor.setSelection(line, column - 1)
            line > 0 -> editor.setSelection(line - 1, editor.text.getColumnCount(line - 1))
        }
    }
}

/**
 * 往 sora 自带的选中工具条上挂一个「加入输入栏」。
 *
 * 库没留扩展点，只能自己往它的按钮行里塞一个：工具条内容视图 = 横向滚动容器 > 按钮行，
 * 结构对不上就整体放弃（静默），一个入口不值得去冒崩溃风险。
 * 按钮只在有选区时露出——没选中就没有可投递的内容；点它把当前选中的文本交给 [onAdd]。
 */
private fun installAddToInputAction(editor: CodeEditor, label: String, onAdd: (String) -> Unit) {
    val scroller = editor.getComponent(EditorTextActionWindow::class.java)?.view as? ViewGroup
        ?: return
    val row = scroller.getChildAt(0) as? ViewGroup ?: return
    val buttons = row.getChildAt(0) as? ViewGroup ?: return

    val context = editor.context
    val selectableItem = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableItem, true)
    val density = context.resources.displayMetrics.density
    val horizontalPadding = (ADD_TO_INPUT_PADDING_DP * density).toInt()
    val button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ADD_TO_INPUT_TEXT_SP)
        setBackgroundResource(selectableItem.resourceId)
        minWidth = 0
        minimumWidth = 0
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        setOnClickListener {
            val cursor = editor.cursor
            val selected = editor.text.subSequence(cursor.left, cursor.right).toString()
            if (selected.isNotEmpty()) onAdd(selected)
        }
    }
    button.setTextColor(editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR))
    // 工具条换色时文字也要跟着走，否则切主题后这一项会压在相近的底色上看不清。
    editor.subscribeAlways(ColorSchemeUpdateEvent::class.java) {
        button.setTextColor(editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR))
    }
    val syncVisibility = {
        button.visibility = if (editor.cursor.isSelected) View.VISIBLE else View.GONE
    }
    editor.subscribeAlways(SelectionChangeEvent::class.java) { syncVisibility() }
    syncVisibility()

    buttons.addView(
        button,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
}


/** 内容渐显时长：给后台语法分析留出窗口，同时不致于让用户觉得打开变慢。 */
private const val HIGHLIGHT_REVEAL_MS = 200

/** 工具条上「加入输入栏」的字号（sp）与左右内边距（dp）：与库里的图标按钮同高，但不喧宾夺主。 */
private const val ADD_TO_INPUT_TEXT_SP = 13f
private const val ADD_TO_INPUT_PADDING_DP = 12f

/** 撤销合并窗口：间隔超过它的输入不再并入上一条撤销记录，撤销才是分步的。sora 默认 8000ms。 */
private const val UNDO_MERGE_WINDOW_MS = 500L

/** 行号左侧预留间距（sp）。 */
private const val LINE_NUMBER_MARGIN_SP = 2f

/** 文件名小栏高度与左右内边距。 */
private val FILE_TITLE_BAR_HEIGHT = 16.dp
private val FILE_TITLE_HORIZONTAL_PADDING = 12.dp

/** 文件读写统一按 UTF-8，小栏右侧展示用。 */
private const val FILE_ENCODING = "UTF-8"

/** 垂直额外视口空间系数（占编辑器高度的比例，取值 [0,1]），用于底部过度滚动。 */
private const val VERTICAL_EXTRA_SPACE_FACTOR = 0.5f

/** 带行号打开时等编辑器完成首次布局的最多帧数，避免宽高迟迟不就绪时死等。 */
private const val LINE_JUMP_LAYOUT_WAIT_FRAMES = 30

/** 定位后校验目标行是否停在期望位置的重试次数，以及每次校验前等待的帧数。 */
private const val LINE_JUMP_MAX_ATTEMPTS = 8
private const val LINE_JUMP_VERIFY_FRAMES = 3

/**
 * 让目标行居中所需的垂直滚动量（已按可滚范围收敛，文件首尾附近自然贴边）。
 * 布局尚不可用时返回 null，由调用方退回官方的「仅保证可见」。
 */
private fun centerScrollOffset(editor: CodeEditor, line: Int): Int? = runCatching {
    // getCharLayoutOffset 返回的 [0] 是该行所在 row 的底部 y。
    val rowBottom = editor.layout.getCharLayoutOffset(line, 0)[0]
    val rowTop = rowBottom - editor.rowHeight
    (rowTop - (editor.height - editor.rowHeight) / 2f).toInt()
        .coerceIn(0, editor.scrollMaxY.coerceAtLeast(0))
}.getOrNull()

/** 无动画落位到指定垂直滚动量，与 sora 内部 ensurePositionVisible 的无动画分支同款做法。 */
private fun scrollVerticallyTo(editor: CodeEditor, offsetY: Int) {
    val scroller = editor.scroller
    scroller.startScroll(editor.offsetX, editor.offsetY, 0, offsetY - editor.offsetY, 0)
    scroller.abortAnimation()
    editor.invalidate()
}
