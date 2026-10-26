package com.aharou.core.privacy

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * 出站脱敏 + 入站回填。
 *
 * 出站把凭据换成 `{{SECRET_xxxxxxxx}}`，入站再换回真值：供应商全程拿不到原文，
 * 主人读到的回复仍是原文。真值只活在本实例的内存映射里，不落盘、不进日志。
 *
 * 占位符由真值的 SHA-256 派生，因此同一真值在同一个实例里永远是同一个占位符。这不是
 * 为了好看——多轮对话会把同一份历史反复重发给供应商，占位符一变 prompt cache 立即全失效，
 * 带 thinking 签名的模型还会因为签名与正文不匹配直接报错。
 *
 * 线程安全用「不可变快照 + CAS」而不是 Mutex：[mask] / [restore] 是普通函数，会被多个
 * 协程直接调用，没有 suspend 上下文让 Mutex 用。每次注册都整份换掉一个不可变 Map，
 * 读路径完全无锁；拷贝成本与已登记条数成正比，正常几条到几十条可以忽略，一次粘贴上万条
 * 凭据时会到几百毫秒级（实测 1MB 含 1.2 万条约 0.6s）。
 *
 * 接线约定（调用方必读）：
 * - 一次会话共用一个实例，换实例等于丢失映射，上一轮的历史就再也回填不回来；
 * - [mask] 在请求体序列化之前调，正文、工具调用参数、工具结果都要过一遍；
 * - [restore] 对正文增量与工具调用参数分别调，流结束时调一次 [flush]——流式会被切在
 *   占位符中间，[restore] 会把可能的半截占位符掐住等下一段，尾巴由 [flush] 吐出。
 */
class OutboundMasker private constructor(private val rules: List<MaskingRule>) {

    private val state = AtomicReference(MaskState.EMPTY)

