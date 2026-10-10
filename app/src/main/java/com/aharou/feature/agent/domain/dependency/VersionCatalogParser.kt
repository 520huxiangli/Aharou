package com.aharou.feature.agent.domain.dependency

/**
 * `gradle/libs.versions.toml`（Gradle 版本目录）的手写行级解析器。
 *
 * 项目里没有任何 TOML 解析库（无 toml4j / ktoml / kaml），且版本目录语法是 TOML 的极小子集，
 * 因此按行解析、只覆盖实际会用到的形态：
 *   - `[versions] a = "1.2.3"`
 *   - `[libraries] a = "g:a:v"` / `{ module = "g:a", version = "1.2.3" }` /
 *     `{ module = "g:a", version.ref = "x" }` / `{ group = "g", name = "a", version.ref = "x" }`
 *   - `[plugins] a = { id = "x", version.ref = "y" }`
 *   - `[bundles] a = ["lib1", "lib2"]`
 *   - 注释（`#`，在引号内不算注释）与单/双引号
 *
 * 解析不了的条目进 [CatalogModel.unparsed]，不静默吞掉。
 */
class VersionCatalogParser {

    data class CatalogVersion(val alias: String, val version: String, val line: Int, val rawLine: String)

    data class CatalogLibrary(
        val alias: String,
        val group: String?,
        val artifact: String?,
        val versionRef: String?,
        val inlineVersion: String?,
        val line: Int,
        val rawLine: String
    )

    data class CatalogPlugin(
        val alias: String,
        val id: String?,
        val versionRef: String?,
        val inlineVersion: String?,
        val line: Int,
        val rawLine: String
    )

    data class CatalogModel(
        val versions: Map<String, CatalogVersion>,
        val libraries: List<CatalogLibrary>,
        val bundles: Map<String, List<String>>,
        val plugins: List<CatalogPlugin>,
        val unparsed: List<UnparsedLine>
    )

    fun parse(content: String, filePath: String): CatalogModel {
        val versions = LinkedHashMap<String, CatalogVersion>()
        val libraries = ArrayList<CatalogLibrary>()
        val bundles = LinkedHashMap<String, List<String>>()
        val plugins = ArrayList<CatalogPlugin>()
        val unparsed = ArrayList<UnparsedLine>()

        var section = ""
        content.lines().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val stripped = stripComment(rawLine).trim()
            if (stripped.isEmpty()) return@forEachIndexed

            if (stripped.startsWith("[")) {
                section = stripped.trim('[', ']').trim()
                return@forEachIndexed
            }

            val assign = splitAssign(stripped)
            if (assign == null) {
                if (section in SECTION_KEYS) {
                    unparsed.add(UnparsedLine(filePath, lineNo, stripped, "缺少 `=`，无法识别条目"))
                }
                return@forEachIndexed
            }
            val key = assign.first
            val value = assign.second

            when (section) {
                "versions" -> {
                    val version = extractStringLiteral(value)
                    if (version == null) {
                        unparsed.add(UnparsedLine(filePath, lineNo, stripped, "版本值不是字符串字面量"))
                    } else {
                        versions[key] = CatalogVersion(key, version, lineNo, rawLine.trimEnd())
                    }
                }
                "libraries" -> {
                    val lib = parseLibrary(key, value, lineNo, rawLine.trimEnd())
                    if (lib == null) {
                        unparsed.add(UnparsedLine(filePath, lineNo, stripped, "无法解析 library 坐标/版本"))
                    } else {
                        libraries.add(lib)
                    }
                }
                "plugins" -> {
                    val plugin = parsePlugin(key, value, lineNo, rawLine.trimEnd())
                    if (plugin == null) {
                        unparsed.add(UnparsedLine(filePath, lineNo, stripped, "无法解析 plugin id/版本"))
                    } else {
                        plugins.add(plugin)
                    }
                }
                "bundles" -> {
                    val members = parseArray(value)
                    if (members == null) {
                        unparsed.add(UnparsedLine(filePath, lineNo, stripped, "bundles 值不是字符串数组"))
                    } else {
                        bundles[key] = members
                    }
                }
                else -> Unit
            }
        }

