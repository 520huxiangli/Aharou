package com.aharou.feature.editor.domain.diagnostics

import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** 超过该体积跳过检查（读取 + 编译的开销对手机不值当）。 */
private const val MAX_FILE_BYTES = 512 * 1024

/** 单次检查的命令超时。 */
private const val TIMEOUT_MS = 10_000L

/** 结果条数上限，防御检查器刷屏。 */
private const val MAX_DIAGNOSTICS = 200

/** shell 未找到命令的退出码。 */
private const val COMMAND_NOT_FOUND = 127

/** 单条提示文本的长度上限。 */
private const val MAX_MESSAGE_LENGTH = 500

/**
 * 把 `py_compile` 生成的字节码缓存引到 /tmp，避免在用户工作区里落下 `__pycache__`。
 * 该变量自 Python 3.8 起生效，旧解释器会忽略它（此时会照旧生成 `__pycache__`，属可接受的降级）。
 */
private const val PYCACHE_PREFIX = "/tmp/.aharou_diag"

private val PY_FILE_LINE = Regex("""File\s+"[^"]*",\s*line\s+(\d+)""")
private val PY_ERROR_MESSAGE = Regex("""^([A-Za-z_]\w*Error)\s*:\s*(.+)$""")

/**
 * Python 3.13 起缩进类错误换了输出格式：`Sorry: IndentationError: … (file, line N)`，
 * 没有 `File "…", line N` 行，行号落在尾部括号里（2026-10-05 在 3.14 上实测）。
 */
private val PY_SORRY_LINE = Regex("""^Sorry:\s*([A-Za-z_]\w*Error)\s*:\s*(.+?)\s*\(([^()]*),\s*line\s+(\d+)\)$""")
private val LUA_LOCATION = Regex(""":(\d+):\s*(.+)$""")

/**
 * 语法检查执行器：按文件扩展名选检查命令 → 在容器里跑一次 → 解析输出成 [EditorDiagnostic] 列表。
 *
 * 只覆盖 Python（`python3 -m py_compile`）与 Lua（`luac -p`），C/C++ 留待后续阶段。
 *
 * 全部是「尽力而为」：容器未就绪、检查器未安装（退出码 127）、文件过大、超时、解析不出任何信息，
 * 都静默返回空列表，不打扰用户。执行走 [CommandEngine.runCommandSyncIfReady]——它**不会**触发容器初始化，
 * 没开过终端时直接得到 null 并跳过，避免为了画波浪线把 rootfs 解压拉起来。
 */
@Singleton
class DiagnosticsRunner @Inject constructor(
    private val commandEngine: CommandEngine,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
) {

    /**
     * 对 [path] 指向的磁盘文件跑一次检查。[contentLength] 为编辑器里的字符数，仅用于体积阈值判断。
     *
     * 注意：阶段 1 检查的是**磁盘上的文件**（触发点是打开/保存，此时磁盘内容与编辑器一致），
     * 因此未保存的编辑不会反映到结果里。
     */
    suspend fun check(path: String, contentLength: Int): List<EditorDiagnostic> {
        if (contentLength > MAX_FILE_BYTES) return emptyList()
        val checker = checkerFor(path) ?: return emptyList()
        val quoted = shellQuote(pathHomeResolver.expandHome(path))
        return try {
            val result = commandEngine.runCommandSyncIfReady(
                command = checker.command(quoted),
                // 必须带工作区路径：不带时容器实例不会把工作区绑到 /root/workspace，
                // 检查器只会报 `No such file or directory`（2026-10-05 实机踩到，靠日志定位）。
                // 同 SearchCodeTool 的取法：编排层没传就用当前工作区。
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = TIMEOUT_MS,
            ) ?: run {
                // 「静默跳过」最难看懂：日志里留一笔，用户问「怎么没反应」时有据可查。
                FileLogger.i(TAG, "容器未就绪，跳过语法检查: $path")
                return emptyList()
            }
            if (result.exitCode == 0) return emptyList()
            // 127 = shell 找不到命令（容器里没装这个检查器）——App 内没有提示，只写日志。
            if (result.exitCode == COMMAND_NOT_FOUND) {
                FileLogger.i(TAG, "容器里没有 ${checker.binaryName}，跳过语法检查: $path")
                return emptyList()
            }
            val diagnostics = checker.parse(result.output).take(MAX_DIAGNOSTICS)
            if (diagnostics.isEmpty()) {
                // 非 0 退出却解析不出行号（路径不对、检查器报的是别的错）——把尾部输出记下来。
                FileLogger.i(
                    TAG,
                    "检查器退出码 ${result.exitCode}、解析不出诊断: $path；输出尾部: ${result.output.takeLast(200)}"
                )
            }
            diagnostics
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "语法检查失败: $path", e)
            emptyList()
        }
    }

    private fun checkerFor(path: String): Checker? {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return Checker.entries.firstOrNull { ext in it.extensions }
    }

    private companion object {
        const val TAG = "EditorDiagnostics"
    }
}