    /** 被切在占位符中间时留下的那半截，等下一段到达再一起换。 */
    private val pending = AtomicReference("")

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (rule in rules) {
            if (!rule.enabled) continue
            out = rule.regex.replace(out) { match -> replaceMatch(rule, match) }
        }
        return out
    }

    fun restore(text: String): String {
        while (true) {
            val carried = pending.get()
            if (carried.isEmpty() && text.isEmpty()) return text
            val snapshot = state.get()
            // id 全小写，但模型偶尔会把占位符写成大写，回填失败只会让用户看到占位符，
            // 所以顺手按大小写不敏感匹配。
            val replaced = PLACEHOLDER.replace(carried + text) { match ->
                snapshot.valueOf(match.groupValues[1].lowercase()) ?: match.value
            }
            val tailLength = partialTailLength(replaced)
            if (pending.compareAndSet(carried, replaced.takeLast(tailLength))) {
                return replaced.dropLast(tailLength)
            }
        }
    }

    /** 流结束时把 [restore] 掐住的尾巴吐出来；没有待处理内容时返回空串。 */
    fun flush(): String {
        while (true) {
            val carried = pending.get()
            if (carried.isEmpty()) return ""
            if (pending.compareAndSet(carried, "")) return carried
        }
    }

    /** 已登记的不同真值个数（不是掩码出现次数），用于面板与日志观察拦截量。 */
    fun maskedCount(): Int = state.get().size

    fun clear() {
        state.set(MaskState.EMPTY)
        pending.set("")
    }

    private fun replaceMatch(rule: MaskingRule, match: MatchResult): String {
        val value = if (rule.valueGroup == 0) {
            match.value
        } else {
            match.groupValues.getOrNull(rule.valueGroup).orEmpty()
        }
        if (value.isEmpty()) return match.value
        if (rule.validator?.invoke(value) == false) return match.value
        val placeholder = placeholderFor(value)
        if (rule.valueGroup == 0) return placeholder
        val group = match.groups[rule.valueGroup] ?: return match.value
        val offset = group.range.first - match.range.first
        return match.value.substring(0, offset) + placeholder + match.value.substring(offset + value.length)
    }

    private fun placeholderFor(value: String): String {
        while (true) {
            val snapshot = state.get()
            snapshot.idOf(value)?.let { return PREFIX + it + SUFFIX }
            val id = freshId(value, snapshot)
            if (state.compareAndSet(snapshot, snapshot.with(value, id))) {
                return PREFIX + id + SUFFIX
            }
        }
    }

    /**
     * 取一个尚未被别的真值占用的短 id。8 位十六进制是 32 bit，撞车概率约四十亿分之一；
     * 真撞上了就加宽到 12/16 位——已有的占位符不受影响，同一真值的 id 仍然恒定。
     */
    private fun freshId(value: String, snapshot: MaskState): String {
        val digest = sha256Hex(value)
        for (length in ID_LENGTHS) {
            val id = digest.take(length)
            if (snapshot.valueOf(id).let { it == null || it == value }) return id
        }
        var salt = 0
        while (true) {
            val id = digest.take(12) + (salt++).toString(16)
            if (snapshot.valueOf(id).let { it == null || it == value }) return id
        }
    }

    /**
     * 结尾是否是「还没收全的占位符」，返回要掐住的后缀长度。
     *
     * 流式增量会把 `{{SECRET_ab12cd34}}` 切在任意位置，不掐住尾巴的话前半段会原样
     * 漏给用户，后半段到齐时已经拼不回来了。只认 `{{SECRET_` + 十六进制这个形态，
     * 正文里普通的 `{{模板变量}}`、未闭合的 `{{ARG` 都不会被误扣。
     */
    private fun partialTailLength(text: String): Int {
        val longest = PREFIX.length + MAX_ID_LENGTH + SUFFIX.length
        val from = maxOf(0, text.length - longest)
        // 从最左边的 '{' 开始找：{ { 被切成两段时，只看右边那个会把第一个花括弧提前吐出去，
        // 占位符就再也拼不回来了。
        for (i in from until text.length) {
            if (text[i] != '{') continue
            val tail = text.substring(i)
            if (PLACEHOLDER.matches(tail)) continue
            if (isPlaceholderPrefix(tail)) return tail.length
        }
        return 0
    }

    private fun isPlaceholderPrefix(tail: String): Boolean {
        if (PREFIX.startsWith(tail)) return true
        if (!tail.startsWith(PREFIX)) return false
        var body = tail.substring(PREFIX.length)
        var braces = 0
        while (body.endsWith("}")) {
            body = body.dropLast(1)
            braces++
        }
        return braces <= SUFFIX.length &&
            body.length <= MAX_ID_LENGTH &&
            body.all { isHexDigit(it) }
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    companion object {
        fun create(rules: List<MaskingRule> = MaskingRules.defaultRules()): OutboundMasker =
            OutboundMasker(rules)

        private const val PREFIX = "{{SECRET_"
        private const val SUFFIX = "}}"
        private const val MAX_ID_LENGTH = 16
        private const val HEX_DIGITS = "0123456789abcdef"
        private val ID_LENGTHS = intArrayOf(8, 12, MAX_ID_LENGTH)
        private val PLACEHOLDER = Regex("\\{\\{SECRET_([0-9a-fA-F]{8,16})\\}\\}")
    }
}

/** 不可变快照：读路径直接读，写路径整份替换，靠 CAS 保证不丢更新。 */
private class MaskState(
    private val valueToId: Map<String, String>,
    private val idToValue: Map<String, String>,
) {
    val size: Int get() = idToValue.size

    fun idOf(value: String): String? = valueToId[value]

    fun valueOf(id: String): String? = idToValue[id]

    fun with(value: String, id: String): MaskState =
        MaskState(valueToId + (value to id), idToValue + (id to value))

    companion object {
        val EMPTY = MaskState(emptyMap(), emptyMap())
    }
}
