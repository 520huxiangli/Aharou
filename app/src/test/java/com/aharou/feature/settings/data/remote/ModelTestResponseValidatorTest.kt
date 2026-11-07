package com.aharou.feature.settings.data.remote

import com.aharou.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTestResponseValidatorTest {
    @Test fun chatAllowsEmptyAndNullText() {
        listOf("\"\"", "null").forEach { content ->
            assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.CHAT,
                """{"choices":[{"message":{"role":"assistant","content":$content},"finish_reason":"length"}]}"""))
        }
    }

    @Test fun httpSuccessBodyMustBeProtocolResponse() {
        listOf("", "<html>ok</html>", "{}", "[]", """{"choices":"wrong"}""").forEach { body ->
            assertNotNull(ModelTestResponseValidator.validate(ModelTestProtocol.CHAT, body))
        }
        assertEquals("error", ModelTestResponseValidator.validate(ModelTestProtocol.CHAT, """{"error":{"message":"bad"}}"""))
    }

    @Test fun responsesAllowsOutputLimitButRejectsFailure() {
        assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.RESPONSES,
            """{"object":"response","status":"completed","output":[]}"""))
        assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.RESPONSES,
            """{"object":"response","status":"incomplete","output":[],"incomplete_details":{"reason":"max_output_tokens"}}"""))
        assertEquals("error", ModelTestResponseValidator.validate(ModelTestProtocol.RESPONSES,
            """{"object":"response","status":"failed","output":[]}"""))
    }

    @Test fun anthropicAndGeminiAllowValidEmptyGeneration() {
        assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.ANTHROPIC,
            """{"type":"message","role":"assistant","content":[],"stop_reason":"max_tokens"}"""))
        assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.GEMINI,
            """{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""))
        assertEquals("error", ModelTestResponseValidator.validate(ModelTestProtocol.GEMINI,
            """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }

    @Test fun interactionsRequiresCompletedStructure() {
        assertNull(ModelTestResponseValidator.validate(ModelTestProtocol.INTERACTIONS,
            """{"id":"interaction","status":"completed","outputs":[]}"""))
        assertNotNull(ModelTestResponseValidator.validate(ModelTestProtocol.INTERACTIONS,
            """{"id":"interaction","status":"in_progress","outputs":[]}"""))
    }

    @Test fun fullEndpointOverridesProtocolFlag() {
        assertEquals(ModelTestProtocol.RESPONSES, ModelTestResponseValidator.protocol(
            ProviderType.OPENAI, false, "model", "https://example.invalid/v1/responses"))
        assertEquals(ModelTestProtocol.CHAT, ModelTestResponseValidator.protocol(
            ProviderType.OPENAI, true, "model", "https://example.invalid/v1/chat/completions"))
    }
}
