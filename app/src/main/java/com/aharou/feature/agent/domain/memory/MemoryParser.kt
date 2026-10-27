package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

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

        val (frontmatter, body) = Frontmatter.splitAndParse(text, TAG, "解析 YAML 失败")

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val content = body.trim()

        // 分类与关键词都允许缺省：老条目没有这两个字段，靠启发式与自动抽取补齐，
        // 这样不需要对已有记忆做任何迁移。
        val type = MemoryType.from(frontmatter["type"]?.toString()) ?: inferType(name)
        val declared = parseKeywords(frontmatter["keywords"])
        val keywords = if (frontmatter.containsKey("keywords")) declared else extractKeywords(name, description, content)

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = content,
            type = type,
            keywords = keywords,
            expiresAt = parseExpiresAt(frontmatter["expires_at"]),
            pending = frontmatter["pending"]?.toString()?.equals("true", ignoreCase = true) == true,
            // 版本只跟内容走：同一份全文算出的版本恒定，与路径、mtime 无关。
            version = MemoryFileStorage.versionOf(text)
        )
    }

    fun format(
        name: String,
        description: String,
        content: String,
        type: MemoryType? = null,
        keywords: List<String>? = null,
        expiresAt: Long? = null,
        pending: Boolean = false
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val head = StringBuilder("---\nname: $safeName\ndescription: $safeDesc\n")
        type?.let { head.append("type: ${it.key}\n") }
        keywords?.let { head.append("keywords: [${it.joinToString(", ") { word -> yamlScalar(word) }}]\n") }
        expiresAt?.takeIf { it > 0L }?.let { head.append("expires_at: $it\n") }
        if (pending) head.append("pending: true\n")
        head.append("---\n")
        return head.toString() + content
    }

    /**
     * 解析 `expires_at`：兼容 `yyyy-MM-dd`（当天 23:59:59 到期）与 epoch 毫秒两种写法。
     * 格式不合法时返回 null（当不过期），不因一个字段把整条记忆解析失败。
     */
    private fun parseExpiresAt(raw: Any?): Long? {
        val text = when (raw) {
            is java.util.Date -> raw.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            else -> raw?.toString()?.trim()
        }?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return it.takeIf { v -> v > 0L } }
        return runCatching {
            LocalDate.parse(text).atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 老条目没有 `type` 字段时的启发式判定。
     *
     * 只认最有把握的两类：核心档案→身份（永留），名字带日期或测试字样→事件（不进清单）。
     * 其余一律当普通事实，宁可多注入一点也不要误把长期知识标成事件而丢出清单。
     */
    private fun inferType(name: String): MemoryType {
        if (isCoreArchiveName(name)) return MemoryType.IDENTITY
        if (DATE_SUFFIX.containsMatchIn(name)) return MemoryType.EVENT
        val lower = name.lowercase()
        if (EVENT_MARKERS.any { lower.contains(it) }) return MemoryType.EVENT
        return MemoryType.FACT
    }

    /** 解析显式声明的 `keywords`，兼容 YAML 列表与逗号串两种写法。 */
    private fun parseKeywords(raw: Any?): List<String> {
        val parts = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(',', '，', ' ')
            else -> return emptyList()
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 零成本关键词抽取（不调模型）：从 name 与 description 取词，另加正文里的结构化实体。
     *
     * 关键词只服务于 `memory(action=search)` 的召回，不进注入清单，
     * 所以宁多勿缺：模糊一点只会多召回一条，漏了则等于搜不到。
     * 正文只参与实体抽取（IP / 域名 / 路径 / 端口）——这些串稀有且精确，
     * 而正文的普通词会把噪音顶上来、拖慢每次列目录。
     */
    private fun extractKeywords(name: String, description: String, body: String): List<String> {
        val out = LinkedHashSet<String>()
        MemoryEntities.extract("$name\n$description\n$body").forEach { out += it }
        name.split('-', '_', ' ').forEach { if (it.length >= 2) out += it }   // 文件名本身就是高信号
        SPLIT.split(description).forEach { raw ->
            val w = raw.trim().trimStart('\'', '"', '（', '(').trimEnd('\'', '"', '）', ')', '。', '，')
            if (w.length in 2..24 && w.lowercase() !in STOP_WORDS) out += w
        }
        return out.take(MAX_KEYWORDS)
    }

    /** 把任意字符串转成安全的 YAML 标量，避免冒号/引号/换行破坏 frontmatter。 */
    private fun yamlScalar(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
