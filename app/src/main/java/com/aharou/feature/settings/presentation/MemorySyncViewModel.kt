package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.memory.MemorySyncManager
import com.aharou.feature.agent.domain.memory.MemorySyncOutcome
import com.aharou.feature.agent.domain.memory.MemorySyncScheduler
import com.aharou.feature.agent.domain.memory.MemorySyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆同步的界面状态：仓库地址、是否正在同步、上次结果。
 *
 * 单独成一个 ViewModel 而不是并进 [SettingsViewModel]：后者已经是超大文件（行数棘轮在盯），
 * 同步这块与设置页其它内容没有耦合，独立更好维护。
 */
@HiltViewModel
class MemorySyncViewModel @Inject constructor(
    private val syncManager: MemorySyncManager,
    private val settings: MemorySyncSettings,
    private val scheduler: MemorySyncScheduler,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _outcome = MutableStateFlow<MemorySyncOutcome?>(null)
    val outcome: StateFlow<MemorySyncOutcome?> = _outcome.asStateFlow()

    fun repoUrl(): String = settings.repoUrl

    fun saveRepoUrl(url: String) {
        settings.repoUrl = url
        // 排不排周期任务取决于「开关 + 地址」两者，地址改了要重排；顺手推一次，省得等下一个周期。
        scheduler.apply()
        if (settings.autoSync) syncNow()
    }

    fun autoSyncEnabled(): Boolean = settings.autoSync

    /** 后台同步的真实间隔（分钟），用于界面提示。 */
    fun autoSyncIntervalMinutes(): Int = settings.autoSyncIntervalMinutes

    fun setAutoSync(enabled: Boolean) {
        settings.autoSync = enabled
        scheduler.apply()
        // 刚打开就立刻跑一次：不必等第一个周期，界面上也就不必再留一个手动按钮。
        if (enabled) syncNow()
    }

    /** 上次同步的结果文案；从未同步过为空串。 */
    fun lastResult(): String = settings.lastResult

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val result = runCatching { syncManager.sync() }
                .getOrElse { MemorySyncOutcome(false, it.message.orEmpty().ifBlank { "同步失败" }) }
            _syncing.value = false
            _outcome.value = result
        }
    }

    fun consumeOutcome() {
        _outcome.value = null
    }
}
