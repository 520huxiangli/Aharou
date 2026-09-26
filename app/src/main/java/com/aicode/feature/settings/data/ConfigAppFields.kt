package com.aicode.feature.settings.data

import android.content.Context
import com.aicode.core.config.ConfigError
import com.aicode.core.config.ConfigRegistry
import com.aicode.core.config.ConfigSchema
import com.aicode.core.config.ConfigValue
import com.aicode.core.config.fields.ClosureField
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置 → 配置通道（Minis 式：Agent 改自己的设置，不需要无障碍 / Shizuku 等任何系统权限）。
 *
 * 与 [com.aicode.core.config.ConfigBuiltins]（soul/memory 等文件型字段）互补：这里接
 * DataStore 支撑的应用设置。注册在 Application.onCreate 完成（ConfigRegistry.init 之后）。
 */
@Singleton
class ConfigAppFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val languageSettings: LanguageSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "app.language",
                displayName = "应用语言",
                description = "界面语言：auto（跟随系统）/ zh / en。",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                revertable = true,
                // 同步读（attachBaseContext 同款读取路径），避免 runBlocking。
                reader = { ConfigValue.Str(languageSettings.getLanguageSync() ?: "auto") },
                writer = { v ->
                    val tag = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    runBlocking { languageSettings.setLanguage(if (tag == "auto") null else tag) }
                },
            ),
        )
    }
}
