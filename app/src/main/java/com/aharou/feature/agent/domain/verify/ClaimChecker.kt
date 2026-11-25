package com.aharou.feature.agent.domain.verify

import com.aharou.core.util.FileLogger

/** 可机器核验的断言类型。只收这三类，语义判断不在这里做。 */
enum class ClaimKind {
    /** 引号里的一段文字（中英文引号都算）。 */
    QUOTE,

    /** 形如 `app/src/main/.../X.kt` 的文件路径。 */
    PATH,

    /** http/https 链接。 */
    URL
}

/** 一条没能在本轮证据里找到出处的断言。 */
data class UngroundedClaim(
    val kind: ClaimKind,
    val text: String,
    val reason: String
)

/**
 * 一次落地检查的结果。
 *
 * [checked] 是实际参与比对的可核验断言数（代码块、工具名、标识符已排除），
 * [note] 是给 UI/日志看的一句话结论。
 */
data class CheckResult(
    val checked: Int,
    val ungrounded: List<UngroundedClaim>,
    val note: String
) {
    val passed: Boolean get() = ungrounded.isEmpty()
}

/**
 * 回答落地检查：把回答里「引文 / 路径 / 链接」三类可逐字比对的东西，拿到本轮工具证据里找一遍。
 *
 * **只报告不阻断**（项目红线）：结果只用于提示，不得影响回答输出、不得拦下消息；
 * 内部任何异常都降级成「本次没检查」，绝不抛给调用方。
 *
 * 刻意不做的判断：语义是否成立、数字是否一致、结论是否由证据推出——这些机器判不准，
 * 硬判只会制造「看起来在把关、实际在造噪音」的假象。
 */
object ClaimChecker {

    private const val TAG = "ClaimChecker"

    /** 太短的引文多半是术语或语气词（「好的」「注意」），比对它们只会制造误报。 */
    private const val MIN_QUOTE_CHARS = 4

    /** 证据片段之间用换行拼接：不让两条证据的边界粘成一个假匹配。 */
    private const val HAYSTACK_SEPARATOR = "\n"

    fun check(answer: String, evidence: List<Evidence>): CheckResult {
        if (answer.isBlank()) return CheckResult(0, emptyList(), "回答为空，未检查")
        if (evidence.isEmpty()) return CheckResult(0, emptyList(), "本轮没有工具证据，跳过落地检查")
        return runCatching { doCheck(answer, evidence) }.getOrElse {
            // 连日志都可能不可用（纯 JVM 单测里没有 android.util.Log），再包一层保证不抛。
            runCatching { FileLogger.w(TAG, "落地检查内部失败，本次按未检查处理", it) }
            CheckResult(0, emptyList(), "落地检查未完成：${it.javaClass.simpleName}")
        }
    }

    private fun doCheck(answer: String, evidence: List<Evidence>): CheckResult {
        val scannable = stripCodeBlocks(answer)
        val haystack = evidence.joinToString(HAYSTACK_SEPARATOR) { it.excerpt }
        val claims = LinkedHashMap<String, ClaimKind>()

        // 先收 URL 与路径，再收引文：同一条文本被多种规则命中时保留更具体的那一类
        // （引号里的链接按 URL 报，而不是含糊地报成「引文」），所以 putIfAbsent 的顺序很重要。
        val urlRanges = ArrayList<IntRange>()
        for (match in URL_RE.findAll(scannable)) {
            val url = match.value.trimUrlTail()
            if (url.length >= MIN_URL_CHARS) {
                claims.putIfAbsent(url, ClaimKind.URL)
                urlRanges += match.range
            }
        }
        for (match in PATH_TOKEN_RE.findAll(scannable)) {
            val token = match.value
            // URL 里的片段（https://a/b.kt 的 //a/b.kt）不该再按文件路径报一次。
            if (urlRanges.any { match.range.first in it }) continue
            if (!looksLikePath(token)) continue
            claims.putIfAbsent(token, ClaimKind.PATH)
        }
        for (match in QUOTE_RE.findAll(scannable)) {
            val text = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: continue
            if (text.length < MIN_QUOTE_CHARS) continue
            if (shouldSkipQuote(text)) continue
            claims.putIfAbsent(text, ClaimKind.QUOTE)
        }

        val ungrounded = claims.mapNotNull { (text, kind) ->
            if (haystack.contains(text)) null
            else UngroundedClaim(kind = kind, text = text, reason = reasonOf(kind))
        }
        return CheckResult(
            checked = claims.size,
            ungrounded = ungrounded,
            note = noteOf(claims.size, ungrounded.size)
        )
    }

