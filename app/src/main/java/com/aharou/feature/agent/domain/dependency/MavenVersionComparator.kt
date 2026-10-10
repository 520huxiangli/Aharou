package com.aharou.feature.agent.domain.dependency

/**
 * Maven 版本比较与正式版挑选。
 *
 * 语义：数字段按数值比（`1.10 > 1.9`）、预发布后缀小于同数字的正式版（`2.0.0-rc1 < 2.0.0`）；
 * 尾部像 `.Final` / `.RELEASE` 这类「正式版」后缀被视同无后缀。策略保守——只推荐把稳定版提到
 * 更高的稳定版（[highestStable] 会滤掉预发布），不主动把用户拉到 `-rc` / `SNAPSHOT`。
 */
object MavenVersionComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val (coreA, qualA) = split(a)
        val (coreB, qualB) = split(b)
        val coreCmp = compareCore(coreA, coreB)
        if (coreCmp != 0) return coreCmp
        return compareQualifier(qualA, qualB)
    }

    /** 是否是预发布版本（带 alpha/beta/rc/milestone/SNAPSHOT 等后缀）。 */
    fun isPrerelease(version: String): Boolean {
        val (_, qualifier) = split(version)
        if (qualifier.isEmpty()) return false
        return PRERELEASE_PREFIX.containsMatchIn(qualifier.lowercase())
    }

    /** 集合里最高的正式版；无正式版时返回 null。 */
    fun highestStable(versions: Collection<String>): String? =
        versions.filter { !isPrerelease(it) }.maxWithOrNull(this)

    /** 集合里绝对值最高的版本（含预发布）。 */
    fun latest(versions: Collection<String>): String? = versions.maxWithOrNull(this)

    private fun split(version: String): Pair<String, String> {
        var v = version.trim()
        val plus = v.indexOf('+')
        if (plus >= 0) v = v.substring(0, plus)

        var qualifier = ""
        val dash = v.indexOf('-')
        if (dash >= 0) {
            qualifier = v.substring(dash + 1)
            v = v.substring(0, dash)
        }

        // 把 core 尾部非数字段（如 1.0.0.Final 的 Final）挪进 qualifier
        val tokens = v.split('.').toMutableList()
        while (tokens.size > 1 && tokens.last().toLongOrNull() == null) {
            val moved = tokens.removeAt(tokens.size - 1)
            qualifier = if (qualifier.isEmpty()) moved else "$moved-$qualifier"
        }
        v = tokens.joinToString(".")

        if (qualifier.lowercase() in RELEASE_WORDS) qualifier = ""
        return v to qualifier
    }

    private fun compareCore(a: String, b: String): Int {
        val ta = a.split('.')
        val tb = b.split('.')
        val n = maxOf(ta.size, tb.size)
        for (i in 0 until n) {
            val sa = ta.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "0"
            val sb = tb.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "0"
            val cmp = compareToken(sa, sb)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun compareToken(a: String, b: String): Int {
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> 1
            nb != null -> -1
            else -> a.lowercase().compareTo(b.lowercase())
        }
    }

    private fun compareQualifier(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return 1
        if (b.isEmpty()) return -1
        val ra = orderOf(a)
        val rb = orderOf(b)
        if (ra != rb) return ra.compareTo(rb)
        return a.lowercase().compareTo(b.lowercase())
    }

    private fun orderOf(qualifier: String): Int {
        val lower = qualifier.lowercase()
        val match = QUALIFIER_PREFIX.find(lower)
        val name = match?.groupValues?.get(1) ?: lower
        val number = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val base = QUALIFIER_ORDER[name] ?: DEFAULT_ORDER
        return base * 100_000 + number.coerceIn(0, 99_999)
    }

    private val RELEASE_WORDS = setOf("final", "ga", "release", "stable")
    private val PRERELEASE_PREFIX = Regex(
        "^(alpha|beta|rc|cr|milestone|snapshot|preview|pre|ea|dev|nightly|canary|m\\d)"
    )
    private val QUALIFIER_PREFIX = Regex("([a-z]+)\\D*(\\d*)")
    private val DEFAULT_ORDER = 5
    private val QUALIFIER_ORDER = mapOf(
        "alpha" to 0,
        "beta" to 1,
        "milestone" to 2,
        "m" to 2,
        "rc" to 3,
        "cr" to 3,
        "snapshot" to 4,
        "sp" to 6
    )
}