/** 支持的检查器：扩展名 → 命令构造 + 输出解析。[binaryName] 仅用于日志（缺哪个检查器时点名）。 */
private enum class Checker(val extensions: Set<String>, val binaryName: String) {
    PYTHON(setOf("py", "pyw", "pyi"), "python3") {
        override fun command(quotedFile: String): String =
            "PYTHONPYCACHEPREFIX=$PYCACHE_PREFIX python3 -m py_compile $quotedFile"

        override fun parse(output: String): List<EditorDiagnostic> = parsePython(output)
    },
    LUA(setOf("lua"), "luac") {
        override fun command(quotedFile: String): String = "luac -p $quotedFile"

        override fun parse(output: String): List<EditorDiagnostic> = parseLua(output)
    };

    abstract fun command(quotedFile: String): String

    abstract fun parse(output: String): List<EditorDiagnostic>
}

/**
 * 解析 `py_compile` 的输出。两种形态：
 * ```
 *   File "x.py", line 4
 *     x = (
 *         ^
 * SyntaxError: '(' was never closed
 * ```
 * ```
 * Sorry: IndentationError: expected an indented block after function definition on line 1 (a2.py, line 2)
 * ```
 * 前者先记住 `File …, line N`，遇到 `XxxError: …` 时把整行标成诊断；后者（3.13 起的缩进类错误）
 * 行号在尾部括号里，需要单独认一遍。`[Errno 2]` 之类没有行号的报错不匹配、直接跳过，避免误报。
 */
private fun parsePython(output: String): List<EditorDiagnostic> {
    val result = ArrayList<EditorDiagnostic>()
    var pendingLine = 0
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        val sorry = PY_SORRY_LINE.find(line)
        if (sorry != null) {
            val errorLine = sorry.groupValues[4].toIntOrNull() ?: 0
            if (errorLine > 0) {
                result += EditorDiagnostic.wholeLine(
                    line = errorLine,
                    severity = DiagnosticSeverity.ERROR,
                    message = buildPythonMessage(sorry.groupValues[1], sorry.groupValues[2]),
                )
                pendingLine = 0
                continue
            }
        }
        val fileMatch = PY_FILE_LINE.find(raw)
        if (fileMatch != null) {
            pendingLine = fileMatch.groupValues[1].toIntOrNull() ?: 0
            continue
        }
        val messageMatch = PY_ERROR_MESSAGE.find(line)
        if (messageMatch != null && pendingLine > 0) {
            result += EditorDiagnostic.wholeLine(
                line = pendingLine,
                severity = DiagnosticSeverity.ERROR,
                message = buildPythonMessage(messageMatch.groupValues[1], messageMatch.groupValues[2]),
            )
            pendingLine = 0
        }
    }
    return result
}

