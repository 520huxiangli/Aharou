package com.aharou.feature.agent.domain.skill.market

/**
 * 技能分类的固定词表（照 WorkBuddy 的分类来），模型打标只能用这些词。
 *
 * 顺序即市场页分类 chip 的展示顺序；[OTHER] 固定排最后。
 */
object SkillCategories {
    const val OTHER = "其它"

    val ORDER: List<String> = listOf(
        "办公效率",
        "内容创作",
        "开发编程",
        "移动开发",
        "文档处理",
        "数据分析",
        "设计多媒体",
        "AI Agent",
        "知识管理",
        "信息与研究",
        "通讯与社交",
        "财经与商业",
        "生活服务",
        OTHER,
    )

    private val allowed = ORDER.toSet()

    /** 把模型给的分类收敛到词表内：去过重、去未知词，最多留 3 个；空则归「其它」。 */
    fun normalize(raw: List<String>): List<String> {
        val kept = raw.asSequence()
            .map { it.trim() }
            .filter { it in allowed }
            .distinct()
            .take(3)
            .toList()
        return kept.ifEmpty { listOf(OTHER) }
    }
}
