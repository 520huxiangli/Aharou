package com.aharou.feature.plugin.character

import com.aharou.feature.plugin.domain.PluginHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * Tavern 角色卡（chara_card_v2）与 [CharacterCard] 的互相转换。
 *
 * 字段映射照 Operit（`CharacterCardManager.convertTavernCardToCharacterCard` /
 * `exportCharacterCardToTavernJson`）来做，理由很实际：同一张卡在 Operit 与 Aharou 之间
 * 来回搬时，两边拼出来的 `characterSetting` / `otherContentChat` 必须长得一样，
 * 否则每搬一次就多叠一层「角色描述」标题。
 *
 * 与 Operit 唯一的扩展：导入时把 `character_book` 的条目留在卡上（[CharacterCard.worldBookEntries]），
 * 导出时写回 `character_book`。Operit 会把这些条目转成提示词标签，Aharou 没有标签库，
 * 直接丢掉就等于静默吞掉作者的设定。
 */
object TavernCharacterCardMapper {

    private const val SPEC = "chara_card_v2"
    private const val SPEC_VERSION = "2.0"
    private const val OPERIT_EXTENSION_SCHEMA = "operit_character_card_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 解析一张 Tavern 卡。返回的卡不带 id 与时间戳，由存储层补齐。 */
    fun import(jsonText: String): CharacterCard {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
            ?: throw PluginHostException("PARAM_INVALID", "tavern_json 不是合法的 JSON 对象")
        val data = root["data"] as? JsonObject
            ?: throw PluginHostException(
                "PARAM_INVALID",
                "Tavern 角色卡缺少 data 字段（需要 chara_card_v2 / chara_card_v3 格式）"
            )
        val name = data.str("name").orEmpty().trim()
        if (name.isEmpty()) throw PluginHostException("PARAM_INVALID", "Tavern 角色卡缺少角色名（data.name）")

        val extension = (data["extensions"] as? JsonObject)
            ?.get("operit") as? JsonObject
        val payload = extension
            ?.takeIf { it.str("schema") == OPERIT_EXTENSION_SCHEMA }
            ?.get("character_card") as? JsonObject

        val card = if (payload != null) fromOperitPayload(payload, name) else fromPlainTavern(data, root)
        return card.copy(
            avatar = data.str("avatar").orEmpty(),
            tags = if (card.tags.isNotEmpty()) card.tags else data.strings("tags"),
            worldBookEntries = parseWorldBook(data["character_book"] as? JsonObject),
        )
    }

    /** 导出成 Tavern v2 卡；`extensions.operit` 里放完整 payload，保证再导入时零损耗。 */
    fun export(card: CharacterCard): String {
        val data = buildJsonObject {
            put("name", card.name)
            put("description", card.description)
            put("personality", "")
            put("first_mes", card.openingStatement)
            put("avatar", card.avatar)
            put("mes_example", card.otherContentChat)
            put("scenario", "")
            put("creator_notes", card.marks)
            put("system_prompt", card.characterSetting)
            put("post_history_instructions", card.advancedCustomPrompt)
            put("alternate_greetings", buildJsonArray { })
            put("tags", buildJsonArray { card.tags.forEach { add(JsonPrimitive(it)) } })
            put("creator", "")
            put("character_version", "")
            put("extensions", buildJsonObject {
                put("operit", buildJsonObject {
                    put("schema", OPERIT_EXTENSION_SCHEMA)
                    put("character_card", operitPayload(card))
                })
            })
            if (card.worldBookEntries.isEmpty()) {
                put("character_book", JsonNull)
            } else {
                put("character_book", buildJsonObject {
                    put("name", "${card.name} 世界书")
                    put("description", "")
                    put("scan_depth", 0)
                    put("token_budget", 0)
                    put("recursive_scanning", false)
                    put("extensions", buildJsonObject { })
                    put("entries", buildJsonArray {
                        card.worldBookEntries.forEachIndexed { index, entry ->
                            add(buildJsonObject {
                                put("name", entry.name)
                                put("keys", buildJsonArray { entry.keys.forEach { add(JsonPrimitive(it)) } })
                                put("secondary_keys", buildJsonArray { })
                                put("content", entry.content)
                                put("enabled", true)
                                put("insertion_order", index)
                                put("id", index)
                            })
                        }
                    })
                })
            }
        }
        return buildJsonObject {
            put("spec", SPEC)
            put("spec_version", SPEC_VERSION)
            put("data", data)
        }.toString()
    }

