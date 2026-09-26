package com.aharou.feature.agent.domain.container

import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CommandEngine] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [CommandEngine] 在何时被首次注入，
 * 真正执行命令时才读取当前模式。
 */
@Singleton
class DelegatingCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: RemoteSshEngine
) : CommandEngine {

    private fun delegate(): CommandEngine =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remoteEngine
        else localEngine

    override val initProgress: StateFlow<ContainerInitState>
        get() = delegate().initProgress

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = delegate().runCommandStream(command, projectPath, timeoutMs)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = delegate().runCommandSync(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncWithExit(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncUnbounded(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = delegate().runCommandSyncIfReady(command, projectPath, timeoutMs)

    override fun isContainerInstalled(): Boolean = delegate().isContainerInstalled()

    override fun isProvisioned(): Boolean = delegate().isProvisioned()

    override fun defaultShell(): String = delegate().defaultShell()

    override fun notReadyHint(): String? = delegate().notReadyHint()

    override suspend fun ensureInstalled() = delegate().ensureInstalled()
}
