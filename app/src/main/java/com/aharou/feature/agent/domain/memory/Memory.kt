package com.aharou.feature.agent.domain.memory

import java.io.File

/**
 * 解析后的单个 Memory 模型。
 *
 * @param name 记忆名称（供大模型调用的唯一标识，通常对应文件名不含扩展名）
 * @param description 记忆描述（一句话摘要，注入到系统提示词中）
 * @param scope 记忆的作用域（GLOBAL 或 PROJECT）
 * @param file 记忆对应的本地文件
 * @param content 记忆正文（剥离 Frontmatter 后的详细内容）
 * @param type 记忆分类，决定是否进注入清单（见 [MemoryType]）
 * @param keywords 检索关键词：只用于 `memory(action=search)` 匹配，**不进清单**，
 *   避免为了提升检索命中率反而把注入撑肥。
 * @param expiresAt 短期记忆的过期时刻（epoch millis）；null 表示不过期。
 *   过期后不再进注入清单（文件保留，仍可 read / edit），无需人工清理。
 * @param version 内容版本（文件全文的 SHA-256 前 12 位）。写入时作为乐观锁的比对基准：
 *   内容没变版本就不变；内容被别处改过版本就变。没有文件（内存构造）时为空串。
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val type: MemoryType = MemoryType.FACT,
    val keywords: List<String> = emptyList(),
    val expiresAt: Long? = null,
    /**
     * 待确认：与另一条记忆的说法冲突、暂时两条并存，等使用者裁决。
     * 由整理器判为冲突时打上；在设置页保存这条记忆即视为已确认（标记清除）。
     */
    val pending: Boolean = false,
    val version: String = ""
)

enum class MemoryScope {
    GLOBAL, PROJECT
}

/**
 * 记忆分类。决定条目「值不值得常驻提示词」——这是清单瘦身的主要手段：
 * 跨会话仍然成立的知识进清单，一次性事件只留文件供按需读取。
 */
enum class MemoryType(val key: String) {
    /** 身份与长期约定（CORE / GLOBAL 类）。永远保留，永不遗忘。 */
    IDENTITY("identity"),

    /** 用户偏好。 */
    PREFERENCE("preference"),

    /** 做事方式 / 流程约定。 */
    WORKFLOW("workflow"),

    /** 一般事实。 */
    FACT("fact"),

    /** 一次性事件（某次测试结果、某天的排查记录）。**不进注入清单**。 */
    EVENT("event");

    /** 是否注入系统提示词清单。事件类只留档，需要时靠 search / read 取回。 */
    val injected: Boolean get() = this != EVENT

    companion object {
        fun from(raw: String?): MemoryType? =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
    }
}
