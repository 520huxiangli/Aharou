package com.aharou.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.lsp.DefinitionTarget
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器页状态。 */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** 保存结果，一次性事件，经 [CodeEditorViewModel.saveEvents] 下发；带 path 以便标签页分辨来源。 */
sealed interface SaveResult {
    data class Success(val path: String) : SaveResult

    /**
     * 磁盘内容在编辑器打开之后被改过（多半是 AI 会话写的），本次保存**未写入**。
     * 携带磁盘当前内容供 UI 展示差异，由用户决定覆盖磁盘还是放弃自己的编辑，不静默覆盖也不静默丢弃。
     */
    data class Conflict(val path: String, val diskContent: String) : SaveResult

    data class Error(val path: String, val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository,
    private val editorLspManager: EditorLspManager,
    private val versions: FileVersionRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** 是否正在写盘（远程模式下经 SFTP，耗时较长，供 UI 转圈）。 */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    private var currentPath: String? = null

    /** 每个路径只读一次盘，结果缓存下来供标签页来回切换复用。 */
    private val loadedStates = mutableMapOf<String, EditorUiState>()

    /** 各路径「打开时磁盘内容」的指纹，用于保存前判断文件是否被 AI/其他会话改过。 */
    private val loadedFingerprints = mutableMapOf<String, String?>()

    /**
     * 把当前文件挂到语言服务器上（成功返回 true）。[wrapper] 是原本的 TextMate 语言，继续负责着色；
     * 没装服务器 / 非收录语言 / 容器未就绪都返回 false，调用方据此退回轻量语法检查。
     */
    suspend fun attachLsp(editor: CodeEditor, wrapper: Language): Boolean {
        val path = currentPath ?: return false
        return editorLspManager.attach(editor, path, wrapper)
    }

    /** 光标处符号的定义位置；未挂语言服务器、没找到定义、服务器未响应都返回 null。 */
    suspend fun findDefinition(editor: CodeEditor): DefinitionTarget? {
        val path = currentPath ?: return null
        val cursor = editor.cursor
        return editorLspManager.findDefinition(path, cursor.leftLine, cursor.leftColumn)
    }

    /** 编辑器路径的统一写法（工作区内为 `~/workspace/…`），用于判断定义目标是不是当前文件。 */
    fun displayPath(path: String): String = editorLspManager.displayPath(path)

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        if (currentPath == path) return
        currentPath = path
        loadedStates[path]?.let { cached ->
            _uiState.value = cached
            return
        }
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val state = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching EditorUiState.Error(null)
                }
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching EditorUiState.TooLarge(size)
                }
                val bytes = fileAccess.readBytes(path)
                if (looksBinary(bytes)) {
                    return@runCatching EditorUiState.Binary
                }
                // 语法包解析放在这里，确保 AndroidView factory 在主线程创建编辑器时 registry 已就绪。
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    // 预热 grammar：某种语法首次构造要编译大量正则，不在这里做就会压到主线程并拖后首次上色。
                    // 不将对象传给 UI：Language 绑编辑器生命周期，editor.release() 会连带销毁它，
                    // 跨重建复用已销毁实例会出问题——此处仅为把编译结果缓进 registry。
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                EditorUiState.Success(
                    content = bytes.toString(Charsets.UTF_8),
                    scopeName = scope
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "打开文件失败: $path", e)
                EditorUiState.Error(e.message)
            }
            loadedStates[path] = state
            if (state is EditorUiState.Success) refreshBaseline(path)
            // 读盘期间用户可能已切到别的标签页，只在仍停留在这个路径时更新界面。
            if (currentPath == path) _uiState.value = state
        }
    }

    /** 刷新指定路径的磁盘指纹基线，并登记给并发保护（AI 会话据此把手上版本视为过期）。 */
    private fun refreshBaseline(path: String) {
        val fingerprint = runCatching { fileAccess.fingerprint(path) }.getOrNull()
        loadedFingerprints[path] = fingerprint
        fingerprint?.let { versions.recordExternal(fileAccess.toDisplayPath(path), it) }
    }

    /**
     * 把指定路径的内容写回磁盘。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。
     *
     * 写盘前先比对磁盘指纹：文件在编辑器打开之后被 AI 会话或别的进程改过时**不写入**，
     * 改为下发 [SaveResult.Conflict]，由用户决定覆盖还是放弃自己的编辑——否则这次保存会静默抹掉对方的改动。
     */
    fun save(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val baseline = loadedFingerprints[path]
                val current = runCatching { fileAccess.fingerprint(path) }.getOrNull()
                if (baseline != null && current != null && current != baseline) {
                    FileLogger.w(TAG, "保存冲突（磁盘内容已变）: $path")
                    val disk = runCatching { fileAccess.readFile(path) }.getOrNull().orEmpty()
                    _saveEvents.send(SaveResult.Conflict(path, disk))
                    return@launch
                }
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /** 无视冲突直接覆盖磁盘（用户在冲突提示里选了「覆盖」）；仍走 [fileAccess] 的原子写。 */
    fun saveForce(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 放弃编辑器里的修改、重新从磁盘加载（用户在冲突提示里选了「放弃我的修改」）。
     * [load] 会因路径未变直接返回、且命中缓存，故先清掉这条路径的状态与缓存再加载。
     */
    fun reloadFromDisk(path: String) {
        loadedStates.remove(path)
        loadedFingerprints.remove(path)
        if (currentPath == path) currentPath = null
        load(path)
    }

    /** 真正写盘并刷新版本基线，避免紧接着的第二次保存被自己上一次的写入判成冲突。 */
    private fun writeThrough(path: String, content: String): SaveResult =
        runCatching { fileAccess.writeFile(path, content) }
            .fold(
                onSuccess = {
                    refreshBaseline(path)
                    markSaved(path, content)
                    SaveResult.Success(path)
                },
                onFailure = { e ->
                    FileLogger.w(TAG, "保存文件失败: $path", e)
                    SaveResult.Error(path, e.message)
                }
            )

    /**
     * 保存成功后把缓存与当前 UI 状态同步成落盘内容。
     *
     * 不同步的话，uiState 会滞在「打开时读到的那份内容」，编辑器页的
     * `LaunchedEffect(state.content)` 会拿这份旧内容去覆盖它自己的 baselineText；
     * 新建文件（打开时内容是空串）编辑后保存就会因此被判成「仍有未保存修改」——
     * 标题一直挂 `*`、返回时反复弹「未保存的修改」。2026-10-05 实机踩到。
     */
    private fun markSaved(path: String, content: String) {
        val scope = (loadedStates[path] as? EditorUiState.Success)?.scopeName
            ?: (_uiState.value as? EditorUiState.Success)?.scopeName
        val saved = EditorUiState.Success(content = content, scopeName = scope)
        loadedStates[path] = saved
        if (currentPath == path) {
            _uiState.value = saved
        }
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** 全量读入内存，超过该体积拒绝打开以避免 OOM 与长时间卡顿。 */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

        /** 二进制嗅探的采样字节数：与 git 一致，仅看开头一段。 */
        const val BINARY_SNIFF_BYTES = 8000

        /** 二进制判定：开头采样段内出现 NUL 字节即视为二进制（文本文件不含 NUL，误判率极低）。 */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
