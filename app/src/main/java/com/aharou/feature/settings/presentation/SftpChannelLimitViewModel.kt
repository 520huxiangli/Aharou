package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「远程连接 → SFTP 通道上限」的单独 ViewModel。
 *
 * 不复用 [SettingsViewModel]：那个类已经塞满全局偏好，新设置放独立小组件里自成一体，
 * 与 [ContainerGradleHeapViewModel] 同样处理。
 */
@HiltViewModel
class SftpChannelLimitViewModel @Inject constructor(
    private val containerSettings: ContainerSettingsRepository,
) : ViewModel() {

    val limit = containerSettings.sftpChannelLimitFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ContainerSettingsRepository.DEFAULT_SFTP_CHANNEL_LIMIT,
    )

    fun setLimit(limit: Int) {
        viewModelScope.launch { containerSettings.setSftpChannelLimit(limit) }
    }
}