        return CatalogModel(versions, libraries, bundles, plugins, unparsed)
    }

    private fun parseLibrary(alias: String, value: String, line: Int, rawLine: String): CatalogLibrary? {
        if (value.startsWith("{")) {
            val table = parseInlineTable(value) ?: return null
            val module = table["module"]
            val group = module?.substringBefore(':')?.takeIf { it.isNotBlank() }
                ?: table["group"]?.takeIf { it.isNotBlank() }
            val artifact = module?.substringAfter(':', "")?.takeIf { it.isNotBlank() }
                ?: table["name"]?.takeIf { it.isNotBlank() }
            var versionRef = table["version.ref"]?.takeIf { it.isNotBlank() }
            var inline = table["version"]?.takeIf { it.isNotBlank() }
            if (versionRef == null && inline != null && inline.startsWith("{")) {
                // `version = { ref = "x" }` / `version = { strictly = "1.2.3" }`
                val nested = parseInlineTable(inline)
                versionRef = nested?.get("ref")?.takeIf { it.isNotBlank() }
                if (versionRef == null) inline = nested?.get("strictly")?.takeIf { it.isNotBlank() }
            }
            if (group == null || artifact == null) return null
            return CatalogLibrary(alias, group, artifact, versionRef, inline, line, rawLine)
        }
        val coordinate = extractStringLiteral(value) ?: return null
        val parts = coordinate.split(':')
        if (parts.size < 3) return null
        return CatalogLibrary(
            alias = alias,
            group = parts[0].takeIf { it.isNotBlank() },
            artifact = parts[1].takeIf { it.isNotBlank() },
            versionRef = null,
            inlineVersion = parts.last().takeIf { it.isNotBlank() },
            line = line,
            rawLine = rawLine
        )
    }

    private fun parsePlugin(alias: String, value: String, line: Int, rawLine: String): CatalogPlugin? {
        if (!value.startsWith("{")) return null
        val table = parseInlineTable(value) ?: return null
        val id = table["id"]?.takeIf { it.isNotBlank() }
        val versionRef = table["version.ref"]?.takeIf { it.isNotBlank() }
        val inline = table["version"]?.takeIf { it.isNotBlank() }
        if (id == null) return null
        return CatalogPlugin(alias, id, versionRef, inline, line, rawLine)
    }

    private fun parseInlineTable(value: String): Map<String, String>? {
        val body = value.trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val inner = body.substring(1, body.length - 1)
        val result = LinkedHashMap<String, String>()
        splitTopLevel(inner).forEach { entry ->
            val pair = splitAssign(entry) ?: return@forEach
            val key = pair.first
            val rawValue = pair.second
            result[key] = if (rawValue.startsWith("{")) rawValue else (extractStringLiteral(rawValue) ?: rawValue)
        }
        return result
    }

    private fun parseArray(value: String): List<String>? {
        val body = value.trim()
        if (!body.startsWith("[") || !body.endsWith("]")) return null
        val inner = body.substring(1, body.length - 1)
        return splitTopLevel(inner).map { extractStringLiteral(it.trim()) ?: it.trim() }
    }

    /** 按顶层逗号切分（忽略引号与嵌套括号内的逗号）。 */
    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var depth = 0
        for (ch in text) {
            when {
                quote != null -> {
                    sb.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    sb.append(ch)
                }
                ch == '{' || ch == '[' -> {
                    depth++
                    sb.append(ch)
                }
                ch == '}' || ch == ']' -> {
                    depth--
                    sb.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts.filter { it.isNotBlank() }
    }

    /** 去掉引号并还原转义；不是字符串字面量时返回 null。 */
    private fun extractStringLiteral(value: String): String? {
        val v = value.trim()
        if (v.length < 2) return null
        val quote = v.first()
        if ((quote != '"' && quote != '\'') || v.last() != quote) return null
        return v.substring(1, v.length - 1)
    }

    /** 在引号外找到第一个 `=`，切成 key/value。 */
    private fun splitAssign(segment: String): Pair<String, String>? {
        var quote: Char? = null
        segment.forEachIndexed { i, ch ->
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '=' -> return segment.substring(0, i).trim() to segment.substring(i + 1).trim()
            }
        }
        return null
    }

    /** 去掉 `#` 注释（引号内的 `#` 保留）。 */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { i, ch ->
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' -> return line.substring(0, i)
            }
        }
        return line
    }

    private companion object {
        val SECTION_KEYS = setOf("versions", "libraries", "plugins", "bundles")
    }
}
