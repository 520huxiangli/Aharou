package com.aharou.core.util

/**
 * POSIX sh 单引号转义：把 [value] 整体包成单引号字面量，内部的单引号用 `'"'"'` 转义，
 * 供容器/远程命令拼接时安全嵌入任意字符串（空格、引号等特殊字符不会破坏命令结构）。
 */
internal fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
