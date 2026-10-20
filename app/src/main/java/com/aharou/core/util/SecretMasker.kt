package com.aharou.core.util

/**
 * 注入给模型的记忆正文脱敏。
 *
 * 只替换、不丢弃：主人需要时仍能用 `memory(action="read")` 取回原文，掩码只是不把真值
 * 摊进每一轮请求里。规则聚焦「凭据形态」——`sk-` 前缀密钥、`Bearer` 令牌、`password=`
 * 之类的赋值、私钥块；IP、域名、绝对路径属于项目事实（254 的地址、cnb.cool、容器路径），
 * 故意不匹配，否则检索会被一起打残。
 *
 * 与 `AILogger` 的关系：那边只处理 data URL 与超长 base64，管的是日志不被二进制撑爆；
 * 这里的判定标准是「是不是凭据」。两者不重叠，别把两套正则混在一起改。
 */
internal object SecretMasker {

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = PRIVATE_KEY_BLOCK.replace(text) { m -> "[已脱敏: 私钥块 ${m.value.length} 字符]" }
        out = BEARER_TOKEN.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}${maskValue(m.groupValues[3])}"
        }
        out = ASSIGNED_SECRET.replace(out) { m ->
            val quote = m.groupValues[3]
            "${m.groupValues[1]}${m.groupValues[2]}$quote${maskValue(m.groupValues[4])}$quote"
        }
        out = PREFIXED_KEY.replace(out) { m -> maskValue(m.value) }
        return out
    }

    /** 保留前 4 位与长度：主人问「是哪个令牌」时分得清，又不给出可用信息。 */
    private fun maskValue(value: String): String =
        "[已脱敏: ${value.take(4)}…共 ${value.length} 位]"

    private val PRIVATE_KEY_BLOCK =
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

    private val BEARER_TOKEN =
        Regex("(?i)\\b(bearer)(\\s+)([A-Za-z0-9._~+/=-]{12,})")

    private val ASSIGNED_SECRET = Regex(
        "(?i)\\b(password|passwd|token|api[_-]?key|apikey|secret|access[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret)\\b(\\s*[=:]\\s*)([\"']?)([A-Za-z0-9._~+/=@#$%^&*-]{8,})\\3"
    )

    private val PREFIXED_KEY = Regex("\\b(sk-(?:ant-|proj-)?[A-Za-z0-9_-]{12,})")
}
