package com.aharou.feature.agent.presentation

import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 按会话维护的两份排队状态：用户排队等待发送的消息（[QueuedRequest]），
 * 以及供应商并发受限时的「前面还有几个」提示（[RequestQueueState]）。
 *
 * 从 [AIAgentViewModel] 搬出来是因为那个文件顶在行数基线上（零余量）。判忙、起跑等流程仍留在
 * ViewModel（它们要碰 sessionJobs 等运行态），这里只负责这两份数据本身。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class SessionQueueController @Inject constructor() {

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    private val _requestQueueStates = MutableStateFlow<Map<String, RequestQueueState?>>(emptyMap())

    /** 当前会话的排队消息列表。 */
    fun queuedRequests(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<List<QueuedRequest>> =
        sessionIds
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList())
                else _queuedRequests.map { it[id] ?: emptyList() }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前会话等待供应商并发名额的状态；取得名额、本轮结束或被打断时清空。 */
    fun requestQueueState(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<RequestQueueState?> =
        sessionIds
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else _requestQueueStates.map { it[id] }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun setRequestQueueState(sessionId: String, state: RequestQueueState?) {
        _requestQueueStates.value =
            if (state == null) _requestQueueStates.value - sessionId else _requestQueueStates.value + (sessionId to state)
    }

    fun enqueue(sessionId: String, request: QueuedRequest) {
        val currentList = _queuedRequests.value[sessionId] ?: emptyList()
        _queuedRequests.value = _queuedRequests.value + (sessionId to (currentList + request))
    }

    fun remove(sessionId: String, id: String) {
        val queue = _queuedRequests.value[sessionId] ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.filterNot { it.id == id })
    }

    /** 调整队列顺序（队列面板拖拽排序）；下标越界或原地不动时什么都不做。 */
    fun move(sessionId: String, fromIndex: Int, toIndex: Int) {
        val queue = _queuedRequests.value[sessionId] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sessionId to reordered)
    }

    /** 编辑队列条目的文本；空文本视为取消编辑，不改动队列。 */
    fun update(sessionId: String, id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val queue = _queuedRequests.value[sessionId] ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /** 取出并移除指定条目（插话用）；不在队列里时返回 null。 */
    fun takeOut(sessionId: String, id: String): QueuedRequest? {
        val queue = _queuedRequests.value[sessionId] ?: return null
        val req = queue.firstOrNull { it.id == id } ?: return null
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.filterNot { it.id == id })
        return req
    }

    /** 队首条目；队列为空时返回 null。不改变队列。 */
    fun peekFirst(sessionId: String): QueuedRequest? =
        _queuedRequests.value[sessionId]?.firstOrNull()

    /** 消费掉队首条目；调用方取出后自行起下一轮。 */
    fun dropFirst(sessionId: String) {
        val queue = _queuedRequests.value[sessionId] ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
    }

    fun clearAll() {
        _queuedRequests.value = emptyMap()
    }

    fun clear(sessionId: String) {
        _queuedRequests.value = _queuedRequests.value - sessionId
    }

    /**
     * 用编辑后的文本重建 modelRequest：无附件注入时与 request 同步；有附件注入时
     * （拼成 `request + "\n\n" + 附件清单`）只替换文本前缀、保留附件部分。
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }
}
