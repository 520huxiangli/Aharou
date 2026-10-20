package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页「任务结束后自动清理临时终端」开关的状态源。
 *
 * 单独成类而不是并进 [SettingsViewModel]：那个文件顶在架构门禁的行数基线上、零余量，
 * 而这个开关只需要一条流和一个写入口。
 */
@HiltViewModel
class TerminalCleanupSettingsViewModel @Inject constructor(
    private val terminalSettingsRepository: TerminalSettingsRepository
) : ViewModel() {

    /** 开关当前值；初始值与仓库缺省一致（开启），避免首帧闪一下关。 */
    val autoCloseFinishedAgentTabs: StateFlow<Boolean> =
        terminalSettingsRepository.autoCloseFinishedAgentTabsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setAutoCloseFinishedAgentTabs(enabled: Boolean) {
        viewModelScope.launch { terminalSettingsRepository.setAutoCloseFinishedAgentTabs(enabled) }
    }
}
