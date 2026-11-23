package com.aharou.feature.plugin.data.import

/** JS 里 `ToolPkg.registerTool({...})` / `registerApi({...})` 声明的工具；[file] 是包内相对路径。 */
internal data class OperitRuntimeTool(
    val name: String,
    val displayName: String?,
    val displayNameZh: String?,
    val displayNameEn: String?,
    val description: String,
    val descriptionZh: String?,
    val descriptionEn: String?,
    val parameters: List<OperitToolParameter>,
    val file: String,
    /** 来自 `registerApi`：没有参数声明，schema 需允许任意键。 */
    val openSchema: Boolean = false
)

/**
 * 静态扫描 `ToolPkg.registerTool({...})` / `ToolPkg.registerApi({...})`，不执行插件代码。
 *
 * v2 包多数工具写在 `METADATA` 块里，由 [OperitMetadataReader] 负责；仍有包把声明写成 JS 调用，
 * 这类工具只在运行时注册，静态看不出来就会导入成空壳。这里先按子串预筛，再做字符级扫描：
 * 字符串、模板串、行注释、块注释都跳过，按花括号配对取对象字面量。
 * 任何一处解析不了都只当「这个包没有这类工具」，不让单点失败拦住整包导入。
 */
internal object OperitRuntimeToolScanner {

    private const val API = "ToolPkg"
    private const val METHOD = "registerTool"
    private const val LEGACY_API_METHOD = "registerApi"
    private const val MAX_TOOLS_PER_SCRIPT = 512
    private const val MAX_CALL_CHARS = 200_000
    private const val MAX_NAME_CHARS = 128

    fun scan(scripts: List<OperitScript>): List<OperitRuntimeTool> =
        scanMethod(scripts, METHOD) { literal -> toTool(literal, openSchema = false) }

    /**
     * v2 的 `ToolPkg.registerApi({ name, function })`：只有方法名，没有标题/参数。
     * 只在包里找不到任何其他地方声明的工具时才拿来兜底，否则会把一堆 UI 显式 API 也当成工具。
     */
    fun scanApiFallback(scripts: List<OperitScript>): List<OperitRuntimeTool> =
        scanMethod(scripts, LEGACY_API_METHOD) { literal -> toTool(literal, openSchema = true) }

    private fun scanMethod(
        scripts: List<OperitScript>,
        method: String,
        convert: (Map<String, Any?>?) -> OperitRuntimeTool?
    ): List<OperitRuntimeTool> {
        val result = mutableListOf<OperitRuntimeTool>()
        scripts.forEach { script ->
            if (!script.text.contains(method)) return@forEach
            val literals = runCatching {
                JsObjectLiteral.findCalls(script.text, API, method, MAX_CALL_CHARS)
            }.getOrDefault(emptyList())
            literals.take(MAX_TOOLS_PER_SCRIPT).forEach { literal ->
                convert(literal)?.copy(file = script.path)?.let(result::add)
            }
        }
        return result.distinctBy { it.file to it.name }
    }

    private fun toTool(literal: Map<String, Any?>?, openSchema: Boolean): OperitRuntimeTool? {
        if (literal == null) return null
        val name = (literal["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return null
        // Operit 的 display 是 `{ label: {zh, en}, icon }`，只取标题；有些包直接给字符串或 title
        val display = literal["display"] ?: literal["label"] ?: literal["title"]
        val label = (display as? Map<*, *>)?.get("label") ?: display
        val description = literal["description"]
        return OperitRuntimeTool(
            name = name,
            displayName = operitLocalizedText(label),
            displayNameZh = operitLocalizedText(label, "zh"),
            displayNameEn = operitLocalizedText(label, "en"),
            description = operitLocalizedText(description).orEmpty(),
            descriptionZh = operitLocalizedText(description, "zh"),
            descriptionEn = operitLocalizedText(description, "en"),
            parameters = operitParametersFrom(literal["parameters"]),
            file = "",
            openSchema = openSchema
        )
    }
}

/**
 * 够用就好的 JS 对象字面量读取器：只认字面量（字符串 / 数字 / 布尔 / 数组 / 对象），
 * 遇到函数、变量名、拼接表达式一律返回「不可解析」，由调用方跳过该字段。
 * 不追求完整 JS 语法——插件包里的工具声明本身就是纯数据。
 */
private object JsObjectLiteral {

    fun findCalls(script: String, api: String, method: String, maxChars: Int): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var i = 0
        val length = script.length
        while (i < length) {
            val c = script[i]
            when {
                c == '/' && i + 1 < length && script[i + 1] == '/' -> i = lineEnd(script, i)
                c == '/' && i + 1 < length && script[i + 1] == '*' -> i = blockCommentEnd(script, i)
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(script, i)
                    i = if (end < 0) length else end
                }
                c == api[0] && isBoundary(script, i) && script.startsWith(api, i) -> {
                    val call = parseCall(script, i, api, method, maxChars)
                    if (call == null) {
                        i += api.length
                    } else {
                        call.first?.let(result::add)
                        i = call.second
                    }
                }
                else -> i++
            }
        }
        return result
    }

