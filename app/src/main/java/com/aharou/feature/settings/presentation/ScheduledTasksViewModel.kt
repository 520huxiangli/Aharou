package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.ScheduledTaskRepository
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页「定时任务」分区的状态与操作入口。 */
@HiltViewModel
class ScheduledTasksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScheduledTaskRepository,
    private val scheduler: ScheduledTaskScheduler,
    chatSessionDao: ChatSessionDao
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTaskEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可作为运行目标的根会话（跨工作区，最近更新在前）。 */
    val sessions: StateFlow<List<ChatSessionEntity>> = chatSessionDao.getAllRootSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        existing: ScheduledTaskEntity?,
        name: String,
        prompt: String,
        targetSessionId: String?,
        workspacePath: String?,
        intervalMinutes: Int,
        maxRuns: Int,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            repository.save(
                existing = existing,
                name = name,
                prompt = prompt,
                targetSessionId = targetSessionId,
                workspacePath = workspacePath,
                intervalMinutes = intervalMinutes,
                maxRuns = maxRuns,
                enabled = enabled
            )
            scheduler.ensureScheduled(context)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            scheduler.ensureScheduled(context)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            scheduler.ensureScheduled(context)
        }
    }
}
