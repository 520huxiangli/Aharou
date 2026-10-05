package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.workspace.domain.WorkspaceSearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 侧边栏「文件」Tab 的工作区搜索：关键词防抖后交给 [WorkspaceSearchEngine]，
 * 结果按文件分组由 UI 完成（与「会话」Tab 的搜索同一套交互：输入即出结果、点击跳转）。
 *
 * 独立成一个 ViewModel（而不是塞进 [AIAgentViewModel]）是为了不碰行数棘轮基线文件，
 * 搜索本身也不依赖会话状态：搜索根就是当前工作区。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class FileSearchViewModel @Inject constructor(
    private val engine: WorkspaceSearchEngine
) : ViewModel() {

    private companion object {
        /** 输入停顿多久才真正发起搜索：与 VS Code 的 searchOnTypeDebouncePeriod 同值（300ms）。 */
        const val DEBOUNCE_MS = 300L
    }

    private val _query = MutableStateFlow("")

    /** 输入框里的原始关键词（可能还没触发搜索）。 */
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<FileSearchState> = _query
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { raw ->
            val keyword = raw.trim()
            if (keyword.isEmpty()) {
                flowOf(FileSearchState(query = raw))
            } else {
                flow {
                    emit(FileSearchState(query = raw, loading = true))
                    val result = engine.search(keyword)
                    emit(
                        FileSearchState(
                            query = raw,
                            hits = result.hits,
                            truncated = result.truncated
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FileSearchState())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clear() {
        _query.value = ""
    }
}
