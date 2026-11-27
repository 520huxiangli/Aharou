package com.aharou.feature.agent.presentation

import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 会话级的「谁在跑」状态：运行中的 job、停止占位、界面状态、活跃会话集合、工具参数快照。
 *
 * 这组状态被会话主循环、停止逻辑、通知投递、排队消费同时读写，之前都长在 [AIAgentViewModel] 里。
 * 抽出来是为了让停止/投递这些块能搬到独立类而不必反向持有 ViewModel —— 它们共享同一份 map，
 * 语义与原来的字段完全一致（ViewModel 侧用 getter 直接指向这里，读写写法不变）。
 *
 * 注意：这里**不加锁也不做线程约束**，与原先一致的用法是只在主线程读写（停止、主循环收尾都在主线程）。
 */
@ViewModelScoped
class SessionRunRegistry @Inject constructor() {

    /** 会话 id → 正在跑的那一轮 job。 */
    val jobs: MutableMap<String, Job> = mutableMapOf()

    /** 会话 id → 停止占位。停止期间新消息据此排队，收尾时 complete 放行等待者。 */
    val stopping: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    /** 界面状态（每个会话一份，供侧栏与输入框判断）。 */
    val agentStates: MutableStateFlow<Map<String, AgentUIState>> = MutableStateFlow(emptyMap())

    /** 有活跃 job 的会话 id 集合。 */
    val activeSessions: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /** 正在执行斜杠命令的会话：命令期间同样算忙，但不注册 job。 */
    val runningCommandSessions: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /** 工具参数流式累积（key = 消息 id），停止时用它补齐被中断工具的调用参数。 */
    val toolArgsByMsgId: MutableMap<String, String> = mutableMapOf()

    /** 已强制停止、不需再向父会话播报的子代理 runId。 */
    val suppressedSubAgentStops: MutableSet<String> = mutableSetOf()
}
