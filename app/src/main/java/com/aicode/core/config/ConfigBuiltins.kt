package com.aicode.core.config

import android.content.Context
import com.aicode.core.config.fields.ClosureField
import com.aicode.core.soul.SoulBodyLimitCheck
import com.aicode.core.soul.SoulFile
import com.aicode.core.soul.SoulMetadata
import com.aicode.core.soul.SoulStore
import com.aicode.core.soul.SystemPromptBuilder

/**
 * 内置配置字段注册（自 OpenMinis 移植 · 分批扩充）。
 *
 * 第一批：`soul`（人格）主题——正文 / 名字 / 头像 / 语言。
 * 后续批次：envvars / models / groups / thinkingrules 等（接 AiCode 自有存储）。
 */
object ConfigBuiltins {

    fun registerInto(registry: ConfigRegistry, context: Context) {
        val appContext = context.applicationContext
        val memoryStore = com.aicode.core.memory.AharouMemoryStore(appContext)

        registry.register(
            ClosureField(
                path = "soul.body",
                displayName = "人格正文",
                description = "SOUL.md 正文——决定说话方式与性格。中文 ≤1600 字 / 英文 ≤1000 词；自动拒绝 prompt 注入句式。",
                valueSchema = ConfigSchema.Str(maxLength = 40000),
                access = ConfigAccess.READWRITE,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.body.orEmpty()) },
                writer = { v ->
                    val body = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val limit = SoulStore.isOverLimit(body)
                    if (limit.isOverLimit) {
                        val msg = when (limit) {
                            is SoulBodyLimitCheck.OverLimitChinese ->
                                "超中文上限（${limit.chars}/${limit.cap} 字）"
                            is SoulBodyLimitCheck.OverLimitEnglish ->
                                "超英文上限（${limit.words}/${limit.cap} 词）"
                            else -> "超限"
                        }
                        throw ConfigError.InvalidValue("人格正文$msg")
                    }
                    if (SystemPromptBuilder.containsInjectionPattern(body)) {
                        throw ConfigError.InvalidValue("正文包含 prompt 注入句式，已拒绝")
                    }
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(body = body))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.name",
                displayName = "名字",
                description = "聊天界面显示的名字（≤40 字）。",
                valueSchema = ConfigSchema.Str(maxLength = 40),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.name.orEmpty()) },
                writer = { v ->
                    val name = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(name = name.trim())))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.style",
                displayName = "风格",
                description = "回复风格（一句话）——如「病娇」「简洁、不用 markdown、不要表情包」；会作为硬性风格约束注入系统提示，优先级高于默认的语气设定。",
                valueSchema = ConfigSchema.Str(maxLength = 400),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.style.orEmpty()) },
                writer = { v ->
                    val style = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(style = style.trim())))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.icon",
                displayName = "头像",
                description = "emoji 或图片（data URI / 路径）；空 = 默认。",
                valueSchema = ConfigSchema.Str(maxLength = 200000),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.icon.orEmpty()) },
                writer = { v ->
                    val icon = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(icon = icon.trim())))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.lang",
                displayName = "语言",
                description = "回复语言：auto / zh / en。",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.lang ?: "auto") },
                writer = { v ->
                    val lang = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(lang = lang)))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.core",
                displayName = "核心档案",
                description = "长期记忆的核心档案（CORE.md）——会注入系统提示；也可直接编辑容器内 /root/.aharou/memory/CORE.md。",
                valueSchema = ConfigSchema.Str(maxLength = 20000),
                revertable = true,
                reader = { ConfigValue.Str(memoryStore.readCore().orEmpty()) },
                writer = { v ->
                    val text = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    memoryStore.writeCore(text)
                },
            ),
        )
    }
}
