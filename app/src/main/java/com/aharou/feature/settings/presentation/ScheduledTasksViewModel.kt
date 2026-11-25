package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.AutomationGlobalSwitch
import com.aharou.feature.agent.domain.schedule.ScheduledTaskRepository
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val globalSwitch: AutomationGlobalSwitch,
    chatSessionDao: ChatSessionDao
) : ViewModel() {

    /** 全部自动化的总开关；与单个任务的 enabled 分开管。 */
    private val _paused = MutableStateFlow(globalSwitch.isPaused())
    val paused: StateFlow<Boolean> = _paused

    fun setPaused(paused: Boolean) {
        globalSwitch.setPaused(paused)
        _paused.value = paused
    }

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
        enabled: Boolean,
        scheduleType: String = ScheduledTaskEntity.TYPE_INTERVAL,
        dailyMinuteOfDay: Int = 9 * 60,
        onceAtMillis: Long = 0L,
        triggerType: String = ScheduledTaskEntity.TYPE_TIME,
        triggerPackage: String = "",
        triggerBatteryBelow: Int = -1,
        triggerOnCharging: Boolean = false,
        triggerSsid: String = "",
        triggerLatitude: Double? = null,
        triggerLongitude: Double? = null,
        triggerRadiusMeters: Int = 200,
        triggerOnEnter: Boolean = true,
        triggerOnExit: Boolean = true,
        triggerMcpServer: String = "",
        triggerMcpResourceUri: String = "",
        conditionWindowFrom: Int = -1,
        conditionWindowTo: Int = -1,
        condRequireCharging: Boolean = false,
        condMinBattery: Int = -1,
        debounceMinutes: Int = 10
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
                enabled = enabled,
                scheduleType = scheduleType,
                dailyMinuteOfDay = dailyMinuteOfDay,
                onceAtMillis = onceAtMillis,
                triggerType = triggerType,
                triggerPackage = triggerPackage,
                triggerBatteryBelow = triggerBatteryBelow,
                triggerOnCharging = triggerOnCharging,
                triggerSsid = triggerSsid,
                triggerLatitude = triggerLatitude,
                triggerLongitude = triggerLongitude,
                triggerRadiusMeters = triggerRadiusMeters,
                triggerOnEnter = triggerOnEnter,
                triggerOnExit = triggerOnExit,
                triggerMcpServer = triggerMcpServer,
                triggerMcpResourceUri = triggerMcpResourceUri,
                conditionWindowFrom = conditionWindowFrom,
                conditionWindowTo = conditionWindowTo,
                condRequireCharging = condRequireCharging,
                condMinBattery = condMinBattery,
                debounceMinutes = debounceMinutes
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
