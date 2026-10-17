package com.aharou.feature.agent.domain.memory

/**
 * 记忆文本里的结构化实体抽取：IPv4、域名、绝对路径、带冒号的端口。
 *
 * 这些串一旦被关键词抽取或召回切词按「非字母数字」切开就再也对不上原文
 * （`192.168.1.10` → `192` / `168` / `10`，`/root/workspace` → `root` / `workspace`），
 * 所以先整体抽出来再交给调用方。纯函数、无状态：结果全部小写、去重、按出现位置排序。
 */
object MemoryEntities {
    /** 单段文本最多产出多少个实体：只服务于召回，再多也只是噪音。 */
    const val MAX_ENTITIES = 12

    /** 最短 3 个字符：刚好放行 `:80` 这类两位端口，又挡掉 `:1`、`a.co` 以下的碎片。 */
    private const val MIN_ENTITY_LENGTH = 3
    private const val MAX_ENTITY_LENGTH = 120

    /** 严格四段点分十进制，前后不能再接数字或点，避免把 `1.2.3.4.5` 截成 `1.2.3.4`。 */
    private val IPV4 = Regex("""(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3})(?![\d.])""")

    /** 域名候选：最后一段必须是字母，纯数字的 IP 片段与版本号自然落选。 */
    private val DOMAIN = Regex(
        """(?<![A-Za-z0-9_.-])((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,24})(?![A-Za-z0-9-])"""
    )

    /** 约定要认的路径根，且至少带一个子段：`/root` 本身不算，`/root/x` 才算。 */
    private val ABSOLUTE_PATH = Regex(
        """(?<![\p{L}\p{N}._/-])(/(?:root|home|data|sdcard|storage|etc|opt|var|usr)(?:/[\p{L}\p{N}._@%+~-]+)+)"""
    )

    /** 冒号端口；`10:30` 这类时间由 [isPort] 排除。 */
    private val PORT = Regex("""(:\d{1,5})(?!\d)""")

    /** 常见 TLD：只认这些后缀，避免把 `build.gradle.kts`、`Node.js` 之类误判成域名。 */
    private val COMMON_TLDS = setOf(
        "com", "cn", "net", "org", "top", "shop", "cloud", "io", "dev", "app", "xyz",
        "me", "info", "co", "site", "online", "store", "tech", "ai", "cc", "tv",
        "edu", "gov", "mil", "name", "pro", "club", "vip", "link", "live", "fun",
        "space", "website", "host", "press", "wiki", "work", "run", "page", "blog", "art",
        "design", "zone", "one", "icu", "asia", "mobi", "biz", "id", "jp", "kr",
        "de", "uk", "ru", "fr", "eu", "au", "ca", "us", "in", "hk", "tw", "sg",
        "ch", "nl", "se", "no", "fi", "dk", "pl", "br", "mx", "es", "it", "at",
        "be", "cz", "pt", "gr", "tr", "ua", "vn", "th", "my", "ph", "nz", "za",
        "ar", "cl", "il", "ae", "sa"
    )

    /** 统一入口：按出现位置返回去重后的实体串。 */
    fun extract(text: String, limit: Int = MAX_ENTITIES): List<String> {
        if (text.isBlank() || limit <= 0) return emptyList()
        // 实体必然带 `.`（IP/域名）、`:`（端口）或 `/`（路径）之一。注入排序会逐条反复调这里，
        // 中文散文占了记忆正文本体，先用 O(n) 扫三个字符避开四条正则的固定开销。
        if (!mayContainEntity(text)) return emptyList()
        val hits = ArrayList<Pair<Int, String>>()
        IPV4.findAll(text).forEach { match ->
            val value = match.groupValues[1]
            if (isIpv4(value)) hits += match.range.first to value
        }
        DOMAIN.findAll(text).forEach { match ->
            val value = match.groupValues[1]
            if (value.substringAfterLast('.').lowercase() in COMMON_TLDS) {
                hits += match.range.first to value
            }
        }
        ABSOLUTE_PATH.findAll(text).forEach { match ->
            hits += match.range.first to match.groupValues[1].trimEnd('.', ',', ';', ':', '。', '，')
        }
        PORT.findAll(text).forEach { match ->
            val value = match.groupValues[1]
            if (isPort(text, match.range.first, value)) hits += match.range.first to value
        }
        return hits.sortedBy { it.first }
            .map { it.second.lowercase() }
            .filter { it.length in MIN_ENTITY_LENGTH..MAX_ENTITY_LENGTH }
            .distinct()
            .take(limit)
    }

    /** 实体必含 `.`、`:`、`/` 之一；三者都没有的文本不可能命中任何一条正则。 */
    private fun mayContainEntity(text: String): Boolean =
        text.indexOf('.') >= 0 || text.indexOf(':') >= 0 || text.indexOf('/') >= 0

    /** 每段 0..255 才算 IPv4；`2.27.1` 只有三段，上面正则就不会命中。 */
    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3) return@all false
            val octet = part.toIntOrNull() ?: return@all false
            octet in 0..255
        }
    }

    /**
     * 端口合法性：1..65535，且排除时间写法。
     *
     * `10:30` 的「端口」是 30，冒号前恰好 1~2 位数字，按时间丢弃；
     * `192.168.1.10:8080` 的冒号前是一长串数字且 8080 > 59，不会被误伤。
     */
    private fun isPort(text: String, colonIndex: Int, raw: String): Boolean {
        val port = raw.drop(1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        if (port > 59) return true
        var cursor = colonIndex - 1
        while (cursor >= 0 && text[cursor].isDigit()) cursor--
        return (colonIndex - 1 - cursor) !in 1..2
    }
}
