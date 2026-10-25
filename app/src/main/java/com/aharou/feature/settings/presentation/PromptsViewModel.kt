package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.prompt.PromptFragment
import com.aharou.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aharou.feature.agent.domain.prompt.PromptFragmentSource
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提示词页状态：按编号列出最终生效的片段（含来源），以及内置开关。 */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 没有选中工作区时项目层不可写，编辑/新建回落到全局层。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * 提示词页状态：四级来源（项目 > 全局 > 本地 > 内置）的生效片段列表 + 内置开关。
 *
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** 保存某编号的覆盖（新建与编辑同一入口）：写到所选作用域层；编辑改编号时清掉旧编号。 */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            refresh()
        }
    }

    /** 删除某编号的覆盖，自动回退到下一层。 */
    fun deleteFragment(number: Int) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { catalog.deleteOverride(number, projectRoot) }
            refresh()
        }
    }

    /**
     * 清空项目层与全局层里带编号的自定义片段，内置片段与本地副本不受影响。
     *
     * catalog 没有「全删」入口，这里按其既有 API 组合：同一编号可能两层各有一份，
     * 而 [PromptFragmentCatalog.deleteOverride] 一次只删当前生效层，删完下一层才会暴露。
     */
    fun resetCustomFragments() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.list(projectRoot)
                    .filter { it.editable }
                    .map { it.number }
                    .distinct()
                    .forEach { number -> deleteEveryCustomLayer(number, projectRoot) }
            }
            refresh()
        }
    }

    /**
     * 逐层删同一个编号的自定义片段：项目层与全局层可能各有一份，同一层里还可能有多个同号文件
     * （手工放进去的）。[PromptFragmentCatalog.deleteOverride] 一次只删当前生效的那个文件，
     * 所以删到生效层不再是可写来源为止；轮数上限只用于防异常目录死循环。
     */
    private fun deleteEveryCustomLayer(number: Int, projectRoot: String) {
        repeat(MAX_RESET_ROUNDS) {
            val fragment = catalog.fragment(number, projectRoot) ?: return
            if (!fragment.editable) return
            if (!catalog.deleteOverride(number, projectRoot)) return
        }
    }

    /**
     * 批量删除自定义片段：逐编号删尽各层（同 [deleteEveryCustomLayer] 的层叠规则），
     * 全部删完只重读一次列表。
     */
    fun deleteFragments(numbers: Collection<Int>) {
        val targets = numbers.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                targets.forEach { number -> deleteEveryCustomLayer(number, projectRoot) }
            }
            refresh()
        }
    }

    /** 拖拽重排：按新顺序重新编号并落盘，改变注入顺序。乐观更新本地状态，避免重读导致列表跳动。 */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val numbers = reordered.map { it.number }.sorted()
        val renumbered = reordered.mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }

    private companion object {
        const val MAX_RESET_ROUNDS = 8
    }
}
