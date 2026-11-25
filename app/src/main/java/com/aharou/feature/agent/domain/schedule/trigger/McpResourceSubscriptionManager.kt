package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.mcp.McpClient
import com.aharou.feature.agent.domain.mcp.McpManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 资源订阅的登记处：把「哪些任务订阅了哪条 server 的哪个资源」落到 MCP 客户端上。
 *
 * 一条 client 只能挂一个资源变更回调（[McpClient.setResourceUpdatedListener] 是整体替换），
 * 所以同一 server 下的多个订阅在这里聚合成一个回调，收到 uri 后再交给事件总线按
 * 「server + uri」匹配任务，不在这里判断该跑哪个任务。
 *
 * 订阅只在两处发起：用户建了 mcp_resource 任务、或任务被改动。client 自己不主动订阅任何东西——
 * 这条边界在 [McpClient.subscribeResource] 的注释里也写着，改动时别破坏它。
 *
 * 已知限制：MCP server 是按「当前活动工作区」连接的，任务没写工作区或 server 不在活动工作区里时
 * 这里拿不到 client，只能记日志跳过；等下次任务改动或 App 重启时再对齐。MCP 重连后订阅会失效，
 * 这里靠「client 实例变了就重订」兜底，但重连本身不会主动触发这里的方法。
 */
@Singleton
class McpResourceSubscriptionManager @Inject constructor(
    private val dao: ScheduledTaskDao,
    private val mcpManager: McpManager,
    private val eventBus: EventTriggerBus,
) {

    private companion object {
        const val TAG = "McpResourceSub"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** 某条 server 当前用哪个 client 订了哪些 uri。换 client 说明连接重建过，要全部重订。 */
    private class Subscribed(val client: McpClient, val uris: MutableSet<String>)

    private val subscribed = mutableMapOf<String, Subscribed>()

    /** 把订阅对齐到当前任务表；幂等，重复调用不会重复发订阅请求。 */
    suspend fun syncAll() = mutex.withLock {
        val tasks = runCatching { dao.enabledByTriggerType(ScheduledTaskEntity.TYPE_MCP_RESOURCE) }
            .getOrElse { e ->
                FileLogger.e(TAG, "读取 MCP 资源触发任务失败", e)
                return@withLock
            }
        val wanted = tasks
            .filter { it.triggerMcpServer.isNotBlank() && it.triggerMcpResourceUri.isNotBlank() }
            .groupBy { it.triggerMcpServer }

        // 整条 server 都不要了：撤订阅并摘回调。
        (subscribed.keys - wanted.keys).forEach { server ->
            val entry = subscribed.remove(server) ?: return@forEach
            entry.uris.forEach { uri ->
                runCatching { entry.client.unsubscribeResource(uri) }
                    .onFailure { FileLogger.w(TAG, "取消资源订阅失败: $server $uri", it) }
            }
            entry.client.setResourceUpdatedListener(null)
        }

        wanted.forEach { (server, serverTasks) ->
            val workspace = serverTasks.firstNotNullOfOrNull { it.workspacePath }
            val client = runCatching {
                if (workspace != null) mcpManager.clientFor(server, workspace) else mcpManager.clientFor(server)
            }.getOrNull()
            if (client == null) {
                FileLogger.w(TAG, "MCP server 未连接，本次跳过资源订阅：$server")
                return@forEach
            }

            val existing = subscribed[server]
            if (existing == null || existing.client !== client) {
                // 连接重建过：旧 client 上的订阅随会话失效，这里整组重订。
                val fresh = Subscribed(client, mutableSetOf())
                client.setResourceUpdatedListener { uri -> onResourceUpdated(server, uri) }
                subscribed[server] = fresh
            }
            val entry = subscribed.getValue(server)
            val uris = serverTasks.map { it.triggerMcpResourceUri }.toSet()

            (entry.uris - uris).toList().forEach { uri ->
                runCatching { client.unsubscribeResource(uri) }
                    .onFailure { FileLogger.w(TAG, "取消资源订阅失败: $server $uri", it) }
                entry.uris.remove(uri)
            }
            uris.forEach { uri ->
                if (uri in entry.uris) return@forEach
                runCatching { client.subscribeResource(uri) }
                    .onSuccess {
                        entry.uris.add(uri)
                        FileLogger.i(TAG, "已订阅资源：$server $uri")
                    }
                    .onFailure { FileLogger.w(TAG, "订阅资源失败: $server $uri", it) }
            }
        }
    }

    /**
     * client 的回调跑在传输读线程上，这里只把工作丢进协程就返回。
     * 传出去的东西只有 server 名与 uri 两个我们自己配置过的值，notification 的其它字段一概不碰。
     */
    private fun onResourceUpdated(server: String, uri: String) {
        scope.launch { eventBus.postMcpResource(server, uri) }
    }
}
