package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.agent.domain.prompt.InjectedContextStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 把 [InjectedContextStore] 的会话快照暴露给聊天界面。
 *
 * 单独成 ViewModel：数据只被「显示全部」一个按钮用到，不必挤进已经很庞大的 [AIAgentViewModel]。
 */
@HiltViewModel
class InjectedContextViewModel @Inject constructor(
    store: InjectedContextStore,
) : ViewModel() {

    /** 会话 id → 本轮注入正文。 */
    val bySession: StateFlow<Map<String, String>> = store.bySession
}
