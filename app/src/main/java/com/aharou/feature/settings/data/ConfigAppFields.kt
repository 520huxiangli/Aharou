package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.R
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreEnumField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.feature.agent.domain.skill.PromptLanguageSettings
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.LanguageSettingsRepository
import com.aharou.feature.settings.data.repository.StartupSessionMode
import com.aharou.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aharou.feature.settings.data.repository.UpdateChannel
import com.aharou.feature.settings.data.repository.UpdateCheckSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置 → 配置通道（Minis 式：Agent 改自己的设置，不需要无障碍 / Shizuku 等任何系统权限）。
 *
 * 与 [com.aharou.core.config.ConfigBuiltins]（soul/memory 等文件型字段）互补：这里接
 * DataStore 支撑的应用设置。注册在 Application.onCreate 完成（ConfigRegistry.init 之后）。
 *
 * 本文件只管 `app.*`；界面外观、编辑器、终端、网络等主题见 [ConfigAppearanceFields] 与
 * [ConfigNetworkFields]。
 */
@Singleton
class ConfigAppFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val languageSettings: LanguageSettingsRepository,
    private val generalSettings: GeneralSettingsRepository,
    private val updateCheckSettings: UpdateCheckSettingsRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val promptLanguageSettings: PromptLanguageSettings,
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

        registry.register(
            ClosureField(
                path = "app.prompt_language",
                displayName = context.getString(R.string.settings_prompt_language),
                description = context.getString(R.string.settings_prompt_language_desc),
                valueSchema = ConfigSchema.StrEnum(PromptLanguageSettings.VALUES),
                revertable = true,
                reader = { ConfigValue.Str(promptLanguageSettings.get()) },
                writer = { v ->
                    val tag = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    promptLanguageSettings.set(tag)
                },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.auto_remove_stale_models",
                displayName = "自动清理失效模型",
                description = "拉取模型列表后，自动移除远端已不存在的本地模型。默认开启。",
                flow = generalSettings.autoRemoveStaleModelsFlow,
                setter = { generalSettings.setAutoRemoveStaleModels(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.privacy_masking_enabled",
                displayName = "出站脱敏",
                description = "请求发往供应商前，把 API Key、私钥、令牌、邮箱、身份证/银行卡号换成占位符，回复里再换回真值。占位符只存内存、不落盘。默认关闭（开启后模型看不到这些真值）。",
                flow = generalSettings.privacyMaskingEnabledFlow,
                setter = { generalSettings.setPrivacyMaskingEnabled(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.ocr_for_text_only_models",
                displayName = "为无视觉模型识别图片",
                description = "当前模型不支持图片输入时，用本机 OCR 把图片与影子屏截图转成文字再发给模型。全在本机识别、不上传。默认开启。",
                flow = generalSettings.ocrForTextOnlyModelsFlow,
                setter = { generalSettings.setOcrForTextOnlyModels(it) },
            ),
        )

        registry.register(
            DataStoreEnumField(
                path = "app.startup_session_mode",
                displayName = "启动进入的会话",
                description = "App 启动（含切换工作区）时进入哪个会话：new_session=复用当前工作区里未发过消息的空会话，否则新建；recent_session=直接打开最近更新的会话。",
                flow = generalSettings.startupSessionModeFlow.map { it.name.lowercase() },
                setter = { generalSettings.setStartupSessionMode(StartupSessionMode.valueOf(it.uppercase())) },
                cases = listOf("new_session", "recent_session"),
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.first_byte_timeout_sec",
                displayName = "首字超时（秒）",
                description = "发出请求后等待模型返回第一个字的时间上限，超过即判失败。默认 120 秒。",
                flow = generalSettings.firstByteTimeoutSecFlow,
                setter = { generalSettings.setFirstByteTimeoutSec(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.stream_idle_timeout_sec",
                displayName = "数据块间隔超时（秒）",
                description = "流式输出期间，两个数据块之间的最长等待时间，超过即判失败。默认 120 秒。",
                flow = generalSettings.streamIdleTimeoutSecFlow,
                setter = { generalSettings.setStreamIdleTimeoutSec(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.max_network_retries",
                displayName = "网络请求最大重试次数",
                description = "网络请求失败后的重试次数。默认 6。",
                flow = generalSettings.maxNetworkRetriesFlow,
                setter = { generalSettings.setMaxNetworkRetries(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.enter_to_send",
                displayName = "回车发送",
                description = "输入框里按回车即发送消息（关闭则回车换行）。",
                flow = generalSettings.enterToSendFlow,
                setter = { generalSettings.setEnterToSend(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.compaction_threshold_percent",
                displayName = "自动压缩阈值（%）",
                description = "上下文用量达到该百分比时自动压缩历史。默认 90。",
                flow = generalSettings.compactionThresholdPercentFlow,
                setter = { generalSettings.setCompactionThresholdPercent(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.sendfile_max_size_mb",
                displayName = "sendFile 单文件上限（MB）",
                description = "sendFile 工具一次能发送的文件大小上限，超过会被拒绝。默认 100。",
                flow = generalSettings.sendFileMaxSizeMbFlow,
                setter = { generalSettings.setSendFileMaxSizeMb(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.delete_external_workspace_sessions",
                displayName = "移除外部工作区时删聊天记录",
                description = "移除外部本地工作区时，是否一并删除它的聊天记录。默认关闭（保留记录）。",
                flow = generalSettings.deleteExternalWorkspaceSessionsFlow,
                setter = { generalSettings.setDeleteExternalWorkspaceSessions(it) },
            ),
        )

        registry.register(
            ClosureField(
                path = "app.auto_check_update",
                displayName = "自动检查更新",
                description = "启动时自动检查有没有新版本。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(updateCheckSettings.autoCheckEnabled) },
                writer = { v ->
                    updateCheckSettings.autoCheckEnabled =
                        (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "app.update_channel",
                displayName = "更新通道",
                description = "更新走哪条通道：stable=仅正式版 / latest=含测试版（beta 与 RC）。默认 stable。",
                valueSchema = ConfigSchema.StrEnum(listOf("stable", "latest")),
                revertable = true,
                reader = { ConfigValue.Str(updateCheckSettings.channel.name.lowercase()) },
                writer = { v ->
                    val tag = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    updateCheckSettings.channel = UpdateChannel.valueOf(tag.uppercase())
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "app.auto_download_update",
                displayName = "自动下载更新包",
                description = "检测到新版本后在后台自动下好，用户只需点一下安装。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(updateCheckSettings.autoDownloadEnabled) },
                writer = { v ->
                    updateCheckSettings.autoDownloadEnabled =
                        (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "app.auto_install_update",
                displayName = "自动安装更新",
                description = "更新包下载完就自动静默安装（需 Shizuku 授权）。覆盖安装会重启 App，且无法回退到旧版。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(updateCheckSettings.autoInstallEnabled) },
                writer = { v ->
                    updateCheckSettings.autoInstallEnabled =
                        (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "app.soft_compaction_threshold_percent",
                displayName = "软精简阈值（%）",
                description = "上下文用量达到该百分比时先精简历史里的超长工具输出，不调用摘要模型。默认 55。",
                flow = generalSettings.softCompactionThresholdPercentFlow,
                setter = { generalSettings.setSoftCompactionThresholdPercent(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.disable_safety_interception",
                displayName = "禁用安全拦截",
                description = "开启后 AUTO 模式下灾难性删除不再拦截、Shizuku 也不再逐次弹窗确认；仅作用于 AUTO 模式。默认关闭。",
                flow = toolSafetySettings.disableSafetyInterceptionFlow,
                setter = { toolSafetySettings.setDisableSafetyInterception(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
    }
}