    /**
     * 代码块整体跳过。
     *
     * 代码里的引号、路径、链接是「展示给别人看的代码」，不是「宣称工具核实过的事实」——
     * 拿它们去证据里找必然是误报。未闭合的 ``` 之后也按代码处理：回答被截断时常停在半个
     * 代码块里，那时后面剩下的内容同样不该参与判定。
     */
    private fun stripCodeBlocks(answer: String): String {
        val withoutFenced = FENCED_CODE_RE.replace(answer, " ")
        val open = withoutFenced.indexOf("```")
        return if (open >= 0) withoutFenced.substring(0, open) else withoutFenced
    }

    /**
     * 引文的过滤条件。
     *
     * - 工具名/标识符：模型常写 `"readFile"`、`"status"` 这类术语，引号只是强调，逐字比对没有意义。
     *   纯 ASCII 的单 token 一律跳过（工具名都是这种形态）；其中的路径/链接由各自规则另行核验，不会漏。
     * - 含省略号的引文：模型转述时用「……」拼接两段不相邻的原文，这种文本永远不可能逐字命中。
     */
    private fun shouldSkipQuote(text: String): Boolean {
        if (text.contains("…") || text.contains("...")) return true
        return IDENTIFIER_RE.matches(text)
    }

    private fun looksLikePath(token: String): Boolean {
        if (token.length < MIN_PATH_CHARS) return false
        if (!token.contains('/')) return false
        if (token.startsWith("//")) return false
        if (!EXTENSION_RE.containsMatchIn(token)) return false
        val lastSegment = token.substringAfterLast('/')
        // 只有扩展名、没有文件名的末段（`foo/.kt`）不是路径。
        return lastSegment.substringBeforeLast('.', "").isNotBlank()
    }

    private fun reasonOf(kind: ClaimKind): String = when (kind) {
        ClaimKind.QUOTE -> "引文未出现在本轮工具结果中：可能来自上文、记忆或模型自身知识，也可能是转述"
        ClaimKind.PATH -> "路径未出现在本轮工具结果中：可能来自已有上下文、用户原话或推测"
        ClaimKind.URL -> "链接未出现在本轮工具结果中：可能来自记忆或推测"
    }

    private fun noteOf(checked: Int, ungrounded: Int): String = when {
        checked == 0 -> "未发现可机器核验的引用（引文 / 路径 / 链接）"
        ungrounded == 0 -> "检查 $checked 处引用，均能在本轮工具证据中找到"
        else -> "检查 $checked 处引用，$ungrounded 处未能在本轮工具证据中找到"
    }

    private fun String.trimUrlTail(): String =
        trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '。', '，', '；', '：', '）', '】', '」')

    /** 闭合的 ``` 段（非贪婪，允许跨行）。 */
    private val FENCED_CODE_RE = Regex("```[\\s\\S]*?```")

    /** 英文双引号 / 中文弯引号 / 直角引号三种；不允许跨行，避免把整段正文吞成一条引文。 */
    private val QUOTE_RE = Regex("\"([^\"\\n]{4,})\"|“([^”\\n]{4,})”|「([^」\\n]{4,})」")

    private val URL_RE = Regex("https?://[^\\s<>\"'`）)】]+", RegexOption.IGNORE_CASE)

    /** 只取「不含空格、冒号、引号」的连续片段，标点会把路径截断成可信的一段。 */
    private val PATH_TOKEN_RE = Regex("[A-Za-z0-9_./~\\-]+")

    /** 扩展名 1~8 位：覆盖 `.kt` / `.gradle` / `.gitignore` 之外的长尾，又不会被长数字误命。 */
    private val EXTENSION_RE = Regex("\\.[A-Za-z0-9]{1,8}$")

    /** 纯 ASCII 标识符/字段名/工具名（可带 `.` `/` `-` `:` `_`），带空格的自然语言不算。 */
    private val IDENTIFIER_RE = Regex("[A-Za-z_][A-Za-z0-9_.:/-]*")

    /** 低于 `https://a` 长度的都不算链接，避免把 `"https://"` 这种空壳报出来。 */
    private const val MIN_URL_CHARS = 9

    private const val MIN_PATH_CHARS = 4
}
