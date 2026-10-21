package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.StewardController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 智能托管与会话链路之间的桥：把「一轮 AI 结束」翻译成一次托管判定，需要时再把下一步发回同一会话。
 *
 * 单独成类而不是写在 [AIAgentViewModel] 里：那个文件已经顶在架构门禁的行数上限，而这一层只是转接
 * （取最后一条助手消息 → 交判定 → 回调发送），留在 ViewModel 里只会让它继续膨胀。
 */
@Singleton
class StewardBridge @Inject constructor(
    private val stewardController: StewardController,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) {

    /**
     * 一轮结束即 AI 空闲：旁路问一次主管模型任务做完没有，没做完就把下一步发进同一会话。
     *
     * 判定走独立协程、失败只记日志，不影响本轮收尾；续发与本轮 job 天然串行，不会重入。
     *
     * @param send 把托管生成的下一步当作一条新用户消息发进该会话
     */
    fun onTurnFinished(
        scope: CoroutineScope,
        sessionId: String,
        send: (String) -> Unit,
    ) {
        if (!stewardController.enabled) return
        scope.launch {
            val lastReport = messagePersistenceUseCase.getMessagesOnce(sessionId)
                .lastOrNull { it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank() }
                ?.content.orEmpty()
            stewardController.onAiIdle(lastReport, send)
        }
    }
}
