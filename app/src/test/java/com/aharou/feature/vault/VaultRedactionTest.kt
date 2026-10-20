package com.aharou.feature.vault

import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 结果级脱敏：用户批准读明文的工具结果跳过脱敏，其它结果照旧。
 * 用 [redactUsing] 注入脱敏函数，不依赖 VaultRepository（它需要 Android Context）。
 */
class VaultRedactionTest {

    private val secret = "Zx9Qm2Lp7Rt4Wv1Bn8Kc5Hd3S"
    private val redactor: (String) -> String = { text -> redactText(text, listOf("openai" to secret)) }

    /** 取结果 data 里的 value 字段（Success / Partial 都行）。 */
    private fun valueOf(result: ToolResult): String {
        val data = when (result) {
            is ToolResult.Success -> result.data
            is ToolResult.Partial -> result.data
            is ToolResult.Error -> error("Error 结果没有 data")
        }
        return data.jsonObject.getValue("value").jsonPrimitive.content
    }

    @Test
    fun approvedPlaintextIsNotRedacted() {
        val result = ToolResult.Success(
            buildJsonObject { put("value", secret) },
            plaintextApproved = true,
        )
        assertEquals(secret, valueOf(result.redactUsing(redactor)))
    }

    @Test
    fun normalToolResultIsStillRedacted() {
        val result = ToolResult.Success(buildJsonObject { put("value", secret) })
        assertEquals("[REDACTED:openai]", valueOf(result.redactUsing(redactor)))
    }

    @Test
    fun nestedTextLeavesAreRedacted() {
        val result = ToolResult.Success(
            buildJsonObject {
                put("value", secret)
                put(
                    "nested",
                    buildJsonObject {
                        put(
                            "list",
                            buildJsonArray {
                                add(JsonPrimitive("token=$secret"))
                                add(JsonPrimitive(7))
                            },
                        )
                    },
                )
            },
        )
        val redacted = (result.redactUsing(redactor) as ToolResult.Success).data.jsonObject
        assertEquals("[REDACTED:openai]", redacted.getValue("value").jsonPrimitive.content)
        val list = redacted.getValue("nested").jsonObject.getValue("list").jsonArray
        assertEquals("token=[REDACTED:openai]", list[0].jsonPrimitive.content)
        assertEquals("7", list[1].jsonPrimitive.content)
    }

    @Test
    fun partialAndErrorMessageAreRedacted() {
        val partial = ToolResult.Partial(buildJsonObject { put("value", secret) }, "echo $secret")
        val redactedPartial = partial.redactUsing(redactor) as ToolResult.Partial
        assertEquals("[REDACTED:openai]", valueOf(redactedPartial))
        assertEquals("echo [REDACTED:openai]", redactedPartial.message)

        val error = ToolResult.Error("bad $secret")
        assertEquals("bad [REDACTED:openai]", (error.redactUsing(redactor) as ToolResult.Error).message)
    }

    @Test
    fun approvalFlagIsNotSerialized() {
        val wire = ToolResult.Success(JsonPrimitive("ok"), plaintextApproved = true).toTransportString()
        assertFalse(wire.contains("plaintextApproved"))
    }

    @Test
    fun skipDependsOnTheFlagNotOnTheData() {
        val result = ToolResult.Success(
            buildJsonObject { put("value", secret) },
            plaintextApproved = true,
        )
        // 同一份数据，未标标记时仍然会被脱敏：跳过取决于标记，不取决于内容。
        val unflagged = ToolResult.Success(buildJsonObject { put("value", secret) })
        assertEquals(secret, valueOf(result.redactUsing(redactor)))
        assertEquals("[REDACTED:openai]", valueOf(unflagged.redactUsing(redactor)))
    }
}
