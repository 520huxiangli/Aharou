package com.aharou.feature.plugin.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * `contributes.hooks` 的解析：生命周期 hook 与聊天内联标签（`event: "chat.tag"`）同源，
 * 但后者多一组 tag/height/maxHeight 字段、也不参与事件派发，所以在一次遍历里拆成两份。
 *
 * 独立成文件是为了让 [PluginManifestParser] 不再增长：它是行数棘轮基线里的文件，
 * 新增 hook 形态时把校验写在这里，主解析器只留一行调用。
 */
internal object PluginHookParser {
    internal class Result(val hooks: List<PluginHookRef>, val chatTags: List<PluginChatTagRef>)

    private val HOOK_KEYS = setOf("id", "event", "handler", "mode")

    /** 只有 `event: "chat.tag"` 的条目可以多带这三个字段。 */
    private val CHAT_TAG_KEYS = setOf("tag", "height", "maxHeight")

    /** 内联标签名：小写字母/数字/下划线，与 XML 标签名同形，也是切分器的匹配键。 */
    private val CHAT_TAG_NAME = Regex("^[a-z0-9_]{1,32}$")

    /** 聊天内联标签声明的高度单位是 dp；下限防止插件声明一个点不到的高度。 */
    private const val MIN_CHAT_TAG_HEIGHT = 24
    private const val MAX_CHAT_TAG_HEIGHT = 2000

    /**
     * 缺省视为空列表。普通条目只允许 id/event/handler/mode；`event: "chat.tag"` 另行允许
     * tag/height/maxHeight，并把结果放进 [Result.chatTags]。
     */
    fun read(raw: JsonElement?, path: String, error: (PluginErrorCode, String) -> Unit): Result? {
        if (raw == null) return Result(emptyList(), emptyList())
        val array = raw as? JsonArray ?: return null.also { error(PluginErrorCode.FIELD_INVALID, path) }
        if (array.size > PluginManifestParser.MAX_IDS_PER_TYPE) {
            return null.also { error(PluginErrorCode.FIELD_INVALID, path) }
        }
        val hooks = mutableListOf<PluginHookRef>()
        val chatTags = mutableListOf<PluginChatTagRef>()
        var ok = true
        array.forEachIndexed { index, element ->
            val itemPath = "$path[$index]"
            val item = element as? JsonObject
            if (item == null) {
                error(PluginErrorCode.FIELD_INVALID, itemPath)
                ok = false
                return@forEachIndexed
            }
            if (item["event"]?.stringOrNull() == PluginHookEvents.CHAT_TAG) {
                val chatTag = readChatTag(item, itemPath, error)
                when {
                    chatTag == null -> ok = false
                    chatTags.any { it.tag == chatTag.tag } -> {
                        error(PluginErrorCode.DUPLICATE_ID, "$path.${chatTag.tag}")
                        ok = false
                    }
                    else -> chatTags += chatTag
                }
                return@forEachIndexed
            }
            item.keys.filter { it !in HOOK_KEYS }.forEach {
                error(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                ok = false
            }
            val id = item["id"]?.stringOrNull()?.takeIf { PluginManifestParser.LOCAL_ID.matches(it) }
            val event = item["event"]?.stringOrNull()?.takeIf { it in PluginHookEvents.ALL }
            val handler = item["handler"]?.stringOrNull()?.trim()
                ?.takeIf { PluginManifestParser.HANDLER_NAME.matches(it) }
            val mode = when (val rawMode = item["mode"]) {
                null -> PluginHookModes.OBSERVE
                else -> rawMode.stringOrNull()?.takeIf { it in PluginHookModes.ALL } ?: run {
                    error(PluginErrorCode.FIELD_INVALID, "$itemPath.mode")
                    ok = false
                    null
                }
            }
            if (id == null) {
                error(PluginErrorCode.FIELD_INVALID, "$itemPath.id")
                ok = false
            }
            if (event == null) {
                error(PluginErrorCode.FIELD_INVALID, "$itemPath.event")
                ok = false
            }
            if (handler == null) {
                error(PluginErrorCode.FIELD_INVALID, "$itemPath.handler")
                ok = false
            }
            if (event != null && mode != null) {
                val expectedMode = when (event) {
                    in PluginHookEvents.TRANSFORM -> PluginHookModes.TRANSFORM
                    in PluginHookEvents.REVIEW -> PluginHookModes.REVIEW
                    else -> PluginHookModes.OBSERVE
                }
                if (mode != expectedMode) {
                    error(PluginErrorCode.FIELD_INVALID, "$itemPath.mode")
                    ok = false
                }
            }
            if (id != null && event != null && handler != null && mode != null) {
                if (hooks.any { it.id == id }) {
                    error(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                    ok = false
                } else {
                    hooks += PluginHookRef(id, event, handler, mode)
                }
            }
        }
        return if (ok) Result(hooks, chatTags) else null
    }

    /**
     * 聊天内联标签声明。标签名与 handler 都必需；标签名归一成小写后入库，
     * 同一插件内重复标签名直接拒绝（同一段正文只应该有一个接管者）。
     */
    private fun readChatTag(
        item: JsonObject,
        path: String,
        error: (PluginErrorCode, String) -> Unit
    ): PluginChatTagRef? {
        var ok = true
        item.keys.filter { it !in HOOK_KEYS && it !in CHAT_TAG_KEYS }.forEach {
            error(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
            ok = false
        }
        // chat.tag 不是事件派发，只允许 observe；写 transform / review 说明作者误解了它的用途。
        if (item["mode"] != null && item["mode"]?.stringOrNull() != PluginHookModes.OBSERVE) {
            error(PluginErrorCode.FIELD_INVALID, "$path.mode")
            ok = false
        }
        val id = item["id"]?.stringOrNull()?.takeIf { PluginManifestParser.LOCAL_ID.matches(it) }
        val tag = item["tag"]?.stringOrNull()?.trim()?.lowercase()?.takeIf { CHAT_TAG_NAME.matches(it) }
        val handler = item["handler"]?.stringOrNull()?.trim()
            ?.takeIf { PluginManifestParser.HANDLER_NAME.matches(it) }
        val heightDp = readChatTagHeight(item["height"], "$path.height", error) { ok = false }
        val maxHeightDp = readChatTagHeight(item["maxHeight"], "$path.maxHeight", error) { ok = false }
        if (id == null) { error(PluginErrorCode.FIELD_INVALID, "$path.id"); ok = false }
        if (tag == null) { error(PluginErrorCode.FIELD_INVALID, "$path.tag"); ok = false }
        if (handler == null) { error(PluginErrorCode.FIELD_INVALID, "$path.handler"); ok = false }
        if (heightDp != null && maxHeightDp != null && heightDp > maxHeightDp) {
            error(PluginErrorCode.FIELD_INVALID, "$path.height")
            ok = false
        }
        if (!ok || id == null || tag == null || handler == null) return null
        return PluginChatTagRef(id, tag, handler, heightDp, maxHeightDp)
    }

    private fun readChatTagHeight(
        raw: JsonElement?,
        path: String,
        error: (PluginErrorCode, String) -> Unit,
        onInvalid: () -> Unit
    ): Int? {
        if (raw == null) return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (value == null || value !in MIN_CHAT_TAG_HEIGHT..MAX_CHAT_TAG_HEIGHT) {
            error(PluginErrorCode.FIELD_INVALID, path)
            onInvalid()
            return null
        }
        return value
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