/** Python 异常名 → 中文。仅覆盖最常见的几类，匹配不到就回退英文原名。 */
private val PY_EXCEPTION_ZH: Map<String, String> = mapOf(
    "SyntaxError" to "语法错误",
    "IndentationError" to "缩进错误",
    "TabError" to "Tab 与空格混用错误",
    "NameError" to "名称未定义",
    "TypeError" to "类型错误",
    "ValueError" to "值错误",
    "KeyError" to "键不存在",
    "IndexError" to "索引越界",
    "AttributeError" to "属性不存在",
    "ImportError" to "导入失败",
    "ModuleNotFoundError" to "模块未找到",
    "FileNotFoundError" to "文件未找到",
    "ZeroDivisionError" to "除零错误",
    "OverflowError" to "数值溢出",
    "UnicodeError" to "编码错误",
    "UnicodeDecodeError" to "解码错误",
    "UnicodeEncodeError" to "编码错误",
    "RecursionError" to "递归过深",
    "RuntimeError" to "运行时错误",
    "MemoryError" to "内存不足",
)

/**
 * Python 编译错误的细节部分 → 中文。精确匹配优先（`py_compile` 的消息大多是无参数的固定句）。
 * 只覆盖常见说法，命中不了的保留英文原名——宁可显示原文，也不乱猜。
 */
private val PY_DETAIL_ZH: Map<String, String> = mapOf(
    "invalid syntax" to "语法错误",
    "invalid syntax. Perhaps you forgot a comma?" to "语法错误，可能漏了一个逗号",
    "invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to "语法错误，是不是该用 '==' 或 ':=' 而不是 '='？",
    "unexpected indent" to "多了缩进",
    "unexpected unindent" to "缩进提前结束了",
    "unindent does not match any outer indentation level" to "缩进层级与任何外层都对不上",
    "unexpected EOF while parsing" to "代码没写完，文件就结束了",
    "EOL while scanning string literal" to "字符串在行尾没有闭合",
    "invalid decimal literal" to "无效的十进制字面量",
    "invalid hexadecimal literal" to "无效的十六进制字面量",
    "invalid octal literal" to "无效的八进制字面量",
    "invalid binary literal" to "无效的二进制字面量",
    "leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers" to
        "十进制整数不能以 0 开头，八进制请加 0o 前缀",
    "cannot assign to literal" to "不能给字面量赋值",
    "cannot assign to literal here. Maybe you meant '==' instead of '='?" to "这里不能给字面量赋值，是不是该用 '==' 而不是 '='？",
    "cannot assign to expression here. Maybe you meant '==' instead of '='?" to "这里不能给表达式赋值，是不是该用 '==' 而不是 '='？",
    "cannot assign to function call" to "不能给函数调用的结果赋值",
    "cannot delete literal" to "不能删除字面量",
    "cannot delete function call" to "不能删除函数调用",
    "unexpected character after line continuation character" to "续行符（反斜杠）后面还有多余的字符",
    "source code string cannot contain null bytes" to "源代码里不能有 NUL 空字节",
    "'return' outside function" to "'return' 用在了函数外",
    "'yield' outside function" to "'yield' 用在了函数外",
    "'break' outside loop" to "'break' 用在了循环外",
    "break outside loop" to "'break' 用在了循环外",
    "'continue' not properly in loop" to "'continue' 没有放在循环里",
    "'await' outside function" to "'await' 用在了函数外",
    "'await' outside async function" to "'await' 用在了非 async 函数里",
    "'async for' outside async function" to "'async for' 用在了非 async 函数里",
    "'async with' outside async function" to "'async with' 用在了非 async 函数里",
    "non-default argument follows default argument" to "有默认值的参数后面不能再跟没有默认值的参数",
    "positional argument follows keyword argument" to "位置参数不能跟在关键字参数后面",
    "from __future__ imports must occur at the beginning of the file" to "from __future__ 导入必须放在文件开头",
    "import * only allowed at module level" to "import * 只能写在模块顶层",
    "illegal target for annotation" to "这里不能写类型标注",
    "only single target (not tuple) can be annotated" to "类型标注只能针对单个目标，不能是元组",
    "too many statically nested blocks" to "代码块的静态嵌套层数过多",
)

