package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Shizuku 执行后端的设置页状态与操作入口。 */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /** 重新探测状态（如从 Shizuku 应用返回后）。 */
    fun refresh() = shizukuManager.refreshState()

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
