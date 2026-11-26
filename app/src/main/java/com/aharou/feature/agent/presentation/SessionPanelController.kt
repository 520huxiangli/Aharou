package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.TodoItemDao
import com.aharou.feature.agent.domain.model.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话级面板的数据流组装（待办）。
 *
 * 单独成类是为了把「跟着会话切换重订阅」这段样板从 [AIAgentViewModel] 里挪走——
 * 那个文件有行数棘轮，只许降不许升。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionPanelController @Inject constructor(
    private val todoItemDao: TodoItemDao
) {
    fun todoItems(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<List<TodoItem>> = sessionIds
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
