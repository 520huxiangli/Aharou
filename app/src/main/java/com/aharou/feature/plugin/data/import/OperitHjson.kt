package com.aharou.feature.plugin.data.import

/**
 * 最小 Hjson 解析器，只覆盖 Operit 元数据实际用到的语法。
 *
 * 解析逻辑参考 AAswordman/Operit（LGPL-3.0）的 js 元数据读取方式，按本仓需要重写：
 * 不引入 Hjson 依赖，也不解析 Hjson 全集。
 *
 * 支持：无引号键、「斜杠加星号」块注释、「双斜杠」与井号行注释、尾逗号、`'''` 多行字符串、
 * 单引号与双引号字符串、无引号且按行截断的值；根对象可省略花括号。
 * 不支持：Hjson 的 `<` 续行拼接线（会被当成普通字符串，不拼接也不报错）、`$` 注释、
 * 正则字面量与任何表达式。结构本身破损（未闭合的对象 / 数组 / 注释 / 字符串）时抛出
 * [OperitHjsonException]，由调用方把整份元数据判为无效。
 */
object OperitHjson {

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text.removePrefix("\uFEFF"))
        return parser.parse()
    }

    class OperitHjsonException(message: String) : Exception(message)

    private class Parser(private val source: String) {

        private var index = 0

        fun parse(): Map<String, Any?> {
            skipSpace()
            if (atEnd()) return emptyMap()
            val result = if (peek() == '{') {
                readObject()
            } else {
                readObjectBody(braced = false)
            }
            skipSpace()
            if (!atEnd()) fail("unexpected trailing content")
            return result
        }

        private fun atEnd(): Boolean = index >= source.length

        private fun peek(offset: Int = 0): Char {
            val at = index + offset
            return if (at < source.length) source[at] else '\u0000'
        }

        private fun startsWith(token: String): Boolean = source.startsWith(token, index)

        private fun fail(message: String): Nothing = throw OperitHjsonException(message)

        private fun skipSpace() {
            while (!atEnd()) {
                val c = peek()
                when {
                    c == ' ' || c == '\t' || c == '\r' || c == '\n' -> index++
                    c == '#' -> skipLine()
                    c == '/' && peek(1) == '/' -> skipLine()
                    c == '/' && peek(1) == '*' -> {
                        val end = source.indexOf("*/", index + 2)
                        if (end < 0) fail("unterminated comment")
                        index = end + 2
                    }
                    else -> return
                }
            }
        }

        private fun skipLine() {
            while (!atEnd() && peek() != '\n') index++
        }

        private fun readObject(): Map<String, Any?> {
            index++
            return readObjectBody(braced = true)
        }

        private fun readObjectBody(braced: Boolean): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (atEnd()) {
                    if (braced) fail("unterminated object")
                    return out
                }
                val c = peek()
                if (braced && c == '}') {
                    index++
                    return out
                }
                if (c == ',') {
                    index++
                    continue
                }
                val key = readKey()
                skipSpace()
                if (atEnd() || peek() != ':') fail("expected ':' after key '$key'")
                index++
                out[key] = readValue()
                skipSpace()
                if (!atEnd() && peek() == ',') index++
            }
        }

        private fun readKey(): String {
            if (peek() == '"') return readQuoted()
            val start = index
            while (!atEnd()) {
                val c = peek()
                if (c == ':' || c == '\n' || c == '\r') break
                if (c == '{' || c == '}' || c == '[' || c == ']' || c == ',') break
                index++
            }
            val key = source.substring(start, index).trim()
            if (key.isEmpty()) fail("empty key")
            return key
        }

        private fun readValue(): Any? {
            skipSpace()
            if (atEnd()) return null
            return when {
                peek() == '{' -> readObject()
                peek() == '[' -> readArray()
                peek() == '"' -> readQuoted()
                startsWith("'''") -> readMultiline()
                else -> readQuoteless()
            }
        }

        private fun readArray(): List<Any?> {
            index++
            val out = mutableListOf<Any?>()
            while (true) {
                skipSpace()
                if (atEnd()) fail("unterminated array")
                if (peek() == ']') {
                    index++
                    return out
                }
                if (peek() == ',') {
                    index++
                    continue
                }
                out += readValue()
                skipSpace()
                if (!atEnd() && peek() == ',') index++
            }
        }

        private fun readQuoted(): String {
            index++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = source[index++]) {
                    '"' -> return out.toString()
                    '\n' -> fail("newline inside quoted string")
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val escape = source[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) fail("short unicode escape")
                                val hex = source.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            // Hjson 对未知转义取宽松写法，这里保持同样的宽容，避免个别包因拼写差异整份失败
                            else -> out.append(escape)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readMultiline(): String {
            index += 3
            if (peek() == '\r') index++
            if (peek() == '\n') index++
            val end = source.indexOf("'''", index)
            if (end < 0) fail("unterminated multiline string")
            val raw = source.substring(index, end)
            index = end + 3
            return dedent(raw)
        }

        /** 按结束引号所在行的缩进剥掉每行前缀，与 Hjson 的观感一致。 */
        private fun dedent(raw: String): String {
            val lines = raw.replace("\r\n", "\n").split('\n')
            val closingIndent = lines.lastOrNull().orEmpty().takeWhile { it == ' ' || it == '\t' }
            val body = lines.dropLast(1)
            val trimmed = body.map { line ->
                if (line.startsWith(closingIndent)) line.removePrefix(closingIndent) else line.trimStart()
            }
            return trimmed.joinToString("\n").trimEnd()
        }

        private fun readQuoteless(): Any? {
            val start = index
            while (!atEnd()) {
                val c = peek()
                if (c == '\n' || c == '\r' || c == ',' || c == '}' || c == ']') break
                if (c == '#') break
                if (c == '/' && (peek(1) == '/' || peek(1) == '*')) break
                index++
            }
            val raw = source.substring(start, index).trim()
            return when {
                raw.isEmpty() -> ""
                raw == "true" -> true
                raw == "false" -> false
                raw == "null" -> null
                NUMBER.matches(raw) -> if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                    raw.toDouble()
                } else {
                    raw.toLongOrNull() ?: raw.toDouble()
                }
                else -> raw
            }
        }
    }
}