/** 带参数的 Python 细节（如 `'(' was never closed`）→ 中文；按顺序匹配，命中即用。 */
private val PY_DETAIL_PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
    Regex("""'(.+)' was never closed""") to { m -> "「${m.groupValues[1]}」没有闭合" },
    Regex("""closing parenthesis '(.+)' does not match opening parenthesis '(.+)'""") to { m ->
        "右括号「${m.groupValues[1]}」与左括号「${m.groupValues[2]}」对不上"
    },
    Regex("""unmatched '(.+)'""") to { m -> "多了一个「${m.groupValues[1]}」" },
    Regex("""expected an indented block after (.+) on line (\d+)""") to { m ->
        "第 ${m.groupValues[2]} 行的 ${pythonBlockOwner(m.groupValues[1])} 后面需要缩进的代码块"
    },
    Regex("""expected an indented block""") to { _ -> "这里需要缩进的代码块" },
    Regex("""expected '(.+)'""") to { m -> "缺少「${m.groupValues[1]}」" },
    Regex("""unterminated string literal \(detected at line (\d+)\)""") to { m ->
        "字符串没有闭合（在第 ${m.groupValues[1]} 行发现）"
    },
    Regex("""invalid character '(.+)' \(U\+[0-9A-Fa-f]+\)""") to { m -> "非法字符「${m.groupValues[1]}」" },
    Regex("""invalid digit '(.+)' in (\w+) literal""") to { m ->
        "${m.groupValues[2]} 字面量里出现了非法数字「${m.groupValues[1]}」"
    },
    Regex("""missing parentheses in call to '(.+)'""") to { m -> "调用「${m.groupValues[1]}」时少了括号" },
    Regex("""duplicate argument '(.+)' in function definition""") to { m ->
        "函数定义里重复出现了参数「${m.groupValues[1]}」"
    },
    Regex("""no binding for nonlocal '(.+)' found""") to { m -> "找不到 nonlocal「${m.groupValues[1]}」对应的外层变量" },
    Regex("""name '(.+)' is assigned to before (global|nonlocal) declaration""") to { m ->
        "「${m.groupValues[1]}」在 ${m.groupValues[2]} 声明之前就被赋值了"
    },
)

/** 把 `py_compile` 的细节部分转成中文；识别不了的原样返回。 */
private fun localizePythonDetail(detail: String): String {
    if (detail.isEmpty()) return detail
    PY_DETAIL_ZH[detail]?.let { return it }
    PY_DETAIL_PATTERNS.forEach { (regex, render) ->
        val match = regex.matchEntire(detail) ?: return@forEach
        return render(match)
    }
    return detail
}

/**
 * 异常名 + 细节 → 展示文本：两段各自本地化，识别不了的保留原文。
 *
 * 细节的中文可能与异常名撞词（`SyntaxError: invalid syntax` 会得到「语法错误」+「语法错误」），
 * 撞上就只留一份，别让提示里同一句重复两遍（2026-10-05 实机截图暴露）。
 */
private fun buildPythonMessage(exception: String, detail: String): String {
    val prefix = PY_EXCEPTION_ZH[exception] ?: exception
    val localized = localizePythonDetail(detail.trim())
    val message = when {
        localized.isEmpty() || localized == prefix -> prefix
        localized.startsWith(prefix) -> localized
        else -> "$prefix：$localized"
    }
    return message.take(MAX_MESSAGE_LENGTH)
}

/** 缩进错误里「谁的块」：`'if' statement` → `if 语句`，`function definition` → `函数定义`。 */
private fun pythonBlockOwner(raw: String): String {
    Regex("""'(\w+)' statement""").matchEntire(raw)?.let { return "${it.groupValues[1]} 语句" }
    return when (raw) {
        "function definition" -> "函数定义"
        "class definition" -> "类定义"
        else -> raw
    }
}

