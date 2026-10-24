package com.aharou.feature.agent.domain.memory

/**
 * 蒸馏产出的一条记忆要点。
 *
 * [name] 带 [DistilledEntryParser.NAME_PREFIX] 前缀，用来把「模型蒸馏出来的条目」与手工记忆
 * 区分开（裁剪超额条目时只动这一类）。
 */
data class DistilledEntry(
    val name: String,
    val description: String,
    val content: String,
    val type: MemoryType,
    val keywords: List<String>,
    val pending: Boolean,
)

/**
 * 把蒸馏模型输出的 Markdown 列表拆成一条条要点。
 *
 * 提示词要求每条一行：`- [preference] 正文{关键词: 甲, 乙}`。实际输出经常跑偏——漏分类、
 * 关键词写成全角冒号、行内多一句解释。所以这里只认「能被可靠拆开的部分」：拆不动的行整句
 * 当 fact 正文，**绝不丢弃**，丢一条就等于让模型这轮白干。
 */
object DistilledEntryParser {

    /** 蒸馏条目统一的名字前缀：裁剪上限、回喂旧要点都靠它识别。 */
    const val NAME_PREFIX = "蒸馏-"

    private const val MAX_NAME_CHARS = 16
    private const val MAX_DESC_CHARS = 60
    private const val MAX_CONTENT_CHARS = 2_000
    private const val MAX_KEYWORDS = 8

    private val BULLET = Regex("^\\s*[-*+]\\s+")
    private val TYPE_TAG = Regex("^\\[([A-Za-z\\u4e00-\\u9fa5]+)\\]\\s*")
    private val KEYWORD_TAIL = Regex("\\{[^{}]*}\\s*$")
    private val KEYWORD_SEPARATORS = Regex("[,，、;；]")
    private val NAME_NOISE = Regex("[\\s\\p{Punct}，。、；：！？（）【】《》“”‘’…]+")

    /** 解析整段输出；同一批里重名的只留第一条（同名会互相覆盖）。 */
    fun parse(answer: String): List<DistilledEntry> {
        val out = mutableListOf<DistilledEntry>()
        val seen = HashSet<String>()
        for (raw in answer.lineSequence()) {
            val entry = parseLine(raw) ?: continue
            if (!seen.add(entry.name)) continue
            out += entry
        }
        return out
    }

    private fun parseLine(raw: String): DistilledEntry? {
        val bullet = BULLET.find(raw) ?: return null
        var rest = raw.substring(bullet.range.last + 1).trim()
        if (rest.isEmpty()) return null

        val typeTag = TYPE_TAG.find(rest)
        val type = typeTag?.groupValues?.getOrNull(1)?.let { MemoryType.from(it) } ?: MemoryType.FACT
        if (typeTag != null) rest = rest.substring(typeTag.range.last + 1).trim()

        val keywordMatch = KEYWORD_TAIL.find(rest)
        val keywords = keywordMatch?.value?.let(::keywordsOf).orEmpty()
        if (keywordMatch != null) rest = rest.removeRange(keywordMatch.range).trim()

        val content = rest.take(MAX_CONTENT_CHARS)
        if (content.length < 2) return null

        return DistilledEntry(
            name = NAME_PREFIX + nameBody(content),
            description = content.take(MAX_DESC_CHARS),
            content = content,
            type = type,
            keywords = keywords,
            pending = content.contains("待确认") || content.contains("相冲突"),
        )
    }

    private fun keywordsOf(raw: String): List<String> {
        val inner = raw.trim().removePrefix("{").removeSuffix("}")
        val body = when {
            '：' in inner -> inner.substringAfter('：')
            ':' in inner -> inner.substringAfter(':')
            else -> inner
        }
        return KEYWORD_SEPARATORS.split(body)
            .map { it.trim().trim('"', '\'', '「', '」') }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    /** 名字取正文开头若干字：稳定、可读，换个说法就会生成新名字，靠写入前的相似度匹配兜重复。 */
    private fun nameBody(content: String): String {
        val cleaned = NAME_NOISE.replace(content, "")
        val sliced = cleaned.take(MAX_NAME_CHARS)
        return if (sliced.isBlank()) "要点" else sliced
    }
}
