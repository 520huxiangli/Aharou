package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * 隐身会话（Ghost mode）的进程内运行时状态：判定哪些会话处于隐身，并持有这些会话的内存消息缓冲。
 *
 * 做成全局 object 而非常规 @Singleton，是为了让 [MessagePersistenceUseCase]、[SessionUseCase]、
 * [com.aharou.feature.agent.domain.workflow.ContextCompactor] 这些被单测直接 new 出来的类不必改构造签名
 * （同 [com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus] 的既有做法）。数据库句柄由调用方
 * 逐次传入，避免全局持有 DAO。
 *
 * 隐身消息只在内存存活：不落库、不归档，进程被杀即全部消失；关闭开关、切走会话或删除会话时也主动清空。
 */
object GhostModeStore {

    /** 已确认处于隐身模式的会话 id（内存缓存，避免每条消息落库前都回查数据库）。 */
    private val ghostIds = ConcurrentHashMap.newKeySet<String>()

    /** 各隐身会话的内存消息缓冲：sessionId -> 消息列表（按时间递增）。 */
    private val buffers = ConcurrentHashMap<String, MutableList<AgentMessageEntity>>()

    /** 缓冲变更版本号：驱动 [bufferFlow] 重新发射，让界面及时看到刚产生的隐身消息。 */
    private val revision = MutableStateFlow(0L)

    /** 会话是否处于隐身模式；内存未命中时回查数据库，查到隐身即缓存。 */
    suspend fun isGhost(sessionDao: ChatSessionDao, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        if (sessionId in ghostIds) return true
        val flag = sessionDao.getById(sessionId)?.ghostMode == true
        if (flag) ghostIds.add(sessionId)
        return flag
    }

    /** 持久化隐身开关并同步缓存；关闭时立即丢弃该会话的内存消息（符合「关掉后不留痕」）。 */
    suspend fun setGhost(sessionDao: ChatSessionDao, sessionId: String, enabled: Boolean) {
        sessionDao.updateGhostMode(sessionId, enabled)
        if (enabled) {
            ghostIds.add(sessionId)
        } else {
            ghostIds.remove(sessionId)
            clearBuffer(sessionId)
        }
    }

    /** 会话被删除或不再需要跟踪时调用。 */
    fun forget(sessionId: String) {
        ghostIds.remove(sessionId)
        clearBuffer(sessionId)
    }

    /**
     * 缓存一条隐身消息（不落库、不归档）。**同 id 覆盖而非追加**——与数据库主键 REPLACE 语义对齐：
     * 同一次工具调用的「执行中」占位与最终结果共用同一个 id（见 AgentTurnRunner 的 `tool_${event.id}`），
     * 追加会在缓冲里留下两条同 id 消息，界面 LazyColumn 拿到重复 key 直接抛异常。
     */
    fun appendGhost(sessionId: String, entity: AgentMessageEntity) {
        val list = buffers.computeIfAbsent(sessionId) {
            java.util.Collections.synchronizedList(ArrayList<AgentMessageEntity>())
        }
        synchronized(list) {
            val existing = list.indexOfFirst { it.id == entity.id }
            if (existing >= 0) list[existing] = entity else list.add(entity)
            val overflow = list.size - MAX_BUFFERED_MESSAGES
            if (overflow > 0) list.subList(0, overflow).clear()
        }
        revision.value++
    }

    /** 该会话当前驻留内存的隐身消息快照（按时间递增）。 */
    fun ghostMessages(sessionId: String): List<AgentMessageEntity> = snapshot(sessionId)

    /** 隐身消息缓冲的实时流：任何会话缓冲变更都会重新发射（同值去重）。 */
    fun bufferFlow(sessionId: String): Flow<List<AgentMessageEntity>> =
        revision.map { snapshot(sessionId) }.distinctUntilChanged()

    /** 丢弃某会话的内存消息。 */
    fun clearBuffer(sessionId: String) {
        if (buffers.remove(sessionId) != null) revision.value++
    }

    /**
     * 切到 [activeSessionId] 时丢弃其它会话的缓冲：隐身消息仅属于它所在的那次会话，离开即消失。
     * 对标 Claude 的 Ghost mode——离开这个隐身会话再回来看到的就是空的。
     */
    fun dropOtherBuffers(activeSessionId: String) {
        if (buffers.keys.removeIf { it != activeSessionId }) revision.value++
    }

    private fun snapshot(sessionId: String): List<AgentMessageEntity> =
        buffers[sessionId]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /** 单个隐身会话在内存里保留的消息条数上限，防止超长会话把选项撑爆内存。 */
    private const val MAX_BUFFERED_MESSAGES = 400
}
