package com.aharou.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aharou.desktop.ApprovalGate
import com.aharou.desktop.DesktopContainer
import com.aharou.desktop.DesktopSettings
import com.aharou.desktop.DesktopStorage
import com.aharou.desktop.TurnEvent
import com.aharou.desktop.runAgentTurn
import com.aharou.desktop.tool.DesktopToolbox
import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ChatEntry(val role: String, val text: String, val note: String = "")

private val SYSTEM_PROMPT = """
    你是 Aharou 桌面端，一个跑在用户电脑上的 AI 工作台。
    你可以用工具直接读写工作区文件、执行 shell 命令；所有路径都相对工作区根目录。
    回答简洁直接，用中文。
""".trimIndent()

@Composable
fun ChatPanel(settings: DesktopSettings, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val entries = remember { mutableStateListOf<ChatEntry>() }
    // 普通 MutableList：只在后台协程里被工具循环改写，不需要驱动重组
    val history = remember { mutableListOf<AgentMessage>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    val gate = remember { ApprovalGate() }
    val scope = rememberCoroutineScope()

    val workspaceRoot = remember(settings.workspacePath) {
        File(settings.workspacePath.ifBlank { System.getProperty("user.home") })
    }

    // 启动时接着上次那个会话继续聊，没有就新建
    LaunchedEffect(settings.workspacePath) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val session = DesktopStorage.openOrCreateSession(workspaceRoot.absolutePath)
                session.id to DesktopStorage.messages(session.id)
            }
        }
        loaded.onSuccess { (sid, stored) ->
            sessionId = sid
            entries.clear()
            history.clear()
            stored.forEach { entity ->
                entries += ChatEntry(if (entity.role == "USER") "你" else "Aharou", entity.content)
                when (entity.role) {
                    "USER" -> history += AgentMessage.UserMessage(id = entity.id, content = entity.content)
                    "ASSISTANT" -> if (entity.content.isNotBlank()) {
                        history += AgentMessage.AssistantMessage(
                            id = entity.id,
                            content = entity.content,
                            reasoning = entity.reasoning ?: "",
                            signature = entity.signature ?: ""
                        )
                    }
                }
            }
        }.onFailure { status = "历史会话读取失败：${it.message}" }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        if (!settings.isUsable) {
            status = "先去设置里填好接口地址、Key 和模型"
            return
        }
        input = ""
        entries += ChatEntry("你", text)
        history += AgentMessage.UserMessage(content = text)
        val replyIndex = entries.size
        entries += ChatEntry("Aharou", "")
        busy = true
        status = ""
        val activeSession = sessionId

        scope.launch(Dispatchers.IO) {
            runCatching { activeSession?.let { DesktopStorage.appendUserMessage(it, text) } }
            val provider = DesktopContainer.provider(settings)
            val toolbox = DesktopToolbox(workspaceRoot)
            val reply = StringBuilder()

            try {
                runAgentTurn(provider, SYSTEM_PROMPT, history, toolbox, approve = { name, args -> gate.ask(name, args) }).collect { event ->
                    when (event) {
                        is TurnEvent.Text -> {
                            reply.append(event.delta)
                            withContext(Dispatchers.Main) {
                                entries[replyIndex] = ChatEntry("Aharou", reply.toString())
                            }
                        }
                        is TurnEvent.ToolStart -> withContext(Dispatchers.Main) {
                            status = "调用 ${event.name}…"
                        }
                        is TurnEvent.ToolDone -> withContext(Dispatchers.Main) {
                            status = "${event.name} ${if (event.ok) "完成" else "失败"}"
                        }
                        is TurnEvent.Done -> {
                            val content = event.assistant.content.ifEmpty { reply.toString() }
                            runCatching {
                                activeSession?.let {
                                    DesktopStorage.appendAssistantMessage(
                                        sessionId = it,
                                        content = content,
                                        reasoning = event.assistant.reasoning,
                                        signature = event.assistant.signature,
                                        thinkingBlocksJson = event.assistant.thinkingBlocksJson,
                                        inputTokens = event.assistant.inputTokens
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                entries[replyIndex] = ChatEntry("Aharou", content)
                                status = ""
                            }
                        }
                        is TurnEvent.Failed -> withContext(Dispatchers.Main) {
                            entries[replyIndex] = ChatEntry("Aharou", "出错了：${event.message}")
                            status = ""
                        }
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    entries[replyIndex] = ChatEntry("Aharou", "出错了：${error.message ?: error::class.simpleName}")
                    status = ""
                }
            }
            withContext(Dispatchers.Main) { busy = false }
        }
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("对话", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(settings.model.ifBlank { "未配置模型" }, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        DividerLine()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(entries) { _, entry ->
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text(entry.role, style = MaterialTheme.typography.labelSmall)
                    Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    if (entry.note.isNotBlank()) {
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么") },
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = !busy) {
                Text(if (busy) "…" else "发送")
            }
        }
    }

    gate.current.value?.let { request ->
        AlertDialog(
            onDismissRequest = { gate.reply(false) },
            title = { Text("允许执行 ${request.toolName}？") },
            text = { Text(request.args.take(800), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { gate.reply(true) }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { gate.reply(false) }) { Text("拒绝") }
            }
        )
    }
}
