package com.aharou.feature.browser.data

import android.content.Context
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.browser.BrowserEruda
import com.aharou.feature.browser.BrowserTabPool
import com.aharou.feature.browser.UserAgentProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器设置 → 配置通道。
 *
 * 全部字段落在同一个 SharedPreferences 文件 `browser_prefs`——由 [BrowserTabPool]（User-Agent /
 * 自定义 UA / 全局自定义视口 / 空闲回收）与 [BrowserEruda]（页内开发者工具）共用，是这些设置
 * 唯一的持久化事实源。本文件只读写这份存储，不触碰 [BrowserTabPool] 与 BrowserUseManager
 * （二者在架构门禁的行数基线里，只读不改）。
 *
 * 因此改动在下次池初始化 / 页面重载后生效：设置页里的即时应用由
 * [com.aharou.feature.browser.presentation.BrowserSettingsSheet] 承担，这里只保证「值真的落盘」。
 */
@Singleton
class ConfigBrowserFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "browser.user_agent_profile",
                displayName = "User-Agent 档位",
                description = "浏览器 UA 档位：default / android_phone / android_tablet / desktop_pc / iphone / ipad / custom" +
                    "（另有两个历史档位 mobile_chrome、desktop_chrome）。未设置时按 mobile_chrome 处理。",
                valueSchema = ConfigSchema.StrEnum(UserAgentProfile.entries.map { it.value }),
                revertable = true,
                reader = {
                    val raw = prefs.getString(KEY_UA_PROFILE, null)
                    val profile = raw?.let { runCatching { UserAgentProfile.valueOf(it) }.getOrNull() }
                        ?: UserAgentProfile.MOBILE_CHROME
                    ConfigValue.Str(profile.value)
                },
                writer = { v ->
                    val value = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val profile = UserAgentProfile.fromString(value)
                        ?: throw ConfigError.InvalidValue("未知 UA 档位：$value")
                    prefs.edit().putString(KEY_UA_PROFILE, profile.name).apply()
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "browser.custom_user_agent",
                displayName = "自定义 User-Agent",
                description = "当 UA 档位选 custom 时使用的完整 UA 字符串（如 Mozilla/5.0 …）；留空表示未设置。",
                valueSchema = ConfigSchema.Str(maxLength = 512),
                revertable = true,
                reader = { ConfigValue.Str(prefs.getString(KEY_CUSTOM_UA, null).orEmpty()) },
                writer = { v ->
                    val ua = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    prefs.edit().putString(KEY_CUSTOM_UA, ua).apply()
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "browser.viewport_width",
                displayName = "自定义视口宽度",
                description = "自定视口的 CSS 像素宽度。0 = 用 UA 档位默认宽度（即不覆盖）；有效自定义范围 200~4096。" +
                    "需宽度与高度同时大于 0 才生效。默认 0。",
                valueSchema = ConfigSchema.Int(min = 0, max = VIEWPORT_MAX),
                revertable = true,
                reader = { ConfigValue.Int(prefs.getInt(BrowserTabPool.PREF_GLOBAL_VIEWPORT_WIDTH, 0)) },
                writer = { v ->
                    val width = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("int")
                    prefs.edit().putInt(BrowserTabPool.PREF_GLOBAL_VIEWPORT_WIDTH, width).apply()
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "browser.viewport_height",
                displayName = "自定义视口高度",
                description = "自定视口的 CSS 像素高度。0 = 用 UA 档位默认高度（即不覆盖）；有效自定义范围 200~4096。" +
                    "需宽度与高度同时大于 0 才生效。默认 0。",
                valueSchema = ConfigSchema.Int(min = 0, max = VIEWPORT_MAX),
                revertable = true,
                reader = { ConfigValue.Int(prefs.getInt(BrowserTabPool.PREF_GLOBAL_VIEWPORT_HEIGHT, 0)) },
                writer = { v ->
                    val height = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("int")
                    prefs.edit().putInt(BrowserTabPool.PREF_GLOBAL_VIEWPORT_HEIGHT, height).apply()
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "browser.idle_timeout_minutes",
                displayName = "空闲标签回收（分钟）",
                description = "非使用中的标签空闲该时长后被自动回收。范围 1~240。默认 15。",
                valueSchema = ConfigSchema.Int(
                    min = BrowserTabPool.MIN_IDLE_TIMEOUT_MINUTES,
                    max = BrowserTabPool.MAX_IDLE_TIMEOUT_MINUTES,
                ),
                revertable = true,
                reader = {
                    ConfigValue.Int(
                        prefs.getInt(
                            BrowserTabPool.PREF_IDLE_TIMEOUT_MINUTES,
                            BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES,
                        )
                    )
                },
                writer = { v ->
                    val minutes = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("int")
                    prefs.edit().putInt(BrowserTabPool.PREF_IDLE_TIMEOUT_MINUTES, minutes).apply()
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "browser.devtools_enabled",
                displayName = "页内开发者工具",
                description = "在页面里注入 Eruda 开发者工具（同时打开 WebView 远程调试）。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(prefs.getBoolean(KEY_DEVTOOLS, false)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    prefs.edit().putBoolean(KEY_DEVTOOLS, on).apply()
                },
            ),
        )
    }

    private companion object {
        /** 与 [BrowserTabPool] / [BrowserEruda] 共用的 prefs 文件名。 */
        const val PREFS = "browser_prefs"

        /** [BrowserTabPool] init 读取的 UA 档位键（存的是枚举名）。 */
        const val KEY_UA_PROFILE = "user_agent_profile"

        /** [BrowserTabPool] init 读取的自定义 UA 键。 */
        const val KEY_CUSTOM_UA = "custom_user_agent"

        /** [BrowserEruda] 的开关键。 */
        const val KEY_DEVTOOLS = "browser_devtools_enabled"

        /** 设置页自定义视口的上限（[com.aharou.feature.browser.presentation.BrowserSettingsSheet] 同值）。 */
        const val VIEWPORT_MAX = 4096
    }
}
