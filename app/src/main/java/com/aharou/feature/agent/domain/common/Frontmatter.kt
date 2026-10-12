package com.aharou.feature.agent.domain.common

import com.aharou.core.util.FileLogger
import org.yaml.snakeyaml.Yaml

/**
 * Markdown 文件 YAML frontmatter 的切分与解析，供技能、子代理定义、记忆三处共用。
 *
 * 约定：文本以 `---\n` 起始，到下一个独立成行的 `---` 结束，其下为正文；
 * 无 frontmatter 或 YAML 非法时返回 (空 map, 归一化后的全文)，由调用方按各自默认值兜底。
 */
internal object Frontmatter {

    /** @return (frontmatter 键值对, 正文)。[tag]/[failureMessage] 仅用于解析失败的日志。 */
    fun splitAndParse(
        text: String,
        tag: String,
        failureMessage: String
    ): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(tag, failureMessage, e)
            emptyMap()
        }
        return map to rest
    }

    /** frontmatter 字符串值：双引号包裹，转义反斜杠与引号，换行压成空格保证单行。 */
    fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
