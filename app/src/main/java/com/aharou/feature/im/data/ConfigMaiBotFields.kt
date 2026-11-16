package com.aharou.feature.im.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.im.data.repository.MaiBotSettingsRepository.Mode
import com.aharou.feature.im.domain.maibot.MaiBotModeController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/** MaiBot 工作模式 → 配置通道。 */
@Singleton
class ConfigMaiBotFields @Inject constructor(
    private val controller: MaiBotModeController,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(ModeCollection())
    }

    private inner class ModeCollection : ConfigCollection {
        override val basePath = "maibot"
        override val displayName = "MaiBot"
        override val description =
            "日常模式由 MaiBot 自己回消息；工作模式把消息交给 Aharou 处理，会话会进软件。"
        override val addable = false
        override val removable = false

        override fun childIds(): List<String> = listOf("mode")

        override fun fields(forId: String): List<ConfigField> {
            if (forId != "mode") return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.mode",
                    displayName = "工作模式",
                    description = "daily=麦麦自己聊；work=消息进软件由 Aharou 处理。",
                    valueSchema = ConfigSchema.StrEnum(listOf("daily", "work")),
                    risk = ConfigRisk.NORMAL,
                    reader = {
                        val mode = runBlocking { controller.current() }
                        ConfigValue.Str(if (mode == Mode.WORK) "work" else "daily")
                    },
                    writer = { value ->
                        val text = (value as ConfigValue.Str).value
                        val mode = if (text == "work") Mode.WORK else Mode.DAILY
                        runBlocking { controller.setMode(mode) }
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String =
            throw ConfigError.PermissionDenied("不支持新增")

        override fun remove(id: String) =
            throw ConfigError.PermissionDenied("不支持删除")
    }
}
