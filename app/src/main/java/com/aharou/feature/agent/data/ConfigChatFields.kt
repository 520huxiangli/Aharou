package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreDoubleField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.feature.agent.data.repository.ChatAppearance
import com.aharou.feature.agent.data.repository.ChatAppearanceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天气泡外观 → 配置通道。
 *
 * 全部存在 [ChatAppearanceRepository] 的 DataStore 里，且仓库按整份设置事务式写入
 * （[ChatAppearanceRepository.update]）——气泡项之间有联动，逐字段 setter 会写出中间态，
 * 所以每个字段的写回都走 `update { it.copy(...) }`。
 *
 * 颜色在仓库里是「打包成 32 位 ARGB 的 Long」，这里用 `#AARRGGBB` / `#RRGGBB` 十六进制串
 * 表达，空串表示跟随皮肤（对应仓库里的 null）。
 */
@Singleton
class ConfigChatFields @Inject constructor(
    private val repository: ChatAppearanceRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreBoolField(
                path = "chat.bubble_user_follows_skin",
                displayName = "用户气泡跟随皮肤",
                description = "用户气泡颜色跟随当前主题皮肤。默认开启。",
                flow = repository.settingsFlow.map { it.userBubbleFollowsSkin },
                setter = { value -> repository.update { it.copy(userBubbleFollowsSkin = value) } },
            ),
        )

        registry.register(
            colorField(
                path = "chat.bubble_user_color",
                name = "用户气泡颜色",
                desc = "自定义用户气泡颜色；空串表示跟随皮肤。默认跟随皮肤。",
                read = { it.userBubbleColor },
                write = { s, color -> s.copy(userBubbleColor = color) },
            ),
        )

        registry.register(
            DataStoreDoubleField(
                path = "chat.bubble_user_alpha",
                displayName = "用户气泡不透明度",
                description = "用户气泡不透明度，0.0（全透明）~ 1.0（不透明）。默认 1.0。",
                flow = repository.settingsFlow.map { it.userBubbleAlpha },
                setter = { value -> repository.update { it.copy(userBubbleAlpha = value) } },
                minValue = 0.0,
                maxValue = 1.0,
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "chat.bubble_assistant_enabled",
                displayName = "助手气泡",
                description = "给助手消息也套一层气泡（默认关闭，助手正文直接铺在页面底色上）。",
                flow = repository.settingsFlow.map { it.assistantBubbleEnabled },
                setter = { value -> repository.update { it.copy(assistantBubbleEnabled = value) } },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "chat.bubble_assistant_follows_skin",
                displayName = "助手气泡跟随皮肤",
                description = "助手气泡颜色跟随当前主题皮肤。默认开启。",
                flow = repository.settingsFlow.map { it.assistantBubbleFollowsSkin },
                setter = { value -> repository.update { it.copy(assistantBubbleFollowsSkin = value) } },
            ),
        )

        registry.register(
            colorField(
                path = "chat.bubble_assistant_color",
                name = "助手气泡颜色",
                desc = "自定义助手气泡颜色；空串表示跟随皮肤。默认跟随皮肤。",
                read = { it.assistantBubbleColor },
                write = { s, color -> s.copy(assistantBubbleColor = color) },
            ),
        )

        registry.register(
            DataStoreDoubleField(
                path = "chat.bubble_assistant_alpha",
                displayName = "助手气泡不透明度",
                description = "助手气泡不透明度，0.0（全透明）~ 1.0（不透明）。默认 1.0。",
                flow = repository.settingsFlow.map { it.assistantBubbleAlpha },
                setter = { value -> repository.update { it.copy(assistantBubbleAlpha = value) } },
                minValue = 0.0,
                maxValue = 1.0,
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "chat.bubble_corner_dp",
                displayName = "气泡圆角（dp）",
                description = "气泡圆角半径，0~28 dp。默认 14。",
                flow = repository.settingsFlow.map { it.bubbleCornerDp },
                setter = { value -> repository.update { it.copy(bubbleCornerDp = value) } },
                minValue = 0,
                maxValue = 28,
            ),
        )
    }

    /** 颜色字段：以 `#AARRGGBB` / `#RRGGBB` 十六进制进出，空串对应仓库里的「跟随皮肤」(null)。 */
    private fun colorField(
        path: String,
        name: String,
        desc: String,
        read: (ChatAppearance) -> Long?,
        write: (ChatAppearance, Long?) -> ChatAppearance,
    ): ConfigField = ClosureField(
        path = path,
        displayName = name,
        description = desc,
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = { ConfigValue.Str(runBlocking { repository.settingsFlow.first() }.let(read)?.let(::colorToHex).orEmpty()) },
        writer = { v ->
            val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            val color = parseColor(s)
            runBlocking { repository.update { write(it, color) } }
        },
    )

    private fun colorToHex(color: Long): String = String.format(Locale.US, "#%08X", color and 0xFFFFFFFFL)

    /** 空串 = 跟随皮肤（null）；否则按 6 位（补不透明 FF）或 8 位十六进制解析。 */
    private fun parseColor(raw: String): Long? {
        val hex = raw.trim().removePrefix("#")
        if (hex.isEmpty()) return null
        val value = when (hex.length) {
            6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it }
            8 -> hex.toLongOrNull(16)
            else -> null
        } ?: throw ConfigError.InvalidValue("颜色需为 #RRGGBB 或 #AARRGGBB 十六进制：$raw")
        return value and 0xFFFFFFFFL
    }
}
