package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.agent.domain.subagent.SubAgentSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 子代理并发上限的界面状态。
 *
 * 单独成一个 ViewModel 而不是并进 [SettingsViewModel]：后者已是超大文件（行数棘轮在盯），
 * 上限这一项与设置页其它内容没有耦合。
 */
@HiltViewModel
class SubAgentSettingsViewModel @Inject constructor(
    private val settings: SubAgentSettings,
) : ViewModel() {

    /** 当前上限；设置页改动与配置通道改动都走同一份单例，界面跟着刷新。 */
    val maxRunning: StateFlow<Int> = settings.maxRunningFlow

    /** 子代理之间能否互相对话（team / tell）；关闭后 task 工具直接拒绝这两个动作。 */
    val communicationEnabled: StateFlow<Boolean> = settings.communicationEnabledFlow

    /** 写入前由 [SubAgentSettings] 收敛到合法范围，界面不必自己再判一次。 */
    fun setMaxRunning(value: Int) = settings.setMaxRunning(value)

    fun setCommunicationEnabled(enabled: Boolean) = settings.setCommunicationEnabled(enabled)
}
