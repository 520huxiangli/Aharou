package com.aharou.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
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
    data class Error(val path: String, val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository,
    private val editorLspManager: EditorLspManager
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
            // 读盘期间用户可能已切到别的标签页，只在仍停留在这个路径时更新界面。
            if (currentPath == path) _uiState.value = state
        }
    }

    /** 把指定路径的内容写回磁盘。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。 */
    fun save(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val result = runCatching { fileAccess.writeFile(path, content) }
                    .fold(
                        onSuccess = {
                            markSaved(path, content)
                            SaveResult.Success(path)
                        },
                        onFailure = { e ->
                            FileLogger.w(TAG, "保存文件失败: $path", e)
                            SaveResult.Error(path, e.message)
                        }
                    )
                _saveEvents.send(result)
            } finally {
                _saving.value = false
            }
        }
    }

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
