package com.aharou.feature.agent.domain.inbox

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信箱来信的主动投递：不再等用户切到目标工作区，写入工具与 [SharedInboxWatcher] 发出信号后
 * 立刻扫一遍所有工作区的未读，把有消息的工作区交给 [bind] 进来的投递方。
 *
 * 投递方是 `AIAgentViewModel`——投递要落到具体会话并可能起一轮，只有它握有这些能力；
 * 它创建时把「投到默认会话」注册进来。没有注册方时（App 刚启动、没开过聊天页）什么都不投，
 * 消息留在信箱里等下一次轮首。
 */
@Singleton
class SharedInboxDelivery @Inject constructor(
    private val sharedInbox: SharedInbox,
    private val workspaceRepository: WorkspaceRepository,
    private val watcher: SharedInboxWatcher,
    events: SharedInboxEvents
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var handler: (suspend (String) -> Unit)? = null

    init {
        scope.launch {
            watcher.start()
            events.changed.collect { dispatch() }
        }
    }

    fun bind(handler: suspend (String) -> Unit) {
        this.handler = handler
    }

    /**
     * 串行扫描：同一次写入可能同时触发「工具通知」与「目录监听」，两次扫描落到同一条未读上，
     * 前一次投递完成后会把这几行清掉，后一次自然读到空。
     */
    private suspend fun dispatch() = mutex.withLock {
        val deliver = handler ?: return@withLock
        workspaceRepository.workspaces.value.forEach { workspace ->
            val path = workspace.path
            if (path.isBlank()) return@forEach
            val unread = runCatching { sharedInbox.peek(path) }.getOrDefault(emptyList())
            if (unread.isEmpty()) return@forEach
            runCatching { deliver(path) }
                .onFailure { FileLogger.w(TAG, "投递信箱消息失败: $path", it) }
        }
    }

    private companion object {
        const val TAG = "SharedInboxDelivery"
    }
}