    private fun fromPlainTavern(data: JsonObject, root: JsonObject): CharacterCard {
        val setting = joinSections(
            listOf(
                section("角色描述", data.str("description").orEmpty()),
                section("性格", data.str("personality").orEmpty()),
                section("场景", data.str("scenario").orEmpty()),
            )
        )
        val alternates = data.strings("alternate_greetings")
        val otherChat = joinSections(
            listOf(
                section("对话示例", data.str("mes_example").orEmpty()),
                section("系统提示", data.str("system_prompt").orEmpty()),
                section("历史后置指令", data.str("post_history_instructions").orEmpty()),
                if (alternates.isEmpty()) {
                    null
                } else {
                    "备用问候语\n" + alternates.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")
                },
            )
        )
        val depthPrompt = ((data["extensions"] as? JsonObject)?.get("depth_prompt") as? JsonObject)
            ?.str("prompt")
            .orEmpty()
        val tags = data.strings("tags")
        val marks = buildList {
            add("来源: Tavern 角色卡")
            data.str("creator")?.takeIf { it.isNotBlank() }?.let { add("作者: $it") }
            data.str("creator_notes")?.takeIf { it.isNotBlank() }?.let { add("作者备注: $it") }
            data.str("character_version")?.takeIf { it.isNotBlank() }?.let { add("角色版本: $it") }
            if (tags.isNotEmpty()) add("原始标签: ${tags.joinToString(", ")}")
            val spec = root.str("spec").orEmpty()
            if (spec.isNotBlank()) {
                val version = root.str("spec_version").orEmpty()
                add("格式: $spec" + if (version.isBlank()) "" else " v$version")
            }
        }.joinToString("\n")

        return CharacterCard(
            name = data.str("name").orEmpty().trim(),
            // Operit 把 Tavern 的标签摘要放进卡片的「描述」；它的卡片描述本就是个索引行，
            // 真正的设定文本在上面的 characterSetting 里。
            description = if (tags.isEmpty()) "" else "标签: " + tags.take(5).joinToString(", ") +
                (if (tags.size > 5) " 等${tags.size}个" else ""),
            characterSetting = setting,
            openingStatement = data.str("first_mes").orEmpty(),
            otherContentChat = otherChat,
            advancedCustomPrompt = section("深度提示", depthPrompt).orEmpty(),
            marks = marks,
            tags = tags,
        )
    }

