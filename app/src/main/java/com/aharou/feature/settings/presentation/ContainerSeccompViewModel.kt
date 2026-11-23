package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ProotSeccompMode
import com.aharou.feature.settings.data.repository.ProotSeccompPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「容器 → PRoot 兼容模式」分区状态：模式本身存 DataStore，默认自动。 */
@HiltViewModel
class ContainerSeccompViewModel @Inject constructor(
    private val containerSettings: ContainerSettingsRepository,
    private val prootSeccomp: ProotSeccompPolicy,
) : ViewModel() {

    val mode = containerSettings.prootSeccompModeFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProotSeccompMode.AUTO,
    )

    /**
     * 切模式后必须丢掉已缓存的探测结论：结论按「容器 + 发行版 + 系统指纹」缓存，留着的话
     * 用户切回自动模式仍按上一次探测结果走，看起来像设置没生效。
     *
     * 只清当前容器：结论是「这台机器上这个容器能不能跑默认模式」的事实，与模式选择无关，
     * 别的容器同指纹下的结论仍然有效。
     */
    fun setMode(mode: ProotSeccompMode) {
        viewModelScope.launch {
            containerSettings.setProotSeccompMode(mode)
            val profileId = containerSettings.activeProfileIdFlow.first()
            prootSeccomp.forgetVerdict(profileId)
            containerSettings.clearSeccompVerdicts(profileId)
        }
    }
}
