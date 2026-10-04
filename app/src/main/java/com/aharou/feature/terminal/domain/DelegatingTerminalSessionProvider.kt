package com.aharou.feature.terminal.domain

import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TerminalSessionProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [TerminalSessionProvider] 在何时被首次注入，
 * 真正使用终端时才读取当前模式。
 */
@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val local: TerminalSessionManager,
    private val remote: RemoteTerminalSessionManager
) : TerminalSessionProvider {

    private fun delegate(): TerminalSessionProvider =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remote else local

    override val tabFinishedEvents: SharedFlow<TabFinishedEvent>
        get() = delegate().tabFinishedEvents

    /**
     * 本地与远程两侧都算上：后台命令不该因为用户切了执行模式就从桌宠上消失。
     * 本流是冷的，组合本身不启动任何协程，由订阅方驱动。
     */
    override val runningBackgroundTabs: Flow<List<TabInfo>> =
        combine(local.runningBackgroundTabs, remote.runningBackgroundTabs) { localTabs, remoteTabs ->
            localTabs + remoteTabs
        }.distinctUntilChanged()

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?,
        workspacePath: String?
    ): String = delegate().startBackgroundCommand(command, title, notify, sourceSessionId, workspacePath)

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegate().sendInput(id, input, appendNewline)

    override fun writeToTab(id: String, text: String): Boolean =
        delegate().writeToTab(id, text)

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegate().writeBytesToTab(id, *bytes)

    override fun getTabOutput(id: String): String? = delegate().getTabOutput(id)

    override fun listTabs(): List<TabInfo> = delegate().listTabs()

    override fun closeTab(id: String): Boolean = delegate().closeTab(id)
}
