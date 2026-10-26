package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamForm
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamSaveError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 编辑器保存状态：界面据此显示错误并决定何时关闭。 */
sealed interface AgentTeamSaveState {
    data object Idle : AgentTeamSaveState
    data object Saving : AgentTeamSaveState
    data object Saved : AgentTeamSaveState
    data class Failed(val error: AgentTeamSaveError) : AgentTeamSaveState
}

/**
 * 团队预设列表的界面状态：进入设置页时扫一次，删除与保存后重扫。
 *
 * 单独成 ViewModel 而不是并进 [SettingsViewModel]：后者已是超大文件（行数棘轮在盯），
 * 团队列表与设置页其它内容没有耦合。
 */
@HiltViewModel
class TeamSettingsViewModel @Inject constructor(
    private val repository: AgentTeamRepository
) : ViewModel() {

    private val _teams = MutableStateFlow<List<AgentTeamEntry>>(emptyList())
    val teams: StateFlow<List<AgentTeamEntry>> = _teams.asStateFlow()

    private val _saveState = MutableStateFlow<AgentTeamSaveState>(AgentTeamSaveState.Idle)
    val saveState: StateFlow<AgentTeamSaveState> = _saveState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    /** 删除预设文件后立刻重扫，避免列表停在被删的那一份上。 */
    fun delete(entry: AgentTeamEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(entry.team.name, entry.scope)
            load()
        }
    }

    /** 新建或编辑保存；[originalName] 为编辑前的名字，新建传 null。 */
    fun save(form: AgentTeamForm, originalName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = AgentTeamSaveState.Saving
            val error = repository.save(form, originalName)
            if (error == null) {
                load()
                _saveState.value = AgentTeamSaveState.Saved
            } else {
                _saveState.value = AgentTeamSaveState.Failed(error)
            }
        }
    }

    /** 重新打开编辑器前清掉上一次的保存结果，否则旧错误会闪在空表单上。 */
    fun resetSaveState() {
        _saveState.value = AgentTeamSaveState.Idle
    }

    private suspend fun load() {
        _teams.value = runCatching { repository.listAll() }.getOrElse { e ->
            FileLogger.w(TAG, "读取团队预设失败: ${e.message}", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "TeamSettingsViewModel"
    }
}
