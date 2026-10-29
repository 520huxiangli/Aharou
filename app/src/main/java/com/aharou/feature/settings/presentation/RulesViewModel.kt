package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.rule.RuleFileRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 规则页保存的是哪一份（用于按钮态与提示文案，两份共用一个页面）。 */
enum class RuleScope { GLOBAL, PROJECT }

/** 片段保存失败的原因，界面据此给具体提示。 */
enum class FragmentError { INVALID_NAME, DUPLICATE, IO }

/** 正在编辑的对象：[fragment] 为 null 表示主规则文件，否则是 `rules.d` 下的片段；[isNew] 只对片段有意义。 */
data class RuleEditTarget(
    val scope: RuleScope,
    val fragment: String? = null,
    val isNew: Boolean = false
)

/**
 * 规则页状态：列表视图看全局 / 项目两份主规则与各自的 `rules.d` 片段，
 * 进入编辑后 [editing] 非空，编辑内容放在 [editingContent]。
 *
 * 文件内容只在 [RulesViewModel.refresh] 与打开编辑时读；编辑中的文本留在界面本地，保存才写盘。
 */
data class RulesUiState(
    val global: RuleFileRepository.RuleDocument? = null,
    val project: RuleFileRepository.RuleDocument? = null,
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true,
    val savingScope: RuleScope? = null,
    val savedScope: RuleScope? = null,
    val failedScope: RuleScope? = null,
    val editing: RuleEditTarget? = null,
    val editingContent: String = "",
    val editingName: String = "",
    val saving: Boolean = false,
    val fragmentError: FragmentError? = null
)

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val rules: RuleFileRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RulesUiState())
    val state: StateFlow<RulesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                RulesUiState(
                    global = rules.readGlobal(),
                    project = rules.readProject(projectRoot),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.update { current ->
                loaded.copy(
                    savingScope = current.savingScope,
                    savedScope = current.savedScope,
                    failedScope = current.failedScope,
                    editing = current.editing,
                    editingContent = current.editingContent,
                    editingName = current.editingName
                )
            }
        }
    }

    /** 打开主规则编辑页（与记忆条目一样，点列表项进详情）。 */
    fun openRule(scope: RuleScope) {
        val document = documentOf(scope) ?: return
        _state.update {
            it.copy(
                editing = RuleEditTarget(scope),
                editingContent = document.content,
                editingName = "",
                fragmentError = null
            )
        }
    }

    fun openFragment(scope: RuleScope, name: String) {
        viewModelScope.launch {
            val dir = dirOf(scope)
            val content = dir?.let { withContext(Dispatchers.IO) { rules.readFragment(it, name) } }
            if (dir == null || content == null) return@launch
            _state.update {
                it.copy(
                    editing = RuleEditTarget(scope, fragment = name),
                    editingContent = content,
                    editingName = name,
                    fragmentError = null
                )
            }
        }
    }

    fun startNewFragment(scope: RuleScope) {
        _state.update {
            it.copy(
                editing = RuleEditTarget(scope, fragment = null, isNew = true),
                editingContent = "",
                editingName = "",
                fragmentError = null
            )
        }
    }

    fun closeEdit() {
        _state.update { it.copy(editing = null, editingContent = "", editingName = "", fragmentError = null) }
    }

    /** 保存当前编辑：主规则直接覆盖写回，片段走文件名校验后再写。 */
    fun saveEditing(name: String, content: String) {
        val target = _state.value.editing ?: return
        if (target.isNew || target.fragment != null) {
            saveFragment(target, name, content)
        } else {
            saveRuleFile(target.scope, content)
        }
    }

    fun deleteEditing() {
        val target = _state.value.editing ?: return
        val dir = dirOf(target.scope) ?: return
        val fragment = target.fragment
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (fragment != null) rules.deleteFragment(dir, fragment) else rules.deleteRuleFile(dir)
                }
            }
            _state.update { it.copy(editing = null, editingContent = "", editingName = "") }
            refresh()
        }
    }

    /** 保存提示只显示一瞬间，由界面在这之后调本方法清掉。 */
    fun clearSaveResult() {
        _state.update { it.copy(savedScope = null, failedScope = null) }
    }

    private fun saveRuleFile(scope: RuleScope, content: String) {
        _state.update { it.copy(saving = true, savedScope = null, failedScope = null) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            if (scope == RuleScope.PROJECT && projectRoot.isBlank()) {
                _state.update { it.copy(saving = false, failedScope = scope) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (scope == RuleScope.GLOBAL) {
                        rules.saveGlobal(content)
                    } else {
                        rules.saveProject(projectRoot, content)
                    }
                }
            }
            _state.update { current ->
                val document = result.getOrNull()
                val updated = if (scope == RuleScope.GLOBAL) {
                    current.copy(global = document ?: current.global)
                } else {
                    current.copy(project = document ?: current.project)
                }
                // 自动保存：成功后留在编辑页继续写，不把人踢回列表
                updated.copy(
                    saving = false,
                    savedScope = scope.takeIf { document != null },
                    failedScope = scope.takeIf { document == null }
                )
            }
        }
    }

    private fun saveFragment(target: RuleEditTarget, name: String, content: String) {
        val normalized = name.trim().let { if (it.endsWith(".md")) it else "$it.md" }
        if (!rules.isValidFragmentName(normalized)) {
            _state.update { it.copy(fragmentError = FragmentError.INVALID_NAME) }
            return
        }
        val existing = documentOf(target.scope)?.fragments.orEmpty()
        if (normalized != target.fragment && existing.contains(normalized)) {
            _state.update { it.copy(fragmentError = FragmentError.DUPLICATE) }
            return
        }
        val dir = dirOf(target.scope)
        if (dir == null) {
            _state.update { it.copy(fragmentError = FragmentError.IO) }
            return
        }
        _state.update { it.copy(saving = true, fragmentError = null) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    rules.saveFragment(dir, normalized, content)
                    val previous = target.fragment
                    if (!target.isNew && previous != null && previous != normalized) {
                        rules.deleteFragment(dir, previous)
                    }
                }.isSuccess
            }
            _state.update {
                it.copy(
                    saving = false,
                    savedScope = if (saved) target.scope else null,
                    failedScope = if (saved) null else target.scope,
                    fragmentError = if (saved) null else FragmentError.IO,
                    editing = if (saved) it.editing?.copy(fragment = normalized, isNew = false) else it.editing,
                    editingName = if (saved) normalized else it.editingName
                )
            }
            if (saved) refresh()
        }
    }

    private fun documentOf(scope: RuleScope): RuleFileRepository.RuleDocument? = when (scope) {
        RuleScope.GLOBAL -> _state.value.global
        RuleScope.PROJECT -> _state.value.project
    }

    private fun dirOf(scope: RuleScope): File? = when (scope) {
        RuleScope.GLOBAL -> rules.globalDir()
        RuleScope.PROJECT -> rules.projectDir(workspaceRepository.currentPath())
    }
}
