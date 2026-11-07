package com.aharou.feature.settings.data.remote

import com.aharou.feature.settings.domain.model.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class ModelTestProtocol { CHAT, RESPONSES, ANTHROPIC, GEMINI, INTERACTIONS }

internal object ModelTestResponseValidator {
    fun protocol(type: ProviderType, responses: Boolean, model: String, url: String): ModelTestProtocol {
        val path = url.substringBefore('?').trimEnd('/')
        return when {
            path.endsWith("/chat/completions") -> ModelTestProtocol.CHAT
            path.endsWith("/responses") -> ModelTestProtocol.RESPONSES
            path.endsWith("/messages") -> ModelTestProtocol.ANTHROPIC
            path.endsWith("/interactions") -> ModelTestProtocol.INTERACTIONS
            path.endsWith(":generateContent") -> ModelTestProtocol.GEMINI
            type == ProviderType.ANTHROPIC -> ModelTestProtocol.ANTHROPIC
            type == ProviderType.GEMINI && (responses || model.endsWith("-image", true)) -> ModelTestProtocol.INTERACTIONS
            type == ProviderType.GEMINI -> ModelTestProtocol.GEMINI
            responses -> ModelTestProtocol.RESPONSES
            else -> ModelTestProtocol.CHAT
        }
    }

    fun validate(protocol: ModelTestProtocol, body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return "invalid"
        if (root["error"] != null && root["error"] != JsonNull) return "error"
        fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
        fun array(name: String) = root[name] as? JsonArray
        val valid = when (protocol) {
            ModelTestProtocol.CHAT -> array("choices")?.takeIf { it.isNotEmpty() }?.all { choice ->
                val item = choice as? JsonObject
                val message = item?.get("message") as? JsonObject
                message != null && message.string("role") == "assistant" &&
                    (message.containsKey("content") || message["tool_calls"] is JsonArray) &&
                    (message["content"] == JsonNull || message["content"] is JsonArray ||
                        (message["content"] as? JsonPrimitive)?.isString == true || message["tool_calls"] is JsonArray)
            } == true
            ModelTestProtocol.RESPONSES -> {
                val status = root.string("status")
                if (status in setOf("failed", "cancelled")) return "error"
                val limited = status == "incomplete" && (root["incomplete_details"] as? JsonObject)?.string("reason") == "max_output_tokens"
                root.string("object") == "response" && array("output") != null && (status == "completed" || limited)
            }
            ModelTestProtocol.ANTHROPIC -> root.string("type") == "message" &&
                root.string("role") == "assistant" && array("content") != null && !root.string("stop_reason").isNullOrBlank()
            ModelTestProtocol.GEMINI -> {
                val blocked = (root["promptFeedback"] as? JsonObject)?.string("blockReason")
                if (!blocked.isNullOrBlank() && blocked != "BLOCK_REASON_UNSPECIFIED") return "error"
                array("candidates")?.takeIf { it.isNotEmpty() }?.all { candidate ->
                    val item = candidate as? JsonObject
                    val reason = item?.string("finishReason")
                    if (reason in setOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "RECITATION")) return "error"
                    item != null && (item["content"] as? JsonObject)?.get("parts") is JsonArray || reason in setOf("STOP", "MAX_TOKENS")
                } == true
            }
            ModelTestProtocol.INTERACTIONS -> {
                val status = root.string("status")
                if (status in setOf("failed", "cancelled")) return "error"
                !root.string("id").isNullOrBlank() && status == "completed" &&
                    (array("outputs") != null || array("steps") != null)
            }
        }
        return if (valid) null else "invalid"
    }
}
