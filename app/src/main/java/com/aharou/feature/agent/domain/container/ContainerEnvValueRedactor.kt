package com.aharou.feature.agent.domain.container

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/**
 * 环境变量值脱敏：把用户注入容器的变量值从「要交给模型的文本」里抹掉。
 *
 * 为什么需要：这些值由 EnvVarRepository 加密存着，注入容器后就是普通文本，容器里一句 `env`、
 * `echo $TOKEN`、`curl -H "Authorization: Bearer $KEY"` 就会把它原样带进会话上下文、日志与记忆；
 * 设置页那个「打码」只作用于列表 UI，对命令输出没有任何作用，用户完全无感。
 *
 * 与 [ContainerEnvSanitizer] 的分工：那个按**名字**决定哪些继承来的变量不许进容器（注入前），
 * 本对象按**值**抹掉已经出现在输出里的内容（执行后），两者不重叠、互不替代。
 *
 * 几处刻意的取舍：
 *  - 值短于 [MIN_VALUE_LENGTH] 不参与替换：`1`、`yes`、`/tmp` 这类短值在正常输出里满地都是，
 *    替换了只会把有用的内容糊掉。
 *  - 候选值按长度倒序拼成一条正则：短值先替换会把长值切碎，碎片再也匹配不上。
 *  - 多行值按行拆开各自参与：读文件类输出每行前面带行号，整段值根本匹配不到。
 *  - 额外生成 JSON 转义与 `\uXXXX` 转义形态，而不是「解析 JSON 再递归替换后重新序列化」：
 *    后一种会重排数字与空白，模型看到的就不是命令的真实输出了；而这层编码正是要堵的绕过口。
 *  - 代理类变量的 `user:password`、原始 userinfo、`base64(user:password)` 不做长度过滤：
 *    Basic 认证里那串 base64 是可逆的，再短的口令也还原得出来。
 *  - 正则只编译一次并按变量表指纹缓存：变量表只在用户改设置时变，而本对象每条命令结束都要跑。
 */
object ContainerEnvValueRedactor {

    /** 替换标记。不用 `***`：模型能一眼看出这是被隐藏的凭据，而不是自己的输出。 */
    const val MASK = "[已隐藏:环境变量]"

    /**
     * 命中后追加给模型的一句说明。
     *
     * 这是**工具结果文本**（模型可见），不是用户界面文案，故按规则直接写在常量里、不进 strings.xml。
     */
    const val NOTICE = "（输出中与环境变量同名的值已隐藏，不要尝试用其它方式还原它们）"

    /** 低于该长度的值不参与替换，避免误伤正常文本。 */
    private const val MIN_VALUE_LENGTH = 5

    /** 需要生成 `\uXXXX` 变体的值长度上限：再长只是白白拉大正则，收益趋于零。 */
    private const val UNICODE_VARIANT_MAX_LENGTH = 64

    /** 携带 userinfo 的代理变量名（小写比较）。 */
    private val PROXY_NAMES = setOf("http_proxy", "https_proxy", "all_proxy", "ftp_proxy")

    /**
     * @property text 替换后的文本（未命中时与入参同一个内容）
     * @property hit 是否真的替换过
     */
    data class Result(val text: String, val hit: Boolean)

    /** 编译结果按变量表指纹缓存；用指纹而不是直接缓存变量表，省下缓存里的明文副本。 */
    private class Compiled(val fingerprint: String, val pattern: Regex?)

    @Volatile
    private var cached: Compiled? = null

    /**
     * 把 [text] 里出现的 [variables] 值替换成 [MASK]。
     *
     * 变量表取自 `EnvVarRepository.asMap()`（只含已启用的项），与真正注入容器的那份一致。
     */
    fun redact(text: String, variables: Map<String, String>): Result {
        if (text.isEmpty() || variables.isEmpty()) return Result(text, false)
        val pattern = compiledFor(variables).pattern ?: return Result(text, false)
        val masked = pattern.replace(text, MASK)
        if (masked == text) return Result(text, false)
        return Result(masked, true)
    }

    /**
     * 往文本末尾追加上 [NOTICE]，供调用方在命中后使用。
     *
     * 单独拆出来是为了让调用方自己决定提示放在退出码之前还是之后。
     */
    fun withNotice(text: String): String = if (text.isEmpty()) NOTICE else "$text\n$NOTICE"

    private fun compiledFor(variables: Map<String, String>): Compiled {
        // 指纹用「名字=值」排序拼接：变量只有几条，拼一次的开销远小于重建正则。
        val fingerprint = variables.entries
            .sortedBy { it.key }
            .joinToString("\u0000") { "${it.key}=${it.value}" }
        cached?.let { if (it.fingerprint == fingerprint) return it }
        val fresh = Compiled(fingerprint, compile(variables))
        cached = fresh
        return fresh
    }

    private fun compile(variables: Map<String, String>): Regex? {
        val always = LinkedHashSet<String>()
        val common = LinkedHashSet<String>()
        variables.forEach { (name, value) ->
            if (value.isBlank()) return@forEach
            if (name.lowercase(Locale.ROOT) in PROXY_NAMES) always.addAll(proxyVariants(value))
            common.addAll(expand(value))
        }
        val candidates = (always + common)
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
        if (candidates.isEmpty()) return null
        return Regex(candidates.joinToString("|") { Regex.escape(it) })
    }

    /** 一个值的全部可匹配形态：原值、多行拆行、JSON 转义与 Unicode 转义。 */
    private fun expand(value: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(candidate: String) {
            if (candidate.length >= MIN_VALUE_LENGTH) out.add(candidate)
        }
        add(value)
        if (value.contains('\n') || value.contains('\r')) {
            value.lineSequence().forEach { add(it.trim()) }
        }
        val escaped = jsonEscape(value)
        if (escaped != value) add(escaped)
        if (value.length <= UNICODE_VARIANT_MAX_LENGTH) add(unicodeEscape(value))
        return out.toList()
    }

    /**
     * 代理地址里的凭据形态。
     *
     * 用户名口令可能经过百分号编码（`p@ss` 写成 `p%40ss`），原样与解码后两种都要覆盖：
     * 输出里出现哪一种都能还原出真口令。这几项不做长度过滤，理由见类注释。
     */
    private fun proxyVariants(value: String): List<String> {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd < 0) return emptyList()
        val at = value.indexOf('@', schemeEnd + 3)
        if (at < 0) return emptyList()
        val userInfo = value.substring(schemeEnd + 3, at)
        val separator = userInfo.indexOf(':')
        val rawUser = if (separator < 0) userInfo else userInfo.substring(0, separator)
        val rawPassword = if (separator < 0) "" else userInfo.substring(separator + 1)
        val user = percentDecode(rawUser)
        val password = percentDecode(rawPassword)
        if (user.isEmpty() && password.isEmpty()) return emptyList()

        val credentials = "$user:$password"
        val out = mutableListOf<String>()
        out.add(Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8)))
        out.add(credentials)
        out.add(userInfo)
        listOf(user, password, rawUser, rawPassword)
            .filter { it.length >= MIN_VALUE_LENGTH }
            .forEach { out.add(it) }
        return out
    }

    /** 百分号解码；刻意不用 URLDecoder——它会把 `+` 解成空格，把口令改坏。 */
    private fun percentDecode(text: String): String {
        if (text.indexOf('%') < 0) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unicodeEscape(value: String): String {
        val sb = StringBuilder(value.length * 6)
        value.forEach { c -> sb.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code)) }
        return sb.toString()
    }
}
