package com.aharou.core.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 自定义皮肤的序列化器：持久化与导入导出共用；字段缺失或多余都不该让整份数据报废。 */
val CustomSkinJson = Json { ignoreUnknownKeys = true }

/**
 * 用户自己调出来的一套皮肤。
 *
 * 只存「主色 +（可选）辅色 + 名字」，其余长相由 [customSkinTokens] 现算——
 * 所以皮肤列表里自定义项与预设项是同一类东西，切换、预览、删除走同一套逻辑。
 * 这个类型同时是导入导出 JSON 的载体。
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class CustomSkin(
    val id: String = "custom-" + Uuid.random().toString(),
    val name: String = "",
    val primaryArgb: Long = 0xFF4F8CFF,
    val accentArgb: Long? = null
) {

    fun primaryColor(): Color = Color(primaryArgb)

    fun accentColor(): Color? = accentArgb?.let { Color(it) }

    fun toThemePreset(): AppThemePreset =
        AppThemePreset.custom(
            id = id,
            name = name,
            skin = customSkinTokens(primaryColor(), accentColor())
        )
}
