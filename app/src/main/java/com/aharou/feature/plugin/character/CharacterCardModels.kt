package com.aharou.feature.plugin.character

import kotlinx.serialization.Serializable

/**
 * 角色卡数据模型，移植自 Operit（`AAswordman/Operit`，LGPL-3.0）
 * `data/model/CharacterCard.kt` 的 `CharacterCard` / `CharacterCardToolAccessConfig`。
 *
 * 与 `core/soul` 的 SOUL.md 是两套互相独立的东西，谁都不改谁：
 * - SOUL 是 App 自己的人格，进系统提示词、参与每次对话；
 * - 角色卡是插件数据（Operit 概念），由插件通过 `characterCard.*` 读写，
 *   Aharou 既不把它注入对话，也不允许它改写 SOUL。
 *
 * 字段名与 Operit 保持一致，插件按 Operit 写的代码可以直接搬过来。
 * 少数几条 Operit 用来驱动自家子系统的字段（工具白名单、记忆档案、模型绑定）在这里只是
 * 随卡保存的元数据：Aharou 没有对应的执行链，原样存取以保证导出后往返不丢内容。
 */
@Serializable
data class CharacterCard(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /** 头像：URL、本地路径或 emoji 文本都可以，Aharou 只存不解析。 */
    val avatar: String = "",
    /** 角色设定（系统提示）。 */
    val characterSetting: String = "",
    /** 开场白。 */
    val openingStatement: String = "",
    /** 其他内容（聊天）：对话示例、系统提示、历史后置指令、备用问候语。 */
    val otherContentChat: String = "",
    /** 其他内容（语音）。 */
    val otherContentVoice: String = "",
    /** 标签名。Aharou 没有 Operit 的提示词标签库，这里只保留名字。 */
    val tags: List<String> = emptyList(),
    /** Operit 标签 id。仅随卡保存，Aharou 侧不解析。 */
    val attachedTagIds: List<String> = emptyList(),
    /** 高级自定义提示。 */
    val advancedCustomPrompt: String = "",
    /** 备注（不会被拼进提示词）。 */
    val marks: String = "",
    val chatModelBindingMode: String = CharacterCardBinding.FOLLOW_GLOBAL,
    val chatModelConfigId: String? = null,
    val chatModelIndex: Int = 0,
    val memoryProfileBindingMode: String = CharacterCardBinding.FOLLOW_GLOBAL,
    val memoryProfileId: String? = null,
    val toolAccessConfig: CharacterCardToolAccess = CharacterCardToolAccess(),
    /** 从 Tavern 卡导入的世界书条目；导出时原样写回 `character_book`。 */
    val worldBookEntries: List<CharacterCardWorldBookEntry> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class CharacterCardToolAccess(
    val enabled: Boolean = false,
    val allowedBuiltinTools: List<String> = emptyList(),
    val allowedPackages: List<String> = emptyList(),
    val allowedSkills: List<String> = emptyList(),
    val allowedMcpServers: List<String> = emptyList(),
)

@Serializable
data class CharacterCardWorldBookEntry(
    val name: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
)

/** 绑定模式取值与 Operit 一致；未识别的值一律回落到「跟随全局」。 */
object CharacterCardBinding {
    const val FOLLOW_GLOBAL = "FOLLOW_GLOBAL"
    const val FIXED_CONFIG = "FIXED_CONFIG"
    const val FIXED_PROFILE = "FIXED_PROFILE"

    fun normalizeModelMode(raw: String?): String =
        if (raw?.trim()?.uppercase() == FIXED_CONFIG) FIXED_CONFIG else FOLLOW_GLOBAL

    fun normalizeMemoryMode(raw: String?): String =
        if (raw?.trim()?.uppercase() == FIXED_PROFILE) FIXED_PROFILE else FOLLOW_GLOBAL
}

/** 单张卡在磁盘上的形态；`schema` 用于将来改结构时识别旧文件。 */
@Serializable
internal data class CharacterCardSnapshot(
    val schema: Int = CHARACTER_CARD_SCHEMA,
    val activeCharacterCardId: String? = null,
    val cards: List<CharacterCard> = emptyList(),
)

internal const val CHARACTER_CARD_SCHEMA = 1