    /** 返回（解析出的对象, 调用之后的游标）；不是完整的 registerTool 调用时返回 null。 */
    private fun parseCall(
        script: String,
        start: Int,
        api: String,
        method: String,
        maxChars: Int
    ): Pair<Map<String, Any?>?, Int>? {
        var i = skipTrivia(script, start + api.length)
        if (i >= script.length || script[i] != '.') return null
        i = skipTrivia(script, i + 1)
        if (!script.startsWith(method, i)) return null
        val afterMethod = i + method.length
        // registerToolPkg / registerToolLifecycleHook 这类前缀相同的 API 不能误判
        if (afterMethod < script.length && isIdentifierPart(script[afterMethod])) return null
        i = skipTrivia(script, afterMethod)
        if (i >= script.length || script[i] != '(') return null
        val brace = skipTrivia(script, i + 1)
        if (brace >= script.length || script[brace] != '{') return null
        val close = matching(script, brace, '{', '}') ?: return null
        if (close - brace > maxChars) return null
        return parseObject(script, brace, close) to (close + 1)
    }

    private fun parseObject(script: String, open: Int, close: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        var i = open + 1
        while (i < close) {
            i = skipTrivia(script, i)
            if (i >= close) break
            when {
                script[i] == ',' -> i++
                script[i] == '}' -> break
                isSpread(script, i) -> i = skipMember(script, i + 3, close)
                else -> {
                    val key = readKey(script, i, close)
                    if (key == null) {
                        i = skipMember(script, i, close)
                        continue
                    }
                    val colon = skipTrivia(script, key.second)
                    if (colon >= close || script[colon] != ':') {
                        i = skipMember(script, key.second, close)
                        continue
                    }
                    val valueStart = skipTrivia(script, colon + 1)
                    val value = parseValue(script, valueStart, close)
                    if (value == null) {
                        i = skipMember(script, valueStart, close)
                    } else {
                        result[key.first] = value.value
                        i = skipTrivia(script, value.next)
                    }
                    if (i < close && script[i] == ',') i++
                }
            }
        }
        return result
    }

    private fun parseArray(script: String, open: Int, close: Int): List<Any?> {
        val result = mutableListOf<Any?>()
        var i = open + 1
        while (i < close) {
            i = skipTrivia(script, i)
            if (i >= close) break
            if (script[i] == ',') {
                i++
                continue
            }
            if (script[i] == ']') break
            val value = parseValue(script, i, close)
            if (value == null) {
                i = skipMember(script, i, close)
                continue
            }
            result += value.value
            i = skipTrivia(script, value.next)
            if (i < close && script[i] == ',') i++
        }
        return result
    }

    private class Parsed(val value: Any?, val next: Int)

    private fun parseValue(script: String, start: Int, limit: Int): Parsed? {
        val i = skipTrivia(script, start)
        if (i >= limit) return null
        return when (script[i]) {
            '{' -> {
                val close = matching(script, i, '{', '}') ?: return null
                Parsed(parseObject(script, i, close), close + 1)
            }
            '[' -> {
                val close = matching(script, i, '[', ']') ?: return null
                Parsed(parseArray(script, i, close), close + 1)
            }
            '"', '\'' -> {
                val end = stringEnd(script, i)
                if (end < 0) return null
                Parsed(decodeString(script.substring(i, end)), end)
            }
            '`' -> {
                val end = stringEnd(script, i)
                if (end < 0) return null
                val raw = script.substring(i, end)
                // 模板串里的插值要执行才知道结果，当字符串用会导出错误描述
                val hasInterpolation = raw.indices.any { index ->
                    raw[index] == '$' && index + 1 < raw.length && raw[index + 1] == '{'
                }
                if (hasInterpolation) return null
                Parsed(decodeString(raw), end)
            }
            else -> readScalar(script, i, limit)
        }
    }

