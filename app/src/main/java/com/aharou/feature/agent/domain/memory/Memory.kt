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
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val type: MemoryType = MemoryType.FACT,
    val keywords: List<String> = emptyList()
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
