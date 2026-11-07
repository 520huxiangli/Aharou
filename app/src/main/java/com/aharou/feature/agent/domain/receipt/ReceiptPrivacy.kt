package com.aharou.feature.agent.domain.receipt

import com.aharou.core.util.SecretMasker
import com.aharou.feature.vault.VaultRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ReceiptPrivacy {
    private const val MASK = "[REDACTED]"
    private val sensitiveKey = Regex(
        "(?i)(password|passwd|passphrase|token|secret|credential|private.?key|api.?key|access.?key|authorization|cookie|^keys$)"
    )
    private val sensitivePath = Regex(
        "(?i)(secrets?(?:[./_\\-]|$)|credentials?(?:[./_\\-]|$)|(?:^|/)\\.env(?:[./_\\-]|$)|id_(?:rsa|ed25519)|keystore\\.properties)"
    )
    private val argumentSecret = Regex(
        "(?i)((?:--)?(?:password|passwd|passphrase|token|secret|api[_-]?key|authorization)[\\\"']?\\s*(?:[=:]\\s*|\\s+))([\\\"']?)([^\\s\\\"',;}]+)"
    )
    private val urlPassword = Regex("(https?://[^\\s/:]+:)[^\\s/@]+(@)")

    fun text(value: String): String {
        val bearerMasked = Regex("(?i)\\bbearer\\s+[^\\s\\\"',;}]+").replace(value, "Bearer $MASK")
        val masked = argumentSecret.replace(bearerMasked) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        return VaultRedactor.redact(SecretMasker.mask(urlPassword.replace(masked, "$1$MASK$2")))
    }

    fun arguments(toolName: String, args: Map<String, JsonElement>): String {
        val sensitiveTarget = hasSensitiveTarget(args)
        val secretOperation = toolName.contains("credential", ignoreCase = true) ||
            (args["action"] as? JsonPrimitive)?.content == "reveal"
        fun sanitize(value: JsonElement, key: String = ""): JsonElement = when {
            sensitiveKey.containsMatchIn(key) -> JsonPrimitive(MASK)
            (sensitiveTarget || secretOperation) && key in setOf(
                "value", "content", "text", "new_string", "old_string", "command", "data", "edits"
            ) -> JsonPrimitive(MASK)
            value is JsonObject -> JsonObject(value.mapValues { (childKey, child) -> sanitize(child, childKey) })
            value is JsonArray -> JsonArray(value.map { sanitize(it, key) })
            value is JsonPrimitive && value.isString -> JsonPrimitive(text(value.content))
            else -> value
        }
        return JsonObject(args.mapValues { (key, value) -> sanitize(value, key) }).toString()
    }

    fun omitResult(toolName: String, args: Map<String, JsonElement>): Boolean =
        toolName.contains("credential", ignoreCase = true) ||
            (args["action"] as? JsonPrimitive)?.content == "reveal" || hasSensitiveTarget(args)

    fun structuredText(value: String): String {
        val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
        return if (parsed is JsonObject) arguments("", parsed) else text(value)
    }

    private fun hasSensitiveTarget(args: Map<String, JsonElement>): Boolean = args.any { (key, value) ->
        key in setOf("path", "name", "query", "file", "output_path") &&
            value is JsonPrimitive &&
            (sensitivePath.containsMatchIn(value.content) || sensitiveKey.containsMatchIn(value.content))
    }
}
