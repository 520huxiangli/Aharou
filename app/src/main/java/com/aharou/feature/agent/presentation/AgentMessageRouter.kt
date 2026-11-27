package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.inbox.SharedInbox
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.session.SessionUseCase
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped

/**
 * 代理间消息的投递：主会话 ⇄ 子代理、子代理 ⇄ 兄弟、共享区信箱 → 会话。
 *
 * 这里只负责「把消息组装成通知」，真正入队与起轮由调用方通过 [deliver] 交给
 * `AIAgentViewModel.deliverSystemEvent`——那条路径要碰会话忙闲与队列消费，仍留在 ViewModel。
 *
 * 从 [AIAgentViewModel] 搬出来是因为那个文件顶在行数基线上。
 */
@ViewModelScoped
class AgentMessageRouter @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val sharedInbox: SharedInbox,
) {

    /** 投递一条代理间消息：收件人忙碌时入通知中心，空闲时以一条通知消息触发其新一轮。 */
    suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean,
        fromSibling: Boolean = false,
        deliver: (String, PendingNotification) -> Unit
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent, fromSibling = fromSibling
        )
        deliver(recipientSessionId, item)
    }

    /** 主会话发给运行中/已完成子代理的消息。 */
    suspend fun deliverMessageToSubAgent(
        parentSessionId: String,
        subSessionId: String,
        message: String,
        deliver: (String, PendingNotification) -> Unit
    ) {
        val senderTitle = sessionUseCase.getSessionById(parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = subSessionId,
            senderSessionId = parentSessionId,
            senderTitle = senderTitle,
            message = message,
            fromParent = true,
            deliver = deliver
        )
    }

    /** 子代理发给主会话的消息。 */
    suspend fun deliverMessageToParent(
        parentSessionId: String,
        subSessionId: String,
        message: String,
        fromSibling: Boolean,
        deliver: (String, PendingNotification) -> Unit
    ) {
        val senderTitle = sessionUseCase.getSessionById(subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = parentSessionId,
            senderSessionId = subSessionId,
            senderTitle = senderTitle,
            message = message,
            fromParent = false,
            fromSibling = fromSibling,
            deliver = deliver
        )
    }

    /**
     * 向父会话投递一条子代理结束通知。
     *
     * 失败与被终止都要带上原因，否则父代理只知道「没成」，还得再 read 一次才可能拿到线索。
     */
    fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?,
        deliver: (String, PendingNotification) -> Unit
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliver(parentSessionId, item)
    }

    /** 把某工作区信箱的未读投到它的默认会话（最近更新的那个）；还没有会话时不投，不凭空造空壳会话。 */
    suspend fun deliverInboxToDefaultSession(
        workspacePath: String,
        deliver: (String, PendingNotification) -> Unit
    ) {
        val unread = runCatching { sharedInbox.peek(workspacePath) }.getOrNull().orEmpty()
        if (unread.isEmpty()) return
        val session = runCatching { sessionUseCase.getMostRecentSessionOfWorkspace(workspacePath) }
            .getOrNull() ?: return
        deliverInboxMessages(session.id, workspacePath, deliver)
    }

    /**
     * 把共享区信箱的未读作为一条**代理间通知**投递：与本机子代理消息同一通道，忙碌时搭车进工具结果、
     * 空闲时作为一条通知消息起新一轮——用户看得到，模型也读得到。
     *
     * 先 peek 拿未读，投递成功后再把这批行从信箱删掉（不再维护已读位置指针）。顺序不能反：
     * 先删再投，中途出岔子就把消息丢了。
     */
    suspend fun deliverInboxMessages(
        sessionId: String,
        projectRoot: String,
        deliver: (String, PendingNotification) -> Unit
    ) {
        if (projectRoot.isBlank()) return
        // 会话不在就别清信箱：先把消息留着，等真有会话时再投
        if (sessionUseCase.getSessionById(sessionId) == null) return
        val unread = runCatching { sharedInbox.peek(projectRoot) }.getOrNull().orEmpty()
        if (unread.isEmpty()) return
        deliverAgentMessage(
            recipientSessionId = sessionId,
            senderSessionId = SharedInbox.NOTIFICATION_SENDER_ID,
            senderTitle = "共享区信箱",
            message = unread.joinToString("\n"),
            fromParent = false,
            deliver = deliver
        )
        runCatching { sharedInbox.dropDelivered(projectRoot, unread.size, channel = "session_start", sessionId = sessionId) }
    }
}
