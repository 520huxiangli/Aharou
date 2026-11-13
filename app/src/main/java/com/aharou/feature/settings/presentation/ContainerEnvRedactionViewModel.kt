package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.agent.domain.tool.container.ContainerEnvRedactionSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 容器页「命令输出隐藏环境变量值」开关的状态与写入入口。
 *
 * 状态源直接复用 [ContainerEnvRedactionSetting] 的 SharedPreferences 内存态：命令输出路径每次都要
 * 同步读它，界面与执行侧共用的必须是同一份，改完立刻生效、不必等落盘。
 */
@HiltViewModel
class ContainerEnvRedactionViewModel @Inject constructor(
    private val setting: ContainerEnvRedactionSetting,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = setting.enabledFlow

    fun setEnabled(enabled: Boolean) = setting.setEnabled(enabled)
}
