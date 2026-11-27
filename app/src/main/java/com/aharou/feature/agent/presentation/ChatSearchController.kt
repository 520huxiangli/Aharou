package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * 聊天记录的全局搜索（限当前工作区）：关键词、结果流，以及「点了命中后要滚到哪条消息」。
 *
 * 从 [AIAgentViewModel] 搬出来是因为那个文件顶在行数基线上（零余量）。真正切会话、抬分页上限的
 * 动作仍留在 ViewModel，这里只管搜索数据本身。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@ViewModelScoped
class ChatSearchController @Inject constructor(
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) {

    private companion object {
        const val SEARCH_LIMIT = 50
        const val DEBOUNCE_MS = 300L
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScroll = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScroll: StateFlow<Pair<String, String>?> = _pendingScroll.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    fun state(workspace: Flow<String>, scope: CoroutineScope): StateFlow<ChatSearchState> =
        combine(workspace, _query) { ws, q -> ws to q }
            .debounce(DEBOUNCE_MS)
            .flatMapLatest { (ws, query) ->
                val keyword = query.trim()
                if (ws.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            messagePersistenceUseCase.searchInWorkspace(ws, keyword, SEARCH_LIMIT).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = m.content,
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, ChatSearchState())

    fun updateQuery(query: String) {
        _query.value = query
    }

    fun clear() {
        _query.value = ""
        _pendingScroll.value = null
    }

    /** 登记一条待定位消息：聊天页滚动到位后由 [consumePendingScroll] 清掉。 */
    fun markPendingScroll(sessionId: String, messageId: String) {
        _pendingScroll.value = sessionId to messageId
    }

    fun consumePendingScroll() {
        _pendingScroll.value = null
    }
}
