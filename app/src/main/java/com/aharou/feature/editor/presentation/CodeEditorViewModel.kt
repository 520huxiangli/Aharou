package com.aharou.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.domain.EditorSessionManager
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.editor.lsp.DefinitionTarget
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository,
    private val editorLspManager: EditorLspManager,
    private val versions: FileVersionRegistry,
    private val workspaceIdentity: EditorWorkspaceIdentity
) : ViewModel() {
    private data class DocumentKey(val root: String, val path: String)
    private data class Binding(val root: String, val identity: String, val generation: Long, val access: FileAccessProvider)

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    private val _documentVersion = MutableStateFlow(0)
    val documentVersion: StateFlow<Int> = _documentVersion.asStateFlow()
    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    private var binding: Binding? = null
    private var generation = 0L
    private var requestId = 0L
    private var loadId = 0L
    private var currentPath: String? = null
    private val loadedStates = DocumentCache()
    private val loadedFingerprints = mutableMapOf<DocumentKey, String?>()
    private val pendingSaves = mutableSetOf<EditorSaveRequest>()
    private val writeMutex = Mutex()

    fun bindWorkspace(root: String, identity: String): Long {
        require(root.isNotBlank())
        binding?.takeIf { it.root == root && it.identity == identity }?.let { return it.generation }
        binding = Binding(root, identity, ++generation, fileAccess.forWorkspace(root))
        currentPath = null
        loadId++
        _uiState.value = EditorUiState.Loading
        _saving.value = false
        return generation
    }

    fun unbindWorkspace(generation: Long) {
        if (binding?.generation != generation) return
        binding = null
        currentPath = null
        loadId++
        _uiState.value = EditorUiState.Loading
        _saving.value = false
    }

    fun savedContent(identity: String, path: String): String? =
        EditorSessionManager.forWorkspace(identity).snapshot(path)?.baselineContent
            ?: (loadedStates[DocumentKey(identity, path)] as? EditorUiState.Success)?.content

    fun isShowing(root: String, identity: String, path: String): Boolean =
        binding?.let { it.root == root && it.identity == identity && currentPath == path } == true

    fun accepts(request: EditorSaveRequest): Boolean =
        binding?.let { it.identity == request.workspaceIdentity && it.generation == request.generation } == true

    fun createSaveRequest(path: String, content: String): EditorSaveRequest? {
        val bound = binding ?: return null
        return EditorSaveRequest(bound.root, bound.identity, bound.generation, ++requestId, path, content)
    }

    private class DocumentCache {
        private val entries = LinkedHashMap<DocumentKey, EditorUiState>(8, 0.75f, true)
        private var chars = 0L

        operator fun get(key: DocumentKey): EditorUiState? = entries[key]

        operator fun set(key: DocumentKey, state: EditorUiState) {
            remove(key)
            entries[key] = state
            chars += weightOf(state)
            while (entries.size > 8 || chars > 8L * 1024 * 1024) {
                val eldest = entries.keys.firstOrNull() ?: break
                remove(eldest)
            }
        }

        fun remove(key: DocumentKey): EditorUiState? = entries.remove(key)?.also { chars -= weightOf(it) }

        private fun weightOf(state: EditorUiState): Long =
            if (state is EditorUiState.Success) state.content.length.toLong() else 0L
    }

    suspend fun attachLsp(editor: CodeEditor, wrapper: Language): Boolean {
        val path = currentPath ?: return false
        return editorLspManager.attach(editor, path, wrapper)
    }

    suspend fun findDefinition(editor: CodeEditor): DefinitionTarget? {
        val path = currentPath ?: return null
        val cursor = editor.cursor
        return editorLspManager.findDefinition(path, cursor.leftLine, cursor.leftColumn)
    }

    fun displayPath(path: String): String = editorLspManager.displayPath(path)

    fun load(path: String) {
        val bound = binding ?: return
        if (currentPath == path) return
        currentPath = path
        val key = DocumentKey(bound.identity, path)
        val loading = ++loadId
        loadedStates[key]?.let { cached ->
            _uiState.value = cached
            _documentVersion.value++
            return
        }
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch {
            val (state, fingerprint) = withContext(Dispatchers.IO) {
                val state = try {
                    check(workspaceIdentity.matches(bound.root, bound.identity))
                    readDocument(bound.access, path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "打开文件失败: $path", e)
                    EditorUiState.Error(e.message)
                }
                state to if (state is EditorUiState.Success) {
                    runCatching { bound.access.fingerprint(path) }.getOrNull()
                } else null
            }
            if (binding !== bound || loading != loadId) return@launch
            loadedStates[key] = state
            val draft = EditorSessionManager.forWorkspace(bound.identity).snapshot(path)?.takeIf { it.dirty }
            loadedFingerprints[key] = draft?.let { sha256Fingerprint(it.baselineContent.toByteArray(Charsets.UTF_8)) }
                ?: fingerprint
            fingerprint?.let { versions.recordExternal(bound.access.toDisplayPath(path), it) }
            if (currentPath == path) {
                _uiState.value = state
                _documentVersion.value++
            }
        }
    }

    private fun readDocument(access: FileAccessProvider, path: String): EditorUiState {
        if (!access.exists(path) || !access.isFile(path)) return EditorUiState.Error(null)
        val size = access.fileSize(path)
        if (size > MAX_EDITABLE_BYTES) return EditorUiState.TooLarge(size)
        val bytes = access.readBytes(path)
        if (looksBinary(bytes)) return EditorUiState.Binary
        TextMateSetup.ensureInitialized(context)
        val scope = TextMateSetup.scopeNameFor(path)
        if (scope != null) runCatching { TextMateLanguage.create(scope, false).destroy() }
        return EditorUiState.Success(bytes.toString(Charsets.UTF_8), scope)
    }

    fun save(request: EditorSaveRequest) = submitSave(request, force = false)

    fun saveForce(request: EditorSaveRequest) = submitSave(request, force = true)

    private fun submitSave(request: EditorSaveRequest, force: Boolean) {
        val bound = binding?.takeIf { accepts(request) } ?: return
        if (!pendingSaves.add(request)) return
        _saving.value = true
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val key = DocumentKey(bound.identity, request.path)
                    val baseline = loadedFingerprints[key]
                    val result = withContext(Dispatchers.IO) {
                        try {
                            check(workspaceIdentity.matches(bound.root, bound.identity))
                            val current = bound.access.fingerprint(request.path)
                            if (!force && baseline != null && current != baseline) {
                                SaveResult.Conflict(request, bound.access.readFile(request.path)) to null
                            } else {
                                check(workspaceIdentity.matches(bound.root, bound.identity))
                                bound.access.writeFile(request.path, request.content)
                                SaveResult.Success(request) to
                                    runCatching { bound.access.fingerprint(request.path) }.getOrNull()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "保存文件失败: ${request.path}", e)
                            SaveResult.Error(request, e.message) to null
                        }
                    }
                    if (result.first is SaveResult.Success) {
                        EditorSessionManager.forWorkspace(bound.identity).markSaved(request.path, request.content)
                        loadedStates[key] = EditorUiState.Success(request.content,
                            (loadedStates[key] as? EditorUiState.Success)?.scopeName)
                        loadedFingerprints[key] = result.second
                        if (binding === bound) {
                            result.second?.let { versions.recordExternal(bound.access.toDisplayPath(request.path), it) }
                            markSaved(key, request.content)
                        }
                    }
                    _saveEvents.send(result.first)
                }
            } finally {
                pendingSaves.remove(request)
                _saving.value = pendingSaves.any { accepts(it) }
            }
        }
    }

    fun reloadFromDisk(path: String) {
        val bound = binding ?: return
        val key = DocumentKey(bound.identity, path)
        loadedStates.remove(key)
        loadedFingerprints.remove(key)
        if (currentPath == path) {
            currentPath = null
            load(path)
        }
    }

    private fun markSaved(key: DocumentKey, content: String) {
        val scope = (loadedStates[key] as? EditorUiState.Success)?.scopeName
        val saved = EditorUiState.Success(content, scope)
        loadedStates[key] = saved
        if (currentPath == key.path) _uiState.value = saved
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 8000

        fun looksBinary(bytes: ByteArray): Boolean {
            for (i in 0 until minOf(bytes.size, BINARY_SNIFF_BYTES)) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
