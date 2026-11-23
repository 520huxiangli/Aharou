package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `music.*` 的参数解析。插件会同时送来 Operit 的 snake_case（shim 按 Operit 形状拼好）
 * 与少量 camelCase 写法，这里统一收口，避免每个方法各写一遍类型判断。
 */

internal fun JsonObject.musicString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.musicDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

internal fun JsonObject.musicLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

internal fun JsonObject.musicInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

internal fun JsonObject.musicFlag(key: String): Boolean? =
    when ((this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

internal fun JsonObject.musicItems(): JsonArray? = this["items"] as? JsonArray

/** 解析 `source_type`；未知值直接报错，不猜成 path。 */
internal fun parseMusicSourceType(raw: String?): PluginMusicPlayer.TrackSourceType =
    when (raw?.trim()?.lowercase()) {
        "path", "file", "local" -> PluginMusicPlayer.TrackSourceType.PATH
        "url", "http", "https", "remote" -> PluginMusicPlayer.TrackSourceType.URL
        "uri", "content" -> PluginMusicPlayer.TrackSourceType.URI
        null, "" -> throw PluginHostException("PARAM_INVALID", "需要 source_type：path / url / uri")
        else -> throw PluginHostException("PARAM_INVALID", "不支持的 source_type：$raw（path / url / uri）")
    }

/** 从宿主参数里抽音源；单曲与队列共用一套校验。 */
internal fun musicTrackFrom(params: JsonObject): PluginMusicPlayer.Track {
    val source = params.musicString("source")
        ?: throw PluginHostException("PARAM_INVALID", "需要 source")
    val sourceType = parseMusicSourceType(
        params.musicString("source_type") ?: params.musicString("sourceType")
    )
    return PluginMusicPlayer.Track(
        source = source,
        sourceType = sourceType,
        title = params.musicString("title"),
        artist = params.musicString("artist")
    )
}
