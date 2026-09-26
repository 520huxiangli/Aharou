package com.aharou.core.db

/**
 * 把 SQL 脚本按语句切分，识别注释与字面量，保证其中的分号不参与切分。
 *
 * 旧实现直接 `split(";")`，字符串字面量里出现分号（如 `';base64,'`）会被拦腰截断，
 * 整条迁移失败。状态机覆盖：`--` 行注释、`/* */` 块注释、单引号/双引号/反引号
 * 字面量（含双写转义 `''`）、`[...]` 标识符。块注释按 SQLite 语义不支持嵌套。
 */
object SqlScriptSplitter {

    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var currentHasContent = false
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            when {
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    current.append("--")
                    i += 2
                    while (i < n && script[i] != '\n') {
                        current.append(script[i])
                        i++
                    }
                }

                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    current.append("/*")
                    i += 2
                    while (i < n && !(script[i] == '*' && i + 1 < n && script[i + 1] == '/')) {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append("*/")
                        i += 2
                    }
                }

                c == '\'' || c == '"' || c == '`' -> {
                    current.append(c)
                    i++
                    while (i < n) {
                        if (script[i] == c) {
                            // 双写是转义（SQL 的 ''、``、""），继续留在字面量内
                            current.append(c)
                            i++
                            if (i < n && script[i] == c) {
                                current.append(c)
                                i++
                            } else {
                                break
                            }
                        } else {
                            current.append(script[i])
                            i++
                        }
                    }
                    currentHasContent = true
                }

                c == '[' -> {
                    current.append(c)
                    i++
                    while (i < n && script[i] != ']') {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append(']')
                        i++
                    }
                    currentHasContent = true
                }

                c == ';' -> {
                    if (currentHasContent) {
                        val stmt = current.toString().trim()
                        if (stmt.isNotEmpty()) statements.add(stmt)
                    }
                    current.setLength(0)
                    currentHasContent = false
                    i++
                }

                else -> {
                    current.append(c)
                    if (!c.isWhitespace()) currentHasContent = true
                    i++
                }
            }
        }
        val last = current.toString().trim()
        if (currentHasContent && last.isNotEmpty()) statements.add(last)
        return statements
    }
}