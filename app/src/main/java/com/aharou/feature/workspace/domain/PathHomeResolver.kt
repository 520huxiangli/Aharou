package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前执行环境（本地 PRoot 容器 / 远程 SSH 服务器）家目录的统一来源，供各工具展开 `~`。
 *
 * 各环境真实 home 的获取方式不同：本地容器就绪后查 `$HOME` 缓存（[containerHome]，由
 * [com.aharou.feature.agent.domain.container.LinuxContainerEngine] 写入）；远程连接成功后查
 * 远程 `$HOME` 缓存（[RemoteSshConnection.remoteHome]）。本类屏蔽差异，统一提供 [home] 与
 * [expandHome]，避免各工具各自硬编码（如 `/root`）或重复判断执行模式。
 */
@Singleton
class PathHomeResolver @Inject constructor(
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** 本地容器内真实 home（容器就绪后查 $HOME 缓存），未就绪为 null。 */
    @Volatile
    var containerHome: String? = null

    /** 当前环境的 home：远程取远程用户 home（连接后缓存），本地取容器 home；未知时回退 /root。 */
    fun home(): String = when {
        executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH ->
            remoteSshConnection.remoteHome ?: "/root"
        else -> containerHome ?: "/root"
    }

    /**
     * 当前执行环境下的 AI 配置根目录（`<home>/.aharou`）：本地容器为 `/root/.aharou`，
     * 远程为服务器用户 home 下的 `.aharou`。供需要执行/回显该目录内文件的代码统一取用，
     * 避免写死 `/root`（远程非 root 用户会指错）。
     */
    fun aharouRoot(): String = home().trimEnd('/') + "/.aharou"

    /** 展开 `~` 或 `~/` 前缀为当前环境的 home 路径；其它路径原样返回。 */
    fun expandHome(path: String): String {
        val h = home()
        return when {
            path == "~" -> h
            path.startsWith("~/") -> h.trimEnd('/') + path.removePrefix("~")
            else -> path
        }
    }
}
