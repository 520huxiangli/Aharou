package com.aharou.core.net

import com.aharou.core.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 容器内域名解析兜底。
 *
 * 国内网络常见域名被 DNS 污染（github.com、cnb.cool 都遇到过），容器内 git clone/pull/push 报
 * 「Could not resolve host」。这里把可用 IP 写进容器 rootfs 的 /etc/hosts 绕开污染。
 *
 * 判据为什么不看「宿主侧能否解析」：宿主走 Android 系统 resolver，容器走 rootfs 里的
 * resolv.conf，两者可以不一致——实测这台设备上宿主能解析 github.com 而容器不能。所以
 * 判据落到容器实际读取的 hosts 文件：没有我们写的条目（或条目过期）才注入。
 *
 * 域名来源有两条：默认名单（GitHub 三件套）与调用方传入的「命令里出现的域名」。
 * 后者让新站点不必改代码就能兜底——git 命令的 URL 里是什么域名，就保证什么域名可解析。
 *
 * IP 来源按优先级：宿主侧解析 → DoH（阿里 HTTP 接口，国内可达）→ 内置种子 IP（仅 GitHub）。
 * 候选都要通过 443 探测才采用，避免写进一个连不上的地址。
 */
object GitHubDnsFallback {

    private const val TAG = "GitHubDnsFallback"

    /** 默认兜底域名。codeload 供 tarball 下载，api 供版本查询。 */
    private val DEFAULT_HOSTS = listOf("github.com", "codeload.github.com", "api.github.com")

    /** 只处理形如 a.b 的域名，挡掉命令里扫到的文件路径、`origin` 之类的假阳性。 */
    private val HOST_RE = Regex("""^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$""")

    private const val DOH_ENDPOINT = "https://223.5.5.5/resolve"
    private const val CONNECT_TIMEOUT_MS = 2500L
    private const val PROBE_TIMEOUT_MS = 1000

    /**
     * 探测总预算。调用链（buildContainerEnv → buildSession）不是 suspend，可能落在主线程，
     * 所以卡死上限比探测精度更重要。宿主侧解析命中缓存时通常在毫秒级，正常路径远用不到这个。
     */
    private const val TOTAL_BUDGET_MS = 3000L

    /** 同一批域名的两次注入检查最小间隔：注入含解析与写文件，不该随每条容器命令跑。 */
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L

    /** 已注入内容的保留时长。过期重新解析，使站点换 IP 时能自愈。 */
    private const val REFRESH_AFTER_MS = 7 * 24 * 3600 * 1000L

    private const val BEGIN_MARKER = "# >>> Aharou DNS 兜底（解析失败时注入，整段删掉即还原）"
    private const val END_MARKER = "# <<< Aharou DNS 兜底"
    private const val STAMP_PREFIX = "# injected-at: "

    /** 种子 IP 只对 GitHub 有意义：别的域名解析不出来就该老老实实失败，不能瞎填。 */
    private val SEED_IPS = listOf(
        "20.205.243.166",
        "20.205.243.165",
        "140.82.112.3",
        "140.82.113.4",
    )

    @Volatile
    private var lastCheckAt = 0L

