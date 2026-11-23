package com.aharou.feature.plugin.character

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `characterCard.*` 宿主方法，对应 Operit 的 `Tools.SoftwareSettings.*` 与 `Tools.Chat.*`
 * 里的角色卡部分（9 个方法：list / get / create / update / delete / setActive / clearActive /
 * importTavern / exportTavern；Operit 侧同名方法是 listCharacterCards / getCharacterCard /
 * createCharacterCard / updateCharacterCard / deleteCharacterCard / setActiveCharacterCard /
 * clearActiveCharacterCard / importCharacterCardFromTavernJson / exportCharacterCardToTavernJson）。
 *
 * 边界：角色卡只是插件的自由数据，**不参与 Aharou 的对话**。激活哪张卡不会改动 SOUL.md，
 * 也不会改变系统提示词——那是 App 人格的唯一来源，插件不该有能力覆盖它。
 */
@Singleton
class PluginCharacterCardService @Inject constructor(
    private val store: CharacterCardStore,
    private val pluginManager: PluginManager
) {

    suspend fun list(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val cards = store.cards()
        val active = store.activeId()
        buildJsonObject {
            put("totalCount", cards.size)
            put("activeCharacterCardId", active)
            put("cards", buildJsonArray { cards.forEach { add(cardJson(it)) } })
        }
    }

    suspend fun get(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val id = params.requireId()
        val card = store.card(id) ?: throw notFound(id)
        buildJsonObject {
            put("card", cardJson(card))
            put("activeCharacterCardId", store.activeId())
        }
    }

    suspend fun create(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val (draft, changedFields) = applyFields(CharacterCard(), params, requireName = true)
        val card = store.create(draft)
        buildJsonObject {
            put("created", true)
            put("card", cardJson(card))
            put("activeCharacterCardId", store.activeId())
            put("changedFields", stringArray(changedFields))
        }
    }

    suspend fun update(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val id = params.requireId()
        val existing = store.card(id) ?: throw notFound(id)
        val (patched, changedFields) = applyFields(existing, params, requireName = false)
        if (changedFields.isEmpty()) {
            throw PluginHostException("PARAM_INVALID", "至少提供一个要更新的字段")
        }
        val card = store.update(id) { patched }
        buildJsonObject {
            put("updated", true)
            put("card", cardJson(card))
            put("activeCharacterCardId", store.activeId())
            put("changedFields", stringArray(changedFields))
        }
    }

    suspend fun delete(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val id = params.requireId()
        store.delete(id) ?: throw notFound(id)
        buildJsonObject {
            put("deleted", true)
            put("characterCardId", id)
            put("activeCharacterCardId", store.activeId())
        }
    }

    /** 只记「当前激活的是谁」；不碰 SOUL，也不影响任何正在进行的会话。 */
    suspend fun setActive(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val id = params.requireId()
        val active = store.setActive(id)
        buildJsonObject { put("activeCharacterCardId", active) }
    }

    suspend fun clearActive(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        store.setActive(null)
        buildJsonObject { put("activeCharacterCardId", null as String?) }
    }

    suspend fun importTavern(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        // Operit 传字符串（tavern_json）；也接受已经解析好的对象，省得插件再 stringify 一次。
        val jsonText = when (val raw = params["tavern_json"] ?: params["tavernJson"]) {
            null -> throw PluginHostException("PARAM_MISSING", "missing parameter: tavern_json")
            is JsonObject -> raw.toString()
            is JsonPrimitive -> raw.contentOrNull
                ?: throw PluginHostException("PARAM_INVALID", "tavern_json 必须是 JSON 文本")
            else -> throw PluginHostException("PARAM_INVALID", "tavern_json 必须是 JSON 文本或对象")
        }
        val draft = TavernCharacterCardMapper.import(jsonText)
        val card = store.create(draft)
        buildJsonObject {
            put("imported", true)
            put("card", cardJson(card))
            put("activeCharacterCardId", store.activeId())
        }
    }

    suspend fun exportTavern(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val id = params.requireId()
        val card = store.card(id) ?: throw notFound(id)
        buildJsonObject {
            put("characterCardId", id)
            put("tavernJson", TavernCharacterCardMapper.export(card))
        }
    }

    /**
     * 把请求参数并进卡上。`includeName=false` 时不允许改名；没出现的字段完全不动，
     * 与 Operit 的「部分更新」语义一致。
     */
    private fun applyFields(
        current: CharacterCard,
        params: JsonObject,
        requireName: Boolean
    ): Pair<CharacterCard, List<String>> {
        var card = current
        val changed = mutableListOf<String>()

        fun applyString(field: String, key: String, write: (CharacterCard, String) -> CharacterCard) {
            val value = params.text(key) ?: return
            if (value.length > MAX_FIELD_CHARS) {
                throw PluginHostException("PARAM_INVALID", "$key 超长（上限 $MAX_FIELD_CHARS 字符）")
            }
            card = write(card, value)
            changed += field
        }

        applyString("name", "name") { source, value ->
            if (value.isBlank()) throw PluginHostException("PARAM_INVALID", "name 不能为空")
            source.copy(name = value.trim())
        }
        applyString("description", "description") { source, value -> source.copy(description = value) }
        applyString("avatar", "avatar") { source, value -> source.copy(avatar = value.trim()) }
        applyString("characterSetting", "character_setting") { source, value -> source.copy(characterSetting = value) }
        applyString("characterSetting", "characterSetting") { source, value -> source.copy(characterSetting = value) }
        applyString("openingStatement", "opening_statement") { source, value -> source.copy(openingStatement = value) }
        applyString("openingStatement", "openingStatement") { source, value -> source.copy(openingStatement = value) }
        applyString("otherContentChat", "other_content_chat") { source, value -> source.copy(otherContentChat = value) }
        applyString("otherContentChat", "otherContentChat") { source, value -> source.copy(otherContentChat = value) }
        applyString("otherContentVoice", "other_content_voice") { source, value -> source.copy(otherContentVoice = value) }
        applyString("otherContentVoice", "otherContentVoice") { source, value -> source.copy(otherContentVoice = value) }
        applyString("advancedCustomPrompt", "advanced_custom_prompt") { source, value ->
            source.copy(advancedCustomPrompt = value)
        }
        applyString("advancedCustomPrompt", "advancedCustomPrompt") { source, value ->
            source.copy(advancedCustomPrompt = value)
        }
        applyString("marks", "marks") { source, value -> source.copy(marks = value) }

        params.stringList("tags")?.let { tags ->
            card = card.copy(tags = tags)
            if ("tags" !in changed) changed += "tags"
        }
        params.stringList("attached_tag_ids")?.let { ids ->
            card = card.copy(attachedTagIds = ids)
            if ("attachedTagIds" !in changed) changed += "attachedTagIds"
        }
        // Operit 的 attachedTags 是完整标签对象；Aharou 没有标签库，只取名字存进 tags。
        (params["attached_tags"] as? JsonArray)?.let { raw ->
            val names = raw.mapNotNull { element ->
                when (element) {
                    is JsonObject -> element.text("name")?.trim()?.takeIf { it.isNotEmpty() }
                    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
            card = card.copy(tags = names.distinct())
            if ("tags" !in changed) changed += "tags"
        }

        params.text("chat_model_binding_mode")?.let { raw ->
            card = card.copy(chatModelBindingMode = CharacterCardBinding.normalizeModelMode(raw))
            changed += "chatModelBindingMode"
        }
        params.text("chat_model_config_id")?.let { raw ->
            card = card.copy(chatModelConfigId = raw.trim().takeIf { it.isNotEmpty() })
            changed += "chatModelConfigId"
        }
        (params["chat_model_index"] as? JsonPrimitive)?.intOrNull?.let { index ->
            card = card.copy(chatModelIndex = index.coerceAtLeast(0))
            changed += "chatModelIndex"
        }
        params.text("memory_profile_binding_mode")?.let { raw ->
            card = card.copy(memoryProfileBindingMode = CharacterCardBinding.normalizeMemoryMode(raw))
            changed += "memoryProfileBindingMode"
        }
        params.text("memory_profile_id")?.let { raw ->
            card = card.copy(memoryProfileId = raw.trim().takeIf { it.isNotEmpty() })
            changed += "memoryProfileId"
        }
        (params["tool_access_config"] as? JsonObject)?.let { raw ->
            card = card.copy(
                toolAccessConfig = CharacterCardToolAccess(
                    enabled = (raw["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    allowedBuiltinTools = raw.stringList("allowedBuiltinTools").orEmpty(),
                    allowedPackages = raw.stringList("allowedPackages").orEmpty(),
                    allowedSkills = raw.stringList("allowedSkills").orEmpty(),
                    allowedMcpServers = raw.stringList("allowedMcpServers").orEmpty(),
                )
            )
            changed += "toolAccessConfig"
        }

        if (requireName && card.name.isBlank()) {
            throw PluginHostException("PARAM_INVALID", "创建角色卡需要 name")
        }
        return card to changed.distinct()
    }

    private fun cardJson(card: CharacterCard): JsonObject = buildJsonObject {
        put("id", card.id)
        put("name", card.name)
        put("description", card.description)
        put("avatar", card.avatar)
        put("characterSetting", card.characterSetting)
        put("openingStatement", card.openingStatement)
        put("otherContentChat", card.otherContentChat)
        put("otherContentVoice", card.otherContentVoice)
        put("tags", stringArray(card.tags))
        put("attachedTagIds", stringArray(card.attachedTagIds))
        put("advancedCustomPrompt", card.advancedCustomPrompt)
        put("marks", card.marks)
        put("chatModelBindingMode", card.chatModelBindingMode)
        if (card.chatModelConfigId.isNullOrBlank()) {
            put("chatModelConfigId", null as String?)
        } else {
            put("chatModelConfigId", card.chatModelConfigId)
        }
        put("chatModelIndex", card.chatModelIndex)
        put("memoryProfileBindingMode", card.memoryProfileBindingMode)
        if (card.memoryProfileId.isNullOrBlank()) {
            put("memoryProfileId", null as String?)
        } else {
            put("memoryProfileId", card.memoryProfileId)
        }
        put("toolAccessConfig", buildJsonObject {
            put("enabled", card.toolAccessConfig.enabled)
            put("allowedBuiltinTools", stringArray(card.toolAccessConfig.allowedBuiltinTools))
            put("allowedPackages", stringArray(card.toolAccessConfig.allowedPackages))
            put("allowedSkills", stringArray(card.toolAccessConfig.allowedSkills))
            put("allowedMcpServers", stringArray(card.toolAccessConfig.allowedMcpServers))
        })
        put("worldBookEntryCount", card.worldBookEntries.size)
        put("isDefault", false)
        put("createdAt", card.createdAt)
        put("updatedAt", card.updatedAt)
    }

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.requireId(): String =
        text("character_card_id") ?: text("characterCardId") ?: text("id")
        ?: throw PluginHostException("PARAM_MISSING", "missing parameter: character_card_id")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** 数组或逗号串都收，插件两种写法都有（照抄 Operit 的 tags 处理）。 */
    private fun JsonObject.stringList(key: String): List<String>? {
        val raw = this[key] ?: return null
        val values = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull.orEmpty().split(',')
            is JsonObject -> raw.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> return null
        }
        return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun notFound(id: String): PluginHostException =
        PluginHostException("NOT_FOUND", "角色卡不存在：$id")

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private companion object {
        /** 单字段上限：设定文本可以很长，但一条请求不该能把进程内存打满。 */
        const val MAX_FIELD_CHARS = 200_000
    }
}
