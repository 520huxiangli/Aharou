package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 宿主执行后端（root / Shizuku）的设置页状态与操作入口。 */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
    private val hostShell: HostShellManager,
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /** 实际执行的通道：root / Shizuku / 不可用。 */
    val mode: StateFlow<HostShellMode> = hostShell.mode

    /** root 探测结论；null = 尚未探测。 */
    val rootAvailable: StateFlow<Boolean?> = hostShell.rootAvailable

    /** 重新探测 root 与 Shizuku 状态（如从 Shizuku 应用返回、或刚授权 root 后）。 */
    fun refresh() {
        viewModelScope.launch { runCatching { hostShell.refresh() } }
    }

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
