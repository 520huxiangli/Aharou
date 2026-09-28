package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 各角色所用模型 → 配置通道。
 *
 * 五个角色各存一对 (providerId, model)。对外统一成单一路径、单字符串值，
 * 格式 `providerId:model`，空串表示未设置（回退默认/全局供应商）。providerId
 * 是 UUID 不含冒号，按第一个冒号切分即可，后面的冒号属于模型 id。
 */
@Singleton
class ConfigModelRoleFields @Inject constructor(
    private val defaultModel: DefaultModelSettingsRepository,
    private val titleModel: TitleModelSettingsRepository,
    private val compactionModel: CompactionModelSettingsRepository,
    private val visionModel: VisionModelSettingsRepository,
    private val imageGenModel: ImageGenModelSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            roleField(
                path = "model.roles.default",
                label = "新会话默认模型",
                desc = "空会话切换模型时写入；新建会话按它绑定。留空则回退全局激活供应商。",
                read = { runBlocking { defaultModel.providerIdFlow.first() to defaultModel.modelFlow.first() } },
                write = { p, m -> runBlocking { defaultModel.setDefaultModel(p, m) } },
                clear = { runBlocking { defaultModel.clear() } },
            ),
        )
        registry.register(
            roleField(
                path = "model.roles.title",
                label = "标题生成模型",
                desc = "用于给会话自动起标题。留空则跟随默认模型。",
                read = { runBlocking { titleModel.providerIdFlow.first() to titleModel.modelFlow.first() } },
                write = { p, m -> runBlocking { titleModel.setTitleModel(p, m) } },
                clear = { runBlocking { titleModel.setTitleModel("", "") } },
            ),
        )
        registry.register(
            roleField(
                path = "model.roles.compaction",
                label = "上下文压缩模型",
                desc = "会话历史压缩时使用。留空则跟随默认模型。",
                read = { runBlocking { compactionModel.providerIdFlow.first() to compactionModel.modelFlow.first() } },
                write = { p, m -> runBlocking { compactionModel.setCompactionModel(p, m) } },
                clear = { runBlocking { compactionModel.setCompactionModel("", "") } },
            ),
        )
        registry.register(
            roleField(
                path = "model.roles.vision",
                label = "图片识别模型",
                desc = "识别图片内容时使用。留空则跟随默认模型。",
                read = { runBlocking { visionModel.providerIdFlow.first() to visionModel.modelFlow.first() } },
                write = { p, m -> runBlocking { visionModel.setVisionModel(p, m) } },
                clear = { runBlocking { visionModel.setVisionModel("", "") } },
            ),
        )
        registry.register(
            roleField(
                path = "model.roles.image_gen",
                label = "绘图模型",
                desc = "生成图片时使用。留空则跟随默认模型。",
                read = { runBlocking { imageGenModel.providerIdFlow.first() to imageGenModel.modelFlow.first() } },
                write = { p, m -> runBlocking { imageGenModel.setImageGenModel(p, m) } },
                clear = { runBlocking { imageGenModel.setImageGenModel("", "") } },
            ),
        )
    }

    private fun roleField(
        path: String,
        label: String,
        desc: String,
        read: () -> Pair<String, String>,
        write: (String, String) -> Unit,
        clear: () -> Unit,
    ) = ClosureField(
        path = path,
        displayName = label,
        description = desc,
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = {
            val (providerId, model) = read()
            ConfigValue.Str(if (providerId.isBlank() || model.isBlank()) "" else "$providerId:$model")
        },
        writer = { v ->
            val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            if (s.isBlank()) {
                clear()
            } else {
                val sep = s.indexOf(':')
                if (sep <= 0 || sep == s.lastIndex) {
                    throw ConfigError.InvalidValue("格式应为 providerId:model")
                }
                write(s.substring(0, sep), s.substring(sep + 1))
            }
        },
    )
}
