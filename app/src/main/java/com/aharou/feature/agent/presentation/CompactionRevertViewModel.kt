package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.workflow.CompactionOperationStore
import com.aharou.feature.agent.domain.workflow.CompactionRevertException
import com.aharou.feature.agent.domain.workflow.CompactionRevertFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 撤销一次上下文压缩的结果，供界面决定提示文案。 */
sealed interface CompactionRevertEvent {
    data object Reverted : CompactionRevertEvent

    /** [failure] 为 null 说明是没归类的异常（如数据库报错），界面按通用失败提示。 */
    data class Failed(val failure: CompactionRevertFailure?) : CompactionRevertEvent
}

/**
 * 聊天里「撤销这次压缩」的状态与操作入口。
 *
 * 单独一个 ViewModel 而不是塞进 AIAgentViewModel：后者已经贴着行数棘轮上限，且撤销只需要
 * 「最近一条可撤销记录」这一小块状态，与对话流、工具循环没有耦合。
 *
 * 只对**最近一次**压缩（[CompactionOperationStore.latestRevertible]）暴露入口：恢复更早的摘要会让
 * 中间那段历史重新落回摘要里，语义对不上。
 */
@HiltViewModel
class CompactionRevertViewModel @Inject constructor(
    private val store: CompactionOperationStore,
) : ViewModel() {

    /** 可撤销压缩对应的摘要消息 id：界面上只有这一条摘要卡片带撤销入口。 */
    private val _revertibleSummaryId = MutableStateFlow<String?>(null)
    val revertibleSummaryId: StateFlow<String?> = _revertibleSummaryId.asStateFlow()

    // 缓存操作 id 而不是点击时再查库：查询与点击之间若又压了一次，重新查回来的会是另一条记录。
    private var operationId: String? = null

    private var sessionId: String? = null

    private val _events = MutableSharedFlow<CompactionRevertEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CompactionRevertEvent> = _events.asSharedFlow()

    /** 重新问一次「本会话还有没有可撤销的压缩」；会话切换、新压一次、撤销结束后都要调。 */
    fun refresh(sessionId: String?) {
        this.sessionId = sessionId
        viewModelScope.launch {
            val operation = sessionId?.let { store.latestRevertible(it) }
            operationId = operation?.id
            _revertibleSummaryId.value = operation?.summaryMessageId
        }
    }

    /** 撤销最近一次压缩。历史由 [CompactionOperationStore] 自己失效，界面跟着 Room 的流刷新。 */
    fun revert() {
        val id = operationId ?: return
        // 连点保护：置空后第二次点击直接落到上面的 return，不会重复提交同一条记录。
        operationId = null
        viewModelScope.launch {
            val result = store.revertCompaction(id)
            _revertibleSummaryId.value = null
            if (result.isFailure) {
                // 失败多半是记录已不可撤销（被更新的压缩取代、已撤过），也可能只是临时错误；
                // 重新查一次让入口如实回到「有/没有」，而不是继续挂着一条点了没用的按钮。
                refresh(sessionId)
            }
            val failure = result.exceptionOrNull()?.let { (it as? CompactionRevertException)?.failure }
            _events.emit(
                if (result.isSuccess) CompactionRevertEvent.Reverted
                else CompactionRevertEvent.Failed(failure)
            )
        }
    }
}
