/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt 的 aichat 分支
 * 与它的 AIChatScreen(embedded = true) 用法，LGPL-3.0）。本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：
 * - Operit 直接内嵌整个聊天界面（工具栏、抽屉、模式切换、附件…）。Aharou 面板里只做「只读会话
 *   视图 + 一个输入框」：消息复用聊天页的气泡组件，发送复用聊天页那条请求队列，工具授权、模式与
 *   队列语义都留在聊天页 ViewModel，不在面板里另起一套。
 * - 会话与消息通过 Hilt EntryPoint 直接读库（面板是 Composable，拿不到聊天页 ViewModel）；
 *   缺省会话按「当前工作区最近更新的根会话」取，与聊天页「打开最近会话」同一口径。
 */

package com.aharou.feature.plugin.dsl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.session.AgentChatRequest
import com.aharou.feature.agent.domain.session.AgentChatRequestBus
import com.aharou.feature.agent.domain.session.AgentRunStateStore
import com.aharou.feature.agent.presentation.component.AgentMessageItem
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 只渲染末段消息：面板里的会话视图是摘要，不是完整聊天页，长会话不必整段进内存。 */
private const val MAX_RENDERED_MESSAGES = 120

private const val AI_CHAT_TAG = "ComposeDslAiChat"

/** Composable 拿不到聊天页 ViewModel，只能走 EntryPoint 取单例。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ComposeDslChatEntryPoint {
    fun agentMessageDao(): AgentMessageDao
    fun chatSessionDao(): ChatSessionDao
    fun workspaceRepository(): WorkspaceRepository
    fun chatRequestBus(): AgentChatRequestBus
    fun runStateStore(): AgentRunStateStore
}

@Composable
internal fun renderAiChatNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    val context = LocalContext.current.applicationContext
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context, ComposeDslChatEntryPoint::class.java)
    }
    ComposeDslAiChatBlock(
        entryPoint = entryPoint,
        requestedChatId = props.stringOrNull("chatId"),
        placeholder = props.stringOrNull("placeholder")
            ?: stringResource(R.string.chat_input_placeholder),
        maxHeight = props.dp("maxHeight", 420.dp),
        onSendActionId = ComposeDslParser.extractActionId(props["onSend"]),
        onAction = onAction,
        modifier = modifier
    )
}

@Composable
private fun ComposeDslAiChatBlock(
    entryPoint: ComposeDslChatEntryPoint,
    requestedChatId: String?,
    placeholder: String,
    maxHeight: Dp,
    onSendActionId: String?,
    onAction: (String, Any?) -> Unit,
    modifier: Modifier
) {
    val workspace by entryPoint.workspaceRepository().current.collectAsState()
    val sessionFlow: Flow<ChatSessionEntity?> = remember(requestedChatId, workspace?.path) {
        val dao = entryPoint.chatSessionDao()
        val workspacePath = workspace?.path
        when {
            requestedChatId != null -> dao.getByIdFlow(requestedChatId)
            workspacePath != null -> dao.getRootSessionsByWorkspace(workspacePath)
                .map { sessions -> sessions.maxByOrNull { it.updatedAt } }
            else -> flowOf(null)
        }
    }
    val session by sessionFlow.collectAsState(initial = null)
    val sessionId = session?.id
    val messagesFlow = remember(sessionId) {
        sessionId?.let { entryPoint.agentMessageDao().getMessagesBySessionPaged(it, MAX_RENDERED_MESSAGES) }
            ?: flowOf(emptyList())
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val runStates by entryPoint.runStateStore().states.collectAsState()
    val running = sessionId?.let { runStates[it]?.running } == true
    var draft by remember(sessionId) { mutableStateOf("") }
    var failure by remember(sessionId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visibleMessages = remember(messages) {
        messages.filterNot { it.isCompactionMarker }
    }

    // 只在「本来就在底部」时跟随新消息；用户上翻看历史时不要把他拽回底部。
    LaunchedEffect(
        sessionId,
        visibleMessages.size,
        visibleMessages.lastOrNull()?.id,
        visibleMessages.lastOrNull()?.content?.length
    ) {
        if (visibleMessages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val wasAtBottom = lastVisible == null || lastVisible >= visibleMessages.lastIndex - 1
        if (wasAtBottom) {
            listState.scrollToItem(visibleMessages.lastIndex)
        }
    }

    fun send() {
        val target = session ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        // 发送永远走聊天页那条队列；onSend 只是把结果回传给插件（format 同 ui.dispatch），
        // 不接管发送，避免插件再发一次导致同一句话重复入队。
        val accepted = entryPoint.chatRequestBus().submit(
            AgentChatRequest.Send(target.id, text, target.workspacePath)
        )
        if (accepted) {
            draft = ""
            failure = null
        } else {
            // 聊天页不在前台时队列没有消费者，消息不落库也不静默丢弃。
            failure = "CHAT_UI_UNAVAILABLE"
            FileLogger.w(AI_CHAT_TAG, "会话面板发消息被拒: ${target.id}")
        }
        onSendActionId?.let { actionId ->
            onAction(
                actionId,
                mapOf(
                    "chatId" to target.id,
                    "text" to text,
                    "accepted" to accepted,
                    "reason" to failure
                )
            )
        }
    }

    // 用固定高度的消息区而不是 weight：面板节点可能嵌在 LazyColumn 这类高度无界的容器里，
    // weight 在无界约束下会把内容压成 0。高度由 props 的 maxHeight 决定，与父容器无关。
    val listHeight = (maxHeight - 108.dp).coerceAtLeast(120.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(listHeight)) {
            if (session == null) {
                Text(
                    text = stringResource(R.string.chat_no_sessions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (visibleMessages.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(visibleMessages, key = { it.id }) { entity ->
                        AgentMessageItem(
                            message = entity.toUIMessage(),
                            showActions = false
                        )
                    }
                }
            }
            val atBottom by remember(visibleMessages.size) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    last == null || last >= visibleMessages.lastIndex
                }
            }
            if (!atBottom) {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (visibleMessages.isNotEmpty()) {
                                listState.scrollToItem(visibleMessages.lastIndex)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.common_scroll_to_bottom),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        failure?.let { reason ->
            Text(
                text = stringResource(R.string.plugin_event_failed, reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { send() },
                enabled = draft.isNotBlank() && session != null
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send)
                )
            }
        }
    }
}