/**
 * 解析 `luac -p` 的输出，形如 `lua: x.lua:3: unexpected symbol near '<eof>'`：
 * 取第一个 `:<数字>:` 作为行号，其余作为提示。`cannot open` 之类无行号的信息跳过。
 */
private fun parseLua(output: String): List<EditorDiagnostic> {
    val result = ArrayList<EditorDiagnostic>()
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.contains("cannot open", ignoreCase = true)) continue
        val match = LUA_LOCATION.find(line) ?: continue
        val lineNumber = match.groupValues[1].toIntOrNull() ?: continue
        val message = localizeLuaMessage(match.groupValues[2].trim()).take(MAX_MESSAGE_LENGTH)
        if (message.isEmpty()) continue
        result += EditorDiagnostic.wholeLine(
            line = lineNumber,
            severity = DiagnosticSeverity.ERROR,
            message = message,
        )
    }
    return result
}

/** `luac -p` 的提示 → 中文；识别不了的保留原文。`near` 记号可能是 `'<eof>'`。 */
private val LUA_MESSAGE_PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
    Regex("""'(.+)' expected \(to close '(.+)' at line (\d+)\) near (.+)""") to { m ->
        "需要「${m.groupValues[1]}」来闭合第 ${m.groupValues[3]} 行的「${m.groupValues[2]}」，" +
            "但在 ${luaToken(m.groupValues[4])} 附近"
    },
    Regex("""'(.+)' expected \(to close '(.+)' at line (\d+)\)""") to { m ->
        "需要「${m.groupValues[1]}」来闭合第 ${m.groupValues[3]} 行的「${m.groupValues[2]}」"
    },
    Regex("""'<name>' expected near (.+)""") to { m -> "这里需要一个名字，但在 ${luaToken(m.groupValues[1])} 附近" },
    Regex("""'(.+)' expected near (.+)""") to { m -> "需要「${m.groupValues[1]}」，但在 ${luaToken(m.groupValues[2])} 附近" },
    Regex("""unexpected symbol near (.+)""") to { m -> "${luaToken(m.groupValues[1])} 附近有无法识别的符号" },
    Regex("""unfinished string near (.+)""") to { m -> "字符串没有结束：从 ${luaToken(m.groupValues[1])} 开始" },
    Regex("""malformed number near (.+)""") to { m -> "数字写法不对：${luaToken(m.groupValues[1])}" },
    Regex("""break outside loop at line (\d+)""") to { m -> "'break' 用在了循环外（第 ${m.groupValues[1]} 行）" },
    Regex("""no visible label '(.+)' for <goto> at line (\d+)""") to { m ->
        "第 ${m.groupValues[2]} 行的 <goto> 找不到标签「${m.groupValues[1]}」"
    },
    Regex("""function at line (\d+) has more than (\d+) local variables""") to { m ->
        "第 ${m.groupValues[1]} 行的函数局部变量超过了 ${m.groupValues[2]} 个"
    },
    Regex("""chunk has too many syntax levels""") to { _ -> "代码嵌套层级过多" },
)

/** 把 `luac` 的提示转成中文；识别不了的原样返回。 */
private fun localizeLuaMessage(message: String): String {
    if (message.isEmpty()) return message
    LUA_MESSAGE_PATTERNS.forEach { (regex, render) ->
        val match = regex.matchEntire(message) ?: return@forEach
        return render(match)
    }
    return message
}

/** `near` 后面的记号：luac 用 `'<eof>'` 表示文件结尾，其余去掉单引号后加「」。 */
private fun luaToken(raw: String): String {
    val inner = raw.trim().removeSurrounding("'")
    return if (inner == "<eof>") "「文件结尾」" else "「$inner」"
}
