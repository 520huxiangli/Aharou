package com.aharou.feature.agent.domain.notification

import com.aharou.feature.agent.domain.model.AgentMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知搭车注入器：合法 JSON 结果注入顶层 `notifications` 字段且仍可解析；
 * 非 JSON 结果退化为文本追加，保证不丢事件。
 */
class AgentEventInjectorTest {

    private val injector = DefaultAgentEventInjector()

    private val modeChange = PendingNotification(
        kind = AgentNotificationKind.MODE_CHANGE,
        sourceId = "user",
        title = "PLAN",
        outcome = NotificationOutcome.COMPLETED,
        newMode = AgentMode.PLAN
    )

    @Test
    fun inject_validJson_addsNotificationsFieldAndKeepsOriginal() {
        val raw = """{"success":true,"data":{"message":"ok"}}"""

        val injected = injector.inject(raw, listOf(modeChange))
        val obj = Json.parseToJsonElement(injected).jsonObject

        assertEquals("ok", (obj["data"] as JsonObject)["message"]!!.jsonPrimitive.content)
        val notifications = obj["notifications"] as JsonArray
        assertEquals(1, notifications.size)
        assertEquals("mode_change", (notifications[0] as JsonObject)["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun inject_nonJson_fallsBackToTextAppend() {
        val raw = "tool output is plain text"

        val injected = injector.inject(raw, listOf(modeChange))

        assertTrue(injected.startsWith(raw))
        assertTrue(injected.contains("<mode-change>"))
        assertTrue(injected.contains("<new-mode>PLAN</new-mode>"))
    }

    @Test
    fun inject_emptyEvents_returnsRaw() {
        val raw = """{"success":true}"""

        assertEquals(raw, injector.inject(raw, emptyList()))
    }
}