    private fun readScalar(script: String, start: Int, limit: Int): Parsed? {
        var i = start
        while (i < limit && script[i] != ',' && script[i] != '}' && script[i] != ']' &&
            !script[i].isWhitespace()
        ) {
            i++
        }
        val token = script.substring(start, i)
        return when {
            token.isEmpty() -> null
            token == "true" -> Parsed(true, i)
            token == "false" -> Parsed(false, i)
            token == "null" || token == "undefined" -> Parsed(null, i)
            NUMBER.matches(token) -> Parsed(token.toDoubleOrNull() ?: token, i)
            else -> null
        }
    }

    private fun readKey(script: String, start: Int, limit: Int): Pair<String, Int>? {
        if (start >= limit) return null
        val first = script[start]
        if (first == '"' || first == '\'' || first == '`') {
            val end = stringEnd(script, start)
            if (end < 0 || end > limit) return null
            return decodeString(script.substring(start, end)) to end
        }
        if (!isIdentifierPart(first)) return null
        var i = start
        while (i < limit && isIdentifierPart(script[i])) i++
        return script.substring(start, i) to i
    }

    private fun skipMember(script: String, start: Int, limit: Int): Int {
        var i = start
        var depth = 0
        while (i < limit) {
            val c = script[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(script, i)
                    if (end < 0) return limit
                    i = end
                }
                c == '/' && i + 1 < limit && script[i + 1] == '/' -> i = lineEnd(script, i)
                c == '/' && i + 1 < limit && script[i + 1] == '*' -> i = blockCommentEnd(script, i)
                c == '{' || c == '[' || c == '(' -> {
                    depth++
                    i++
                }
                c == '}' || c == ']' || c == ')' -> {
                    if (depth == 0) return i
                    depth--
                    i++
                }
                c == ',' && depth == 0 -> return i
                else -> i++
            }
        }
        return limit
    }

    private fun matching(script: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var i = open
        while (i < script.length) {
            val c = script[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(script, i)
                    if (end < 0) return null
                    i = end
                }
                c == '/' && i + 1 < script.length && script[i + 1] == '/' -> i = lineEnd(script, i)
                c == '/' && i + 1 < script.length && script[i + 1] == '*' -> i = blockCommentEnd(script, i)
                c == openChar -> {
                    depth++
                    i++
                }
                c == closeChar -> {
                    depth--
                    i++
                    if (depth == 0) return i - 1
                }
                else -> i++
            }
        }
        return null
    }

    /** 返回闭合引号之后的下标；引号未闭合（或字符串内换行）时返回 -1。 */
    private fun stringEnd(script: String, start: Int): Int {
        val quote = script[start]
        var i = start + 1
        while (i < script.length) {
            val c = script[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                c == '\n' && quote != '`' -> return -1
                else -> i++
            }
        }
        return -1
    }

    private fun skipTrivia(script: String, start: Int): Int {
        var i = start
        while (i < script.length) {
            val c = script[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < script.length && script[i + 1] == '/' -> i = lineEnd(script, i)
                c == '/' && i + 1 < script.length && script[i + 1] == '*' -> i = blockCommentEnd(script, i)
                else -> return i
            }
        }
        return i
    }

    private fun lineEnd(script: String, start: Int): Int {
        val index = script.indexOf('\n', start)
        return if (index < 0) script.length else index + 1
    }

    private fun blockCommentEnd(script: String, start: Int): Int {
        val index = script.indexOf("*/", start + 2)
        return if (index < 0) script.length else index + 2
    }

    private fun isSpread(script: String, index: Int): Boolean =
        script[index] == '.' && index + 2 < script.length &&
            script[index + 1] == '.' && script[index + 2] == '.'

    private fun isBoundary(script: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = script[index - 1]
        return !isIdentifierPart(previous) && previous != '.'
    }

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private val NUMBER = Regex("""-?(0[xX][0-9a-fA-F]+|\d+(\.\d+)?([eE][+-]?\d+)?)""")

    private fun decodeString(raw: String): String {
        if (raw.length < 2) return ""
        val body = raw.substring(1, raw.length - 1)
        if (body.indexOf('\\') < 0) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            when (val escape = body[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '0' -> out.append('\u0000')
                '\n' -> Unit
                'u' -> {
                    val code = if (i + 6 <= body.length) body.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(escape)
                }
                'x' -> {
                    val code = if (i + 4 <= body.length) body.substring(i + 2, i + 4).toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 4
                        continue
                    }
                    out.append(escape)
                }
                else -> out.append(escape)
            }
            i += 2
        }
        return out.toString()
    }
}