    @Volatile
    private var lastTargets: List<String> = emptyList()

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 确保 [rootfs] 里的 /etc/hosts 含 [extraHosts] 与默认名单中可用域名的解析。
     *
     * @return 实际写入时返回写入内容，未改动返回 null。
     */
    fun ensureResolvable(rootfs: File, extraHosts: List<String> = emptyList()): String? {
        val targets = (DEFAULT_HOSTS + extraHosts)
            .map { it.trim().lowercase() }
            .filter { HOST_RE.matches(it) }
            .distinct()
        if (targets.isEmpty()) return null

        // 节流按「目标集合」判定：同一批域名 5 分钟内不重复检查，但换了新域名（比如从
        // github 换到 cnb.cool）要立刻处理，否则用户克隆失败后重试依然被跳过。
        val now = System.currentTimeMillis()
        if (targets == lastTargets && now - lastCheckAt < CHECK_INTERVAL_MS) return null
        lastTargets = targets
        lastCheckAt = now

        val hostsFile = File(File(rootfs, "etc").apply { mkdirs() }, "hosts")
        val existing = runCatching { if (hostsFile.isFile) hostsFile.readText() else "" }
            .getOrDefault("")

        // 已注入的块覆盖了本次全部目标且未过期，就不动。新鲜度写进文件本身，进程重启后依然有效
        // （lastCheckAt 只是进程内的节流）。
        val injected = parseBlock(existing)
        if (injected != null && !isStale(injected.stamp, now) && targets.all { it in injected.hosts }) {
            return null
        }

        val resolved = targets.mapNotNull { host -> pickIp(host)?.let { host to it } }
        if (resolved.isEmpty()) {
            FileLogger.w(TAG, "解析不出可用地址，放弃注入：${targets.joinToString()}")
            return null
        }

        // 用标记包裹注入块，替换时整段摘掉即可，不会误删系统原有条目。
        val content = buildString {
            appendLine(BEGIN_MARKER)
            appendLine("$STAMP_PREFIX$now")
            resolved.forEach { (host, ip) -> appendLine("$ip $host") }
            appendLine(END_MARKER)
        }
        return runCatching {
            hostsFile.writeText((stripBlock(existing).trimEnd() + "\n\n" + content).trimStart('\n'))
            FileLogger.i(TAG, "已注入 hosts：${resolved.joinToString { "${it.first}→${it.second}" }}")
            content
        }.onFailure {
            FileLogger.w(TAG, "写入 rootfs hosts 失败：${hostsFile.absolutePath}（${it.message}）")
        }.getOrNull()
    }

    private data class InjectedBlock(val stamp: Long, val hosts: List<String>)

    private fun parseBlock(content: String): InjectedBlock? {
        val lines = content.lineSequence().toList()
        val begin = lines.indexOfFirst { it.trim() == BEGIN_MARKER }
        val end = lines.indexOfFirst { it.trim() == END_MARKER }
        if (begin < 0 || end <= begin) return null
        val body = lines.subList(begin + 1, end)
        val stamp = body.firstOrNull { it.trim().startsWith(STAMP_PREFIX.trim()) }
            ?.substringAfter(STAMP_PREFIX)
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        val hosts = body.filterNot { it.trim().startsWith(STAMP_PREFIX.trim()) }
            .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull() }
        return InjectedBlock(stamp, hosts)
    }

    private fun stripBlock(content: String): String {
        var skipping = false
        return content.lineSequence().filterNot { line ->
            val trimmed = line.trim()
            when {
                trimmed == BEGIN_MARKER -> { skipping = true; true }
                trimmed == END_MARKER -> { skipping = false; true }
                else -> skipping
            }
        }.joinToString("\n")
    }

    private fun isStale(stamp: Long, now: Long): Boolean = now - stamp > REFRESH_AFTER_MS

    /** 按优先级挑一个 443 实测可达的 IP；超出总预算就放弃。 */
    private fun pickIp(host: String): String? {
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        // 宿主侧解析走 Android 系统 resolver，命中缓存时极快，拿到的 IP 也最贴近本机网络。
        // 宿主能解析不代表容器能解析，但 IP 只要真通就能用，所以这里只把它当候选。
        resolveOnHost(host)?.let { if (isReachable(it)) return it }

        if (System.currentTimeMillis() < deadline) {
            resolveViaDoh(host)?.let { if (isReachable(it)) return it }
        }
        if (host in DEFAULT_HOSTS) {
            for (ip in SEED_IPS) {
                if (System.currentTimeMillis() >= deadline) break
                if (isReachable(ip)) return ip
            }
        }
        return null
    }

    private fun resolveOnHost(host: String): String? =
        runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()

    /**
     * 经阿里公共 DNS 的 HTTP 接口查 A 记录。
     * 污染发生在本地递归解析环节，这个接口由阿里代答，且国内可达。
     */
    private fun resolveViaDoh(host: String): String? = runCatching {
        val url = "$DOH_ENDPOINT?name=$host&type=A"
        val body = dohClient.newCall(Request.Builder().url(url).build())
            .execute()
            .use { if (it.isSuccessful) it.body?.string() else null }
            ?: return@runCatching null
        JSONObject(body)
            .optJSONArray("Answer")
            ?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optInt("type") == 1 }
                    ?.optString("data")
                    ?.takeIf { it.isNotBlank() }
            }
    }.onFailure { FileLogger.d(TAG, "DoH 查询失败：$host（${it.message}）") }.getOrNull()

    /** TCP 探测 443：解析出来但连不上同样没用。 */
    private fun isReachable(ip: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, 443), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)
}
