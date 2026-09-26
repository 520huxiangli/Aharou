package com.aharou.feature.agent.domain.tool.explorer

/**
 * explorer 工具（search/list）共用的 shell 参数解析与安全白名单逻辑。
 *
 * 模型常把 `| head -30` 这类 shell 管道直接写进工具 args（如 `search(args="-n \"foo\" src | head -30")`）。
 * 这里把 `|` 从 rg/ls 参数中分离出来单独解析：**只放行 head 行数截断**，其余管道命令一律拒绝，
 * 防止借管道语法在容器里执行任意命令。
 */

/** 按 shell 词法把 args 拆成 tokens；引号未闭合返回 null。`\|`/`|` 解析为独立 token `|`，引号内的 `|` 是字面量。 */
fun parseShellWords(input: String): List<String>? {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var tokenStarted = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            quote == '\'' -> {
                if (c == '\'') quote = null else current.append(c)
                tokenStarted = true
            }
            quote == '"' -> {
                when (c) {
                    '"' -> quote = null
                    '\\' -> {
                        if (i + 1 < input.length) {
                            i++
                            current.append(input[i])
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                tokenStarted = true
            }
            c.isWhitespace() -> {
                if (tokenStarted) {
                    result.add(current.toString())
                    current.clear()
                    tokenStarted = false
                }
            }
            c == '\'' || c == '"' -> {
                quote = c
                tokenStarted = true
            }
            c == '\\' -> {
                if (i + 1 < input.length) {
                    i++
                    current.append(input[i])
                } else {
                    current.append(c)
                }
                tokenStarted = true
            }
            else -> {
                current.append(c)
                tokenStarted = true
            }
        }
        i++
    }
    if (quote != null) return null
    if (tokenStarted) result.add(current.toString())
    return result
}

/**
 * 把 tokens 按独立的 `|` 拆为 (管道前的 tokens, 管道段列表)。
 * 无管道返回 (tokens, 空列表)；`|` 前没有任何参数返回 null。
 * 引号内（如 `"a|b"`）的 `|` 属于 token 本身，不会触发拆分。
 */
fun splitPipes(tokens: List<String>): Pair<List<String>, List<List<String>>>? {
    val pipeIndex = tokens.indexOf("|")
    if (pipeIndex < 0) return tokens to emptyList()
    val headTokens = tokens.subList(0, pipeIndex)
    if (headTokens.isEmpty()) return null
    val segments = mutableListOf<List<String>>()
    var i = pipeIndex
    while (i < tokens.size) {
        if (tokens[i] != "|") return null
        i++
        val segStart = i
        while (i < tokens.size && tokens[i] != "|") i++
        segments += tokens.subList(segStart, i)
    }
    return headTokens to segments
}

/**
 * head 管道段白名单：仅 `head`（默认 10 行）、`head -N`、`head -n N`，N 必须纯数字。
 * 返回该段允许输出的行数；命令不是 head、或带其它参数（含 `;`、重定向、`head -c` 等）返回 null。
 */
fun headLinesOf(seg: List<String>): Int? = when {
    seg.isEmpty() -> null
    seg[0] != "head" -> null
    seg.size == 1 -> 10
    seg.size == 2 -> {
        val n = seg[1]
        if (n.length > 1 && n[0] == '-' && n.drop(1).all(Char::isDigit)) n.drop(1).toInt() else null
    }
    seg.size == 3 && seg[1] == "-n" -> {
        val n = seg[2]
        if (n.isNotEmpty() && n.all(Char::isDigit)) n.toInt() else null
    }
    else -> null
}

/**
 * 组装传给容器的 rg 搜索命令：基础参数 + 全部参数 token（单引号转义、`~/` 展开为 [home]），
 * 若带管道则原样追加白名单校验过的 `| head ...` 段。管道段含非 head 命令/非法参数时返回 null。
 *
 * [home] 为命令执行环境的 home（本地 PRoot 容器为 /root，远程 SSH 为远程用户 home），
 * 用于展开 `~/` 参数——quote 后 shell 不会自动展开 `~`，故在构造命令时显式展开。
 */
fun buildSearchCommand(tokens: List<String>, home: String = "/root"): String? {
    val (rgTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = mutableListOf(
        "rg",
        "--line-number",
        "--no-heading",
        "--with-filename",
        "--color",
        "never"
    )
    args.addAll(rgTokens.map { shellQuote(expandTilde(it, home)) })
    var command = args.joinToString(" ")
    for (seg in pipeSegments) {
        if (headLinesOf(seg) == null) return null
        command += " | " + seg.joinToString(" ")
    }
    return command
}

/** 把 `~`/`~/` 开头的路径参数展开为 [home] 路径，避免被单引号包裹后 shell 不展开 `~`。 */
fun expandTilde(arg: String, home: String = "/root"): String =
    when {
        arg == "~" -> home
        arg.startsWith("~/") -> home.trimEnd('/') + arg.removePrefix("~")
        else -> arg
    }

/** 单引号包裹，shell 命令安全。 */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
