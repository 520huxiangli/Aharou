package com.aharou.feature.agent.domain.schedule

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一条到点的定时任务请求：由 Worker 发出，交给会话层去跑。
 *
 * Worker 跑在应用进程里但拿不到 ViewModel，所以中间隔一层总线——和子代理事件同一套思路，
 * 让「调度」与「执行」解耦，Worker 只负责认领与派发。
 *
 * @param targetSessionId 目标会话；null 表示按 [workspacePath] 新建一个会话再跑。
 * @param workspacePath 目标会话的工作区；[targetSessionId] 非空时以会话自身的为准。
 */
data class ScheduledRunRequest(
    val taskId: String,
    val taskName: String,
    val prompt: String,
    val targetSessionId: String?,
    val workspacePath: String?
)

@Singleton
class ScheduledRunBus @Inject constructor() {

    private val _requests = MutableSharedFlow<ScheduledRunRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ScheduledRunRequest> = _requests.asSharedFlow()

    /** 非挂起投递：Worker 不需要等消费方就绪，缓冲满时丢弃并返回值 false。 */
    fun emit(request: ScheduledRunRequest): Boolean = _requests.tryEmit(request)
}
