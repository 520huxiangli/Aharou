package com.aharou.feature.agent.domain.container

import com.aharou.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CommandEngine] 的委托层：同时持有本地与远程两套实现，每次方法调用时选实现。
 *
 * 选择依据是**本次调用所属工作区解析出的容器**（[ContainerProfileResolver.forWorkspace]），
 * 而不是全局 mode：配了「工作区 → SSH 容器」覆盖时，那个工作区的命令必须走 [RemoteSshEngine]，
 * 其余工作区继续走 [LinuxContainerEngine]。只看全局 mode 会把 SSH profile 交给本地引擎，
 * 它就去起一个不存在的 rootfs（`/bin/sh not found`）。
 *
 * 没有工作区参数的方法（isContainerInstalled / defaultShell / notReadyHint 等）仍看全局默认容器：
 * 它们是界面初始化门禁，面向「当前选中的容器」。
 */
@Singleton
class DelegatingCommandEngine @Inject constructor(
    private val profileResolver: ContainerProfileResolver,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: RemoteSshEngine
) : CommandEngine {

    /** 按本次执行的工作区选引擎。 */
    private fun delegate(projectPath: String?): CommandEngine =
        if (profileResolver.forWorkspace(projectPath).mode == ExecutionMode.REMOTE_SSH) remoteEngine
        else localEngine

    /** 无工作区上下文时按全局默认容器选引擎。 */
    private fun delegateByDefault(): CommandEngine =
        if (profileResolver.defaultProfile().mode == ExecutionMode.REMOTE_SSH) remoteEngine
        else localEngine

    override val initProgress: StateFlow<ContainerInitState>
        get() = delegateByDefault().initProgress

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = delegate(projectPath).runCommandStream(command, projectPath, timeoutMs)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = delegate(projectPath).runCommandSync(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate(projectPath).runCommandSyncWithExit(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate(projectPath).runCommandSyncUnbounded(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = delegate(projectPath).runCommandSyncIfReady(command, projectPath, timeoutMs)

    override fun isContainerInstalled(): Boolean = delegateByDefault().isContainerInstalled()

    override fun isProvisioned(): Boolean = delegateByDefault().isProvisioned()

    override fun defaultShell(): String = delegateByDefault().defaultShell()

    override fun notReadyHint(): String? = delegateByDefault().notReadyHint()

    override suspend fun ensureInstalled() = delegateByDefault().ensureInstalled()
}
