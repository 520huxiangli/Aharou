package com.aharou.feature.agent.domain.mention

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「@ 引用工作区文件」的状态机：持有候选菜单状态、已加载文件与加载 Job。
 * 独立成单例，让 UI 侧（ChatInputBar / AIChatPanel / AIAgentViewModel）只做极薄转发，
 * 不把逻辑堆进已经很大的 AIAgentViewModel。
 */
@Singleton
class FileMentionController @Inject constructor(
    private val repository: FileMentionRepository
) {
    private val _state = MutableStateFlow<FileMentionState>(FileMentionState.Hidden)
    val state: StateFlow<FileMentionState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedProject: String? = null
    private var loadedFiles: List<String>? = null
    private var lastQuery: String = ""

    /**
     * 输入文本变化时驱动菜单：解析出 `@` 查询词就过滤候选，否则收起。
     * 同一工作区只枚举一次文件；切换工作区后首次输入重新枚举。
     */
    fun onInputChanged(text: String, workspacePath: String, scope: CoroutineScope) {
        val query = fileMentionQuery(text)
        if (query == null) {
            dismiss()
            return
        }
        lastQuery = query
        val files = loadedFiles
        if (files != null && loadedProject == workspacePath) {
            _state.value = visibleOf(query, files)
            return
        }
        if (loadJob?.isActive == true) return
        _state.value = FileMentionState.Loading
        loadJob = scope.launch {
            when (val result = repository.loadFiles(workspacePath)) {
                is LoadResult.Success -> {
                    loadedProject = workspacePath
                    loadedFiles = result.files
                    // 用最新查询词出结果：加载期间用户可能又打了几键
                    _state.value = visibleOf(lastQuery, result.files)
                }
                LoadResult.Unavailable -> _state.value = FileMentionState.Empty(lastQuery)
            }
        }
    }

    /** 收起菜单（选中候选 / 发送 / 输入不再含 `@` 后调用）。文件缓存保留，下次输入同工作区直接复用。 */
    fun dismiss() {
        loadJob?.cancel()
        loadJob = null
        lastQuery = ""
        _state.value = FileMentionState.Hidden
    }

    /** 当前展示态对应的查询词，非展示态返回 null。 */
    fun currentQuery(): String? = when (val s = _state.value) {
        is FileMentionState.Visible -> s.query
        is FileMentionState.Empty -> s.query
        else -> null
    }

    /** 按 [query] 在已加载文件里过滤（同步，纯计算）。 */
    fun match(query: String): List<String> = repository.match(loadedFiles.orEmpty(), query)

    private fun visibleOf(query: String, files: List<String>): FileMentionState {
        val matched = repository.match(files, query)
        return if (matched.isEmpty()) FileMentionState.Empty(query)
        else FileMentionState.Visible(query, matched)
    }
}
