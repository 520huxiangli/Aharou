package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「容器 → Gradle 堆上限」分区状态。[recommendedMb] 按设备总内存算出，仅用于提示。 */
data class ContainerGradleHeapUiState(
    val heapMb: Int,
    val deviceMemMb: Int,
    val recommendedMb: Int,
)

@HiltViewModel
class ContainerGradleHeapViewModel @Inject constructor(
    private val containerSettings: ContainerSettingsRepository,
    @param:ApplicationContext context: Context,
) : ViewModel() {

    private val deviceMemMb = ContainerSettingsRepository.deviceTotalMemMb(context)
    private val recommendedMb = ContainerSettingsRepository.recommendedGradleHeapMb(deviceMemMb)

    val state = containerSettings.gradleHeapMbFlow
        .map { ContainerGradleHeapUiState(it, deviceMemMb, recommendedMb) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ContainerGradleHeapUiState(recommendedMb, deviceMemMb, recommendedMb),
        )

    fun setHeapMb(mb: Int) {
        viewModelScope.launch { containerSettings.setGradleHeapMb(mb) }
    }
}
