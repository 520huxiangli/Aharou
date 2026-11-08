package com.aharou.feature.im.domain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.R
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImCallbackQuery
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ImBotBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private data class Cfg(override val enabled: Boolean = true, override val ready: Boolean = true,
        override val proxy: String = "", val account: String = "first") : ImChannelConfig

    private class Settings : ImSettingsSource {
        override val configFlow = MutableStateFlow<ImChannelConfig>(Cfg())
        @Volatile var saved = "1"
        override suspend fun currentConfig() = configFlow.value
        override suspend fun lastCursor() = saved
        override suspend fun saveCursor(cursor: String) { saved = cursor }
    }

    private class Client(override val id: String, override val supportsButtons: Boolean) : ImChannel {
        val incoming = Channel<List<ImUpdate>>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Pair<ImChannelConfig, String>>()
        val polls = CopyOnWriteArrayList<Long>()
        val connected = CopyOnWriteArrayList<ImChannelConfig>()
        val cards = CopyOnWriteArrayList<String>()
        val accountId = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
        var downloader: suspend (ImAttachment) -> File? = { null }
        var failNotices = false
        var failAllSends = false
        override var supportsEdit = false
        var failEdits = false
        var page: ((Long) -> List<ImUpdate>?)? = null
        override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
            connected.add(cfg)
            return ImBotIdentity(accountId, "bot")
        }
        override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
            polls.add(cursor)
            return page?.invoke(cursor) ?: incoming.receive()
        }
        override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
            if (failAllSends) error("send failed")
            if (failNotices && (text.contains("bad.bin") || text.contains("过期") || text.contains("expired"))) {
                error("notice failed")
            }
            sent.add(cfg to text)
            return "reply"
        }
        override suspend fun edit(cfg: ImChannelConfig, chatId: String, messageId: String, text: String): Boolean {
            if (failEdits) error("edit failed")
            return true
        }
        override suspend fun sendWithButtons(cfg: ImChannelConfig, chatId: String, text: String,
            buttons: List<List<Pair<String, String>>>): String {
            cards.add(buttons.first().first().second)
            return "card"
        }
        override fun redact(text: String, cfg: ImChannelConfig) = text.replace("secret-token", "<token>")
        override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment) = downloader(attachment)
    }

    private inner class Fixture(buttons: Boolean = true, id: String = "qq") {
        val settings = Settings()
        val client = Client(id, buttons)
        val permissions = ToolPermissionManager()
        val runner = mockk<AgentTurnRunner>()
        val sessions = mockk<SessionUseCase>()
        val owners = mockk<ImOwnerStore>()
        val calls = CopyOnWriteArrayList<AgentTurnRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = ImBotBridge(id, client, settings, runner, sessions, permissions, context, owners)
        var turn: suspend (AgentTurnRequest) -> Unit = { }
        init {
            coEvery { owners.owners(any()) } returns setOf("chat", "other")
            coEvery { sessions.getSessionById(any()) } returns mockk(relaxed = true)
            every { runner.run(any()) } answers {
                val request = firstArg<AgentTurnRequest>()
                flow {
                    calls.add(request)
                    turn(request)
                    emit(AgentEvent.AssistantDelta("answer"))
                    emit(AgentEvent.Completed)
                }
            }
        }
        fun start() = bridge.start(scope)
        suspend fun stop() { bridge.stop(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        suspend fun push(vararg updates: ImUpdate) { client.incoming.send(updates.toList()) }
        fun approval(request: AgentTurnRequest) = PendingToolPermission(
            id = "approval", toolName = "Bash", title = "tool", summary = "summary", details = "",
            argsPreview = "", sessionId = request.sessionId
        )
    }

    private fun message(id: Long, text: String = "question", chat: String = "chat",
        attachments: List<ImAttachment> = emptyList(), group: Boolean = false, mentioned: Boolean = false,
        sender: String = "sender", senderName: String = "name", chatTitle: String = "title") =
        ImUpdate.Message(ImInboundMessage(id, chat, sender, senderName, chatTitle, text, group,
            id.toString(), null, mentioned, attachments))
    private fun callback(id: Long, data: String = "a:y:approval", chat: String = "chat") =
        ImUpdate.Callback(ImCallbackQuery(id, "callback-$id", chat, "sender", "card", data))
    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }
    private fun attachment(name: String) = ImAttachment(ImAttachment.Kind.FILE, fileId = name, fileName = name)
    private fun messageContext(text: String): JsonObject = Json.parseToJsonElement(
        text.substringAfter("<im_message type=\"data\">\n").substringBefore("\n</im_message>")
    ).jsonObject
    private fun AgentTurnRequest.bodyText(): String = messageContext(text).getValue("text").jsonPrimitive.content

    @Test fun `普通入站消息逐条携带身份到落库文本和模型且保持聊天隔离`() = runBlocking {
        val f = Fixture(id = "qq_napcat")
        try {
            f.start()
            f.push(
                message(1, "first", group = true, mentioned = true, sender = "123", senderName = "甲", chatTitle = "测试群"),
                message(2, "second", group = true, mentioned = true, sender = "456", senderName = "乙", chatTitle = "测试群"),
                message(3, "private", chat = "other", sender = "789", senderName = "丙", chatTitle = "私聊")
            )
            until { f.settings.saved == "4" }
            assertEquals(3, f.calls.size)
            val expectedSenders = listOf("123", "456", "789")
            val expectedNames = listOf("甲", "乙", "丙")
            f.calls.forEachIndexed { index, request ->
                assertEquals(request.text, request.modelRequest)
                val data = messageContext(request.text)
                assertEquals("qq_napcat", data.getValue("channelId").jsonPrimitive.content)
                assertEquals(if (index < 2) "chat" else "other", data.getValue("chatId").jsonPrimitive.content)
                assertEquals(expectedSenders[index], data.getValue("senderId").jsonPrimitive.content)
                assertEquals(expectedNames[index], data.getValue("senderName").jsonPrimitive.content)
                assertEquals(if (index < 2) "测试群" else "私聊", data.getValue("chatTitle").jsonPrimitive.content)
                assertEquals(index < 2, data.getValue("isGroup").jsonPrimitive.boolean)
                assertEquals(if (index < 2) "im-qq_napcat-chat" else "im-qq_napcat-other", request.sessionId)
            }
            assertEquals(listOf("first", "second", "private"), f.calls.map { it.bodyText() })
        } finally { f.stop() }
    }

    @Test fun `纯附件成功后占位正文和发送者同时进入模型而空消息仍跳过`() = runBlocking {
        val f = Fixture()
        val good = File.createTempFile("im-context", ".bin", context.cacheDir).apply { writeText("ok") }
        f.client.downloader = { good }
        try {
            f.start()
            f.push(message(1, ""), message(2, "", attachments = listOf(attachment("good.bin"))))
            until { f.settings.saved == "3" }
            val request = f.calls.single()
            assertEquals(context.getString(R.string.im_attachment_only_hint), request.bodyText())
            assertEquals("sender", messageContext(request.text).getValue("senderId").jsonPrimitive.content)
            assertEquals(request.text, request.modelRequest)
            assertEquals(1, request.inputAttachments.size)
        } finally { f.stop() }
    }

    @Test fun buttonApprovalUnblocksTurnAndKeepsOrdinaryMessagesSerial() = runBlocking {
        val f = Fixture()
        val approved = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.turn = { request -> if (request.bodyText() == "first") {
            assertEquals(PermissionChoice.ONCE, f.permissions.awaitApproval(f.approval(request)))
            approved.complete(Unit)
            finish.await()
        } }
        try {
            f.start()
            f.push(message(1, "first"))
            until { f.client.cards.isNotEmpty() }
            f.push(message(2, "second"), callback(3), callback(3))
            withTimeout(5_000) { approved.await() }
            assertEquals(listOf("first"), f.calls.map { it.bodyText() })
            assertEquals("1", f.settings.saved)
            finish.complete(Unit)
            until { f.settings.saved == "4" }
            assertEquals(listOf("first", "second"), f.calls.map { it.bodyText() })
        } finally { f.stop() }
    }

    @Test fun textApprovalUnblocksWithoutButtonsAndDuplicateIsExpired(): Unit = runBlocking {
        val f = Fixture(buttons = false)
        val finish = CompletableDeferred<Unit>()
        f.turn = { request -> f.permissions.awaitApproval(f.approval(request)); finish.await() }
        try {
            f.start(); f.push(message(1))
            until { f.permissions.pendingRequest.value != null && f.client.sent.isNotEmpty() }
            f.push(message(2, "允许"))
            until { f.permissions.pendingRequest.value == null }
            f.push(message(3, "允许"))
            until { f.client.sent.any { it.second == context.getString(R.string.im_approval_expired) } }
            assertEquals(1, f.calls.size)
            finish.complete(Unit)
        } finally { f.stop() }
    }

    @Test fun wrongOriginAndMalformedCallbackCannotResolveApproval() = runBlocking {
        val f = Fixture()
        f.turn = { f.permissions.awaitApproval(f.approval(it)) }
        try {
            f.start(); f.push(message(1))
            until { f.client.cards.isNotEmpty() }
            f.push(callback(2, chat = "other"), callback(3, "a:wrong:approval"),
                callback(4, "x:y:approval"), callback(5, "b:y:approval"))
            until { f.client.polls.size >= 3 }
            assertTrue(f.permissions.pendingRequest.value != null)
            f.push(callback(6))
            until { f.settings.saved == "7" }
        } finally { f.stop() }
    }

    @Test fun expiredCallbackNeverReportsAllowedAndNoticeFailureDoesNotRerunTurn() = runBlocking {
        val f = Fixture()
        val finish = CompletableDeferred<Unit>()
        f.turn = { f.permissions.awaitApproval(f.approval(it)); finish.await() }
        try {
            f.start(); f.push(message(1))
            until { f.client.cards.isNotEmpty() }
            f.permissions.resolve("approval", PermissionChoice.REJECT)
            until { f.permissions.pendingRequest.value == null }
            f.push(callback(2))
            until { f.client.sent.any { it.second == context.getString(R.string.im_approval_expired) } }
            assertFalse(f.client.sent.any { it.second == context.getString(R.string.im_telegram_approval_allowed) })
            f.client.failNotices = true
            f.push(callback(3))
            finish.complete(Unit)
            until { f.settings.saved == "4" }
            assertEquals(1, f.calls.size)
        } finally { f.stop() }
    }

    @Test fun groupTextWithoutBotDirectionCannotApprove() = runBlocking {
        val f = Fixture(buttons = false)
        f.turn = { f.permissions.awaitApproval(f.approval(it)) }
        try {
            f.start(); f.push(message(1))
            until { f.client.sent.isNotEmpty() }
            f.push(message(2, "允许", group = true))
            until { f.client.polls.size >= 3 }
            assertTrue(f.permissions.pendingRequest.value != null)
            f.push(message(3, "允许", group = true, mentioned = true))
            until { f.settings.saved == "4" }
        } finally { f.stop() }
    }

    @Test fun enabledBeforeCredentialsConnectsOnConfigChangeAndCancelsOldTurn() = runBlocking {
        val f = Fixture()
        val oldCancelled = CompletableDeferred<Unit>()
        f.settings.configFlow.value = Cfg(ready = false)
        f.turn = { try { CompletableDeferred<Unit>().await() } finally { oldCancelled.complete(Unit) } }
        try {
            f.start()
            delay(30)
            assertTrue(f.client.connected.isEmpty())
            f.settings.configFlow.value = Cfg()
            until { f.client.connected.size == 1 }
            f.push(message(1))
            until { f.calls.size == 1 }
            f.settings.configFlow.value = Cfg(account = "second")
            withTimeout(5_000) { oldCancelled.await() }
            until { f.client.connected.size == 2 }
            f.turn = { }
            f.push(message(2, "new"))
            until { f.calls.size == 2 }
            assertEquals("second", (f.client.connected.last() as Cfg).account)
        } finally { f.stop() }
    }

    @Test fun restartDoesNotDuplicatePermissionCollectors() = runBlocking {
        val f = Fixture()
        f.turn = { f.permissions.awaitApproval(f.approval(it)) }
        try {
            f.start(); until { f.client.connected.size == 1 }
            f.bridge.stop(); f.start()
            until { f.client.connected.size == 2 }
            f.push(message(1))
            until { f.client.cards.isNotEmpty() }
            assertEquals(1, f.client.cards.size)
            f.push(callback(2))
            until { f.settings.saved == "3" }
        } finally { f.stop() }
    }

    @Test fun partialAttachmentFailureIsVisibleAndIncludedAsModelData() = runBlocking {
        val f = Fixture()
        val good = File.createTempFile("im-good", ".bin", context.cacheDir).apply { writeText("ok") }
        f.client.downloader = { if (it.fileName == "good.bin") good else null }
        try {
            f.start(); f.push(message(1, "inspect", attachments = listOf(attachment("good.bin"), attachment("bad.bin"))))
            until { f.settings.saved == "2" }
            assertEquals(1, f.calls.single().inputAttachments.size)
            assertTrue(f.calls.single().modelRequest.contains("bad.bin"))
            assertTrue(f.calls.single().modelRequest.contains("type=\"data\""))
            assertTrue(f.client.sent.any { it.second.contains("bad.bin") })
        } finally { f.stop() }
    }

    @Test fun allAttachmentsFailWithoutBodyDoesNotStartEmptyAnalysis() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.push(message(1, "", attachments = listOf(attachment("bad.bin"))))
            until { f.settings.saved == "2" }
            assertTrue(f.calls.isEmpty())
            assertTrue(f.client.sent.any { it.second.contains("bad.bin") })
        } finally { f.stop() }
    }

    @Test fun allAttachmentsFailWithBodyStillRunsOnceWhenNoticeFails() = runBlocking {
        val f = Fixture()
        f.client.failNotices = true
        try {
            f.start(); f.push(message(1, "body", attachments = listOf(attachment("bad.bin"))))
            until { f.settings.saved == "2" }
            assertEquals("body", f.calls.single().bodyText())
            assertTrue(f.calls.single().modelRequest.contains("bad.bin"))
        } finally { f.stop() }
    }

    @Test fun cancellationDuringDownloadIsNotReportedAsAttachmentFailure() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>()
        f.client.downloader = { started.complete(Unit); CompletableDeferred<File?>().await() }
        try {
            f.start(); f.push(message(1, "body", attachments = listOf(attachment("bad.bin"))))
            withTimeout(5_000) { started.await() }
            f.bridge.stop()
            until { !f.bridge.isRunning() }
            assertTrue(f.calls.isEmpty())
            assertTrue(f.client.sent.isEmpty())
            assertEquals("1", f.settings.saved)
        } finally { f.stop() }
    }

    @Test fun approvalBeyondTelegramHundredUpdatePageIsFetchedAfterDurableEnqueue() = runBlocking {
        val f = Fixture(id = "telegram")
        val approved = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.turn = { request -> if (request.bodyText() == "first") {
            f.permissions.awaitApproval(f.approval(request))
            approved.complete(Unit)
            finish.await()
        } }
        val batch = (1L..101L).map { message(it, if (it == 1L) "first" else "queued-$it") }
        val callbackReady = CompletableDeferred<Unit>()
        f.client.page = { offset ->
            when {
                offset <= 100 -> batch.filter { it.updateId >= offset }.take(100)
                offset <= 101 -> listOf(batch.last())
                offset == 102L && callbackReady.isCompleted -> listOf(callback(102))
                else -> null
            }
        }
        try {
            f.start()
            until { f.client.cards.isNotEmpty() && f.client.polls.contains(102L) }
            callbackReady.complete(Unit)
            f.push(callback(102))
            withTimeout(5_000) { approved.await() }
            assertEquals("1", f.settings.saved)
            assertEquals(1, f.calls.size)
            val recovered = TelegramInbox(File(context.filesDir, "aharou/telegram_inbox"), f.client.accountId)
            assertEquals(103L, recovered.nextOffset)
            assertEquals(102, recovered.pending().size)
            finish.complete(Unit)
            until { f.settings.saved == "103" }
            assertEquals(101, f.calls.size)
        } finally { f.stop() }
    }

    @Test fun completedTurnIsNotExecutedAgainWhenFinalReplySendFails() = runBlocking {
        val f = Fixture()
        f.client.failAllSends = true
        try {
            f.start(); f.push(message(1))
            until { f.settings.saved == "2" }
            assertEquals(1, f.calls.size)
        } finally { f.stop() }
    }

    @Test fun streamingSendAndFinalEditFailuresDoNotRestartRunner() = runBlocking {
        val f = Fixture()
        f.client.supportsEdit = true
        f.client.failEdits = true
        try {
            f.start(); f.push(message(1))
            until { f.settings.saved == "2" }
            assertEquals(1, f.calls.size)
            f.client.failAllSends = true
            f.push(message(2))
            until { f.settings.saved == "3" }
            assertEquals(2, f.calls.size)
        } finally { f.stop() }
    }

    @Test fun duplicateInflightOrdinaryMessageIsQueuedOnlyOnce() = runBlocking {
        val f = Fixture()
        val finish = CompletableDeferred<Unit>()
        f.turn = { if (it.bodyText() == "first") finish.await() }
        try {
            f.start(); f.push(message(1, "first"))
            until { f.calls.size == 1 }
            f.push(message(1, "first"), message(2, "second"), message(2, "second"))
            until { f.client.polls.size >= 3 }
            assertEquals(1, f.calls.size)
            finish.complete(Unit)
            until { f.settings.saved == "3" }
            assertEquals(listOf("first", "second"), f.calls.map { it.bodyText() })
        } finally { f.stop() }
    }

    @Test fun telegramInboxSurvivesReopenAndSeparatesAccounts() {
        val dir = File(context.cacheDir, "inbox-${UUID.randomUUID()}")
        val first = TelegramInbox(dir, 1)
        first.append(listOf(message(1), callback(2)))
        val restored = TelegramInbox(dir, 1)
        assertEquals(listOf(1L, 2L), restored.pending().map { it.updateId })
        assertEquals(3L, restored.nextOffset)
        restored.remove(listOf(1))
        assertEquals(listOf(2L), TelegramInbox(dir, 1).pending().map { it.updateId })
        assertTrue(TelegramInbox(dir, 2).pending().isEmpty())
    }
}
