package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import org.yaml.snakeyaml.Yaml
import java.io.File

object MemoryParser {
    private const val TAG = "MemoryParser"
    private const val MAX_DESC_CHARS = 500
    private const val MAX_KEYWORDS = 8

    /** 名字里带这种后缀说明是「某天发生的事」，不是跨会话仍成立的知识。 */
    private val DATE_SUFFIX = Regex("\\d{4}-\\d{2}-\\d{2}|\\d{8}")
    private val EVENT_MARKERS = listOf("-test-", "-result", "test-result", "-tmp-", "-scratch-")
    private val SPLIT = Regex("[\\s,，、。;；:：()（）\\[\\]【】/\\\\|]+")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "this", "that", "is", "are", "was", "were",
        "一个", "这个", "那个", "就是", "可以", "已经", "还有", "如果", "所以", "但是", "因为",
        "全部", "需要", "注意", "表示", "使用", "进行", "没有", "不是", "以及", "或者"
    )

    fun parse(file: File, scope: MemoryScope): Memory? {
        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取 Memory 文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val content = body.trim()

        // 分类与关键词都允许缺省：老条目没有这两个字段，靠启发式与自动抽取补齐，
        // 这样不需要对已有记忆做任何迁移。
        val type = MemoryType.from(frontmatter["type"]?.toString()) ?: inferType(name)
        val declared = parseKeywords(frontmatter["keywords"])
        val keywords = declared.ifEmpty { extractKeywords(name, description, content) }

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = content,
            type = type,
            keywords = keywords
        )
    }

    fun format(
        name: String,
        description: String,
        content: String,
        type: MemoryType? = null,
        keywords: List<String> = emptyList()
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val head = StringBuilder("---\nname: $safeName\ndescription: $safeDesc\n")
        type?.let { head.append("type: ${it.key}\n") }
        if (keywords.isNotEmpty()) head.append("keywords: ${yamlScalar(keywords.joinToString(", "))}\n")
        head.append("---\n")
        return head.toString() + content
    }

    /**
     * 老条目没有 `type` 字段时的启发式判定。
     *
     * 只认最有把握的两类：核心档案→身份（永留），名字带日期或测试字样→事件（不进清单）。
     * 其余一律当普通事实，宁可多注入一点也不要误把长期知识标成事件而丢出清单。
     */
    private fun inferType(name: String): MemoryType {
        if (isCoreName(name)) return MemoryType.IDENTITY
        if (DATE_SUFFIX.containsMatchIn(name)) return MemoryType.EVENT
        val lower = name.lowercase()
        if (EVENT_MARKERS.any { lower.contains(it) }) return MemoryType.EVENT
        return MemoryType.FACT
    }

    /**
     * 核心档案：基名全大写（CORE / GLOBAL / SOUL / OPS / PROFILE / SECRETS / LIFE / L0_AGENT …）。
     * 与 MemoryRepository 的判定保持一致：这些是身份与长期约定，永远算 identity、永远不遗忘。
     */
    private fun isCoreName(name: String): Boolean {
        val base = name.substringBeforeLast('.').uppercase()
        return base.isNotEmpty() && base.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }

    /** 解析显式声明的 `keywords`，兼容 YAML 列表与逗号串两种写法。 */
    private fun parseKeywords(raw: Any?): List<String> {
        val parts = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(',', '，', ' ')
            else -> return emptyList()
        }
        return parts.map { it.trim() }
            .filter { it.length in 2..24 }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    /**
     * 零成本关键词抽取（不调模型）：从 name 与 description 取词。
     *
     * 关键词只服务于 `memory(action=search)` 的召回，不进注入清单，
     * 所以宁多勿缺：模糊一点只会多召回一条，漏了则等于搜不到。
     * 正文不参与抽取——长正文会把噪音词顶上来，且拖慢每次列目录。
     */
    private fun extractKeywords(name: String, description: String, body: String): List<String> {
        val out = LinkedHashSet<String>()
        name.split('-', '_', ' ').forEach { if (it.length >= 2) out += it }   // 文件名本身就是高信号
        SPLIT.split(description).forEach { raw ->
            val w = raw.trim().trimStart('\'', '"', '（', '(').trimEnd('\'', '"', '）', ')', '。', '，')
            if (w.length in 2..24 && w.lowercase() !in STOP_WORDS) out += w
        }
        return out.take(MAX_KEYWORDS)
    }

    /** 把任意字符串转成安全的 YAML 标量，避免冒号/引号/换行破坏 frontmatter。 */
    private fun yamlScalar(value: String): String {
        val needsQuote = value.contains(':') || value.contains('#') ||
            value.contains('"') || value.contains('\'') ||
            value.startsWith('-') || value.startsWith(' ') || value.endsWith(' ') ||
            value.contains('\n') || value.isBlank()
        return if (needsQuote) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}