    private fun fromOperitPayload(payload: JsonObject, fallbackName: String): CharacterCard {
        val toolAccess = payload["toolAccessConfig"] as? JsonObject
        return CharacterCard(
            name = payload.str("name").orEmpty().ifBlank { fallbackName },
            description = payload.str("description").orEmpty(),
            characterSetting = payload.str("characterSetting").orEmpty(),
            openingStatement = payload.str("openingStatement").orEmpty(),
            otherContentChat = payload.str("otherContentChat").orEmpty()
                .ifBlank { payload.str("otherContent").orEmpty() },
            otherContentVoice = payload.str("otherContentVoice").orEmpty(),
            advancedCustomPrompt = payload.str("advancedCustomPrompt").orEmpty(),
            marks = payload.str("marks").orEmpty(),
            tags = (payload["attachedTags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.str("name")?.trim()?.takeIf { name -> name.isNotEmpty() } }
                .orEmpty(),
            attachedTagIds = payload.strings("attachedTagIds"),
            chatModelBindingMode = CharacterCardBinding.normalizeModelMode(payload.str("chatModelBindingMode")),
            chatModelConfigId = payload.str("chatModelConfigId")?.takeIf { it.isNotBlank() },
            chatModelIndex = payload.int("chatModelIndex") ?: 0,
            memoryProfileBindingMode = CharacterCardBinding.normalizeMemoryMode(payload.str("memoryProfileBindingMode")),
            memoryProfileId = payload.str("memoryProfileId")?.takeIf { it.isNotBlank() },
            toolAccessConfig = CharacterCardToolAccess(
                enabled = (toolAccess?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false,
                allowedBuiltinTools = toolAccess?.strings("allowedBuiltinTools").orEmpty(),
                allowedPackages = toolAccess?.strings("allowedPackages").orEmpty(),
                allowedSkills = toolAccess?.strings("allowedSkills").orEmpty(),
                allowedMcpServers = toolAccess?.strings("allowedMcpServers").orEmpty(),
            ),
        )
    }

    private fun operitPayload(card: CharacterCard): JsonObject = buildJsonObject {
        put("name", card.name)
        put("description", card.description)
        put("characterSetting", card.characterSetting)
        put("openingStatement", card.openingStatement)
        put("otherContent", card.otherContentChat)
        put("otherContentChat", card.otherContentChat)
        put("otherContentVoice", card.otherContentVoice)
        put("attachedTagIds", buildJsonArray { card.attachedTagIds.forEach { add(JsonPrimitive(it)) } })
        put("attachedTags", buildJsonArray {
            card.tags.forEach { tag ->
                add(buildJsonObject {
                    put("id", "")
                    put("name", tag)
                    put("description", "")
                    put("promptContent", "")
                    put("tagType", "CUSTOM")
                })
            }
        })
        put("advancedCustomPrompt", card.advancedCustomPrompt)
        put("marks", card.marks)
        put("chatModelBindingMode", CharacterCardBinding.normalizeModelMode(card.chatModelBindingMode))
        if (card.chatModelConfigId.isNullOrBlank()) {
            put("chatModelConfigId", JsonNull)
        } else {
            put("chatModelConfigId", card.chatModelConfigId)
        }
        put("chatModelIndex", card.chatModelIndex.coerceAtLeast(0))
        put("memoryProfileBindingMode", CharacterCardBinding.normalizeMemoryMode(card.memoryProfileBindingMode))
        if (card.memoryProfileId.isNullOrBlank()) {
            put("memoryProfileId", JsonNull)
        } else {
            put("memoryProfileId", card.memoryProfileId)
        }
        put("toolAccessConfig", buildJsonObject {
            put("enabled", card.toolAccessConfig.enabled)
            put("allowedBuiltinTools", buildJsonArray { card.toolAccessConfig.allowedBuiltinTools.forEach { add(JsonPrimitive(it)) } })
            put("allowedPackages", buildJsonArray { card.toolAccessConfig.allowedPackages.forEach { add(JsonPrimitive(it)) } })
            put("allowedSkills", buildJsonArray { card.toolAccessConfig.allowedSkills.forEach { add(JsonPrimitive(it)) } })
            put("allowedMcpServers", buildJsonArray { card.toolAccessConfig.allowedMcpServers.forEach { add(JsonPrimitive(it)) } })
        })
    }

    private fun parseWorldBook(book: JsonObject?): List<CharacterCardWorldBookEntry> {
        val entries = book?.get("entries")
        val items = when (entries) {
            is JsonArray -> entries
            // 有的卡把 entries 写成「id → 条目」的对象
            is JsonObject -> JsonArray(entries.values.toList())
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val content = entry.str("content").orEmpty().trim()
            if (content.isEmpty()) return@mapNotNull null
            CharacterCardWorldBookEntry(
                name = entry.str("name").orEmpty().trim().ifEmpty { entry.str("comment").orEmpty().trim() },
                keys = entry.strings("keys"),
                content = content,
            )
        }
    }

    private fun section(label: String, value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }?.let { "$label\n$it" }

    private fun joinSections(parts: List<String?>): String =
        parts.filterNotNull().joinToString("\n\n").trim()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .orEmpty()
}
