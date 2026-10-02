package com.aharou.feature.agent.domain.shell

import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主命令实际走的通道。 */
enum class HostShellMode {
    /** root（`su`，uid 0）：能力最大，且不依赖 Shizuku 服务。 */
    ROOT,

    /** Shizuku（adb shell，uid 2000）。 */
    SHIZUKU,

    /** 两条通道都不可用。 */
    UNAVAILABLE
}

/**
 * 宿主命令的统一入口：root 优先、Shizuku 兜底。
 *
 * 有 root 就走 root（不受 Shizuku 重启/绑定超时影响，影子屏 runner 也更不容易被系统回收）；
 * root 不可用时回退 Shizuku。两条都不可用时由 [run] 抛可读异常，由调用方转成工具错误。
 * 各通道的实现细节仍分别留在 [RootShell] 与 [ShizukuManager]，这里只做挑选与结果口径统一。
 */
@Singleton
class HostShellManager @Inject constructor(
    private val rootShell: RootShell,
    private val shizukuManager: ShizukuManager,
) {
    companion object {
        /** 通道挑选：root 可用就用 root，否则 Shizuku 就绪就用它，都没有则不可用。 */
        fun resolve(rootAvailable: Boolean?, shizukuState: ShizukuState): HostShellMode = when {
            rootAvailable == true -> HostShellMode.ROOT
            shizukuState == ShizukuState.READY -> HostShellMode.SHIZUKU
            else -> HostShellMode.UNAVAILABLE
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mode: StateFlow<HostShellMode> =
        combine(rootShell.available, shizukuManager.state) { root, shizuku -> resolve(root, shizuku) }
            .stateIn(scope, SharingStarted.Eagerly, HostShellMode.UNAVAILABLE)

    /** Shizuku 侧的细分状态：通道不可用时调用方据此给出具体原因（未安装 / 未运行 / 未授权）。 */
    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    /** root 探测结论：null = 尚未探测。 */
    val rootAvailable: StateFlow<Boolean?> = rootShell.available

    /** 重新探测 root 并刷新 Shizuku 状态。 */
    suspend fun refresh() {
        rootShell.refresh()
        shizukuManager.refreshState()
    }

    /** 执行命令。[HostShellMode.UNAVAILABLE] 时抛异常。 */
    suspend fun run(command: String, timeoutMs: Long): ShellCommandResult {
        if (rootShell.available.value == null) rootShell.refresh()
        if (rootShell.available.value == true) {
            val result = rootShell.run(command, timeoutMs)
            // su 在、但这一条起不来（如授权被拒）：回退 Shizuku，而不是把失败直接抛出去。
            if (result.exitCode != SHELL_EXIT_START_FAILED) return result
        }
        check(shizukuManager.state.value == ShizukuState.READY) {
            "宿主命令通道不可用（Shizuku: ${shizukuManager.state.value}，root: " +
                "${if (rootShell.available.value == true) "可用" else "不可用"}）：" +
                "请安装并授权 Shizuku，或授予本应用 root 权限"
        }
        val result = shizukuManager.runCommand(command, timeoutMs)
        return ShellCommandResult(result.output, result.exitCode)
    }
}
