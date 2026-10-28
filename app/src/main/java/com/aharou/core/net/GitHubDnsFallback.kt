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

    /** 默认兜底域名。codeload 供 tarball 下载，api 供版本查询，raw/objects 供单文件与 Release 资产。 */
    private val DEFAULT_HOSTS = listOf(
        "github.com",
        "codeload.github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
    )

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

    /** 探活结果缓存有效期：与检查间隔一致，避免命令密集时反复做 TCP 探测。 */
    private const val REACHABILITY_TTL_MS = 5 * 60 * 1000L

    /** 已注入内容的保留时长。过期重新解析，使站点换 IP 时能自愈。 */
    private const val REFRESH_AFTER_MS = 7 * 24 * 3600 * 1000L

    private const val BEGIN_MARKER = "# >>> Aharou DNS 兜底（解析失败时注入，整段删掉即还原）"
    private const val END_MARKER = "# <<< Aharou DNS 兜底"
    private const val STAMP_PREFIX = "# injected-at: "

    /** 只对 GitHub 有意义：别的域名解析不出来就该老老实实失败，不能瞎填。 */
    private val SEED_IPS = mapOf(
        "github.com" to listOf("140.82.114.3", "20.205.243.166", "140.82.112.3", "140.82.113.4"),
        "api.github.com" to listOf("20.205.243.168", "140.82.113.6", "140.82.114.6"),
        "codeload.github.com" to listOf("20.205.243.165", "140.82.114.9"),
        "raw.githubusercontent.com" to listOf("185.199.110.133", "185.199.108.133", "185.199.111.133"),
        "objects.githubusercontent.com" to listOf("185.199.110.133", "185.199.108.133"),
    )

    /**
     * 在线 hosts 列表（GitHub520 社区维护，国内可直连）。
     *
     * 静态种子只能拦住「某个 IP 当下能用」，GitHub 轮换后就全失效；这份列表由社区定期更新，
     * 能持续提供新地址。**但它只当候选**：实测同一份列表里 `140.82.113.4` 在本机超时、
     * `140.82.114.3` 才通，不等于运营商之间都一致——所以拉回来也要逐个 443 探活。
     */
    private const val ONLINE_HOSTS_URL = "https://raw.hellogithub.com/hosts"

    /** 在线列表的内存缓存时长：它一天才更新几次，没必要每次命令都拉。 */
    private const val ONLINE_HOSTS_TTL_MS = 6 * 3600 * 1000L

    @Volatile
    private var onlineHosts: Map<String, List<String>> = emptyMap()

    @Volatile
    private var onlineHostsAt = 0L

    @Volatile
    private var lastCheckAt = 0L

    @Volatile
    private var lastTargets: List<String> = emptyList()

    /**
     * 探活结果缓存。命令里扫出的域名每条都可能不同，而「目标集合变了就跳过节流」那条
     * 会把探活放大成每秒一次 TCP 连接（线上实测一天 320 次重解析）。IP 可达性不会秒级变化，
     * 给结果加 5 分钟缓存，让重复探活归零；真失效了最坏也就多等一个周期。
     */
    private val reachabilityCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

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

        // 已注入的块覆盖了本次全部目标、未过期，且里面的 IP 还连得上，才不动。新鲜度写进文件本身，
        // 进程重启后依然有效（lastCheckAt 只是进程内的节流）。
        // 光看时间戳不够：名单里的 GitHub IP 会变差，一旦写进 hosts，容器里的 git 会一直失败到这个
        // 块过期（REFRESH_AFTER_MS）为止，所以这里顺手探活一次（有 5 分钟节流兜着，不会每条命令都探）。
        val injected = parseBlock(existing)
        if (injected != null && !isStale(injected.stamp, now) && targets.all { it in injected.hosts }) {
            // 假 IP（198.18.x.x 之类）也要当成失效：它们被 VPN 接管后照样「可达」，
            // 但容器里的 git 会一直失败——不这样判，旧条目会一直续命。
            val alive = injected.entries
                .filter { (_, host) -> host in targets }
                .all { (ip, _) -> isUsableIp(ip) && isReachableCached(ip) }
            if (alive) return null
            FileLogger.i(
                TAG,
                "已注入的 hosts 条目已失效（连不上或是保留网段的假 IP），重新解析：" +
                    injected.entries.joinToString { "${it.second}→${it.first}" }
            )
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

    private data class InjectedBlock(val stamp: Long, val entries: List<Pair<String, String>>) {
        /** 块里写过的域名（与 [entries] 同源，按写入顺序）。 */
        val hosts: List<String> get() = entries.map { it.second }
    }

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
        val entries = body.filterNot { it.trim().startsWith(STAMP_PREFIX.trim()) }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) null else parts[0] to parts[1]
            }
        return InjectedBlock(stamp, entries)
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

    /** 带缓存的探活：同一 IP 在 [REACHABILITY_TTL_MS] 内只真正连一次。 */
    private fun isReachableCached(ip: String): Boolean {
        val now = System.currentTimeMillis()
        reachabilityCache[ip]?.let { (alive, at) ->
            if (now - at < REACHABILITY_TTL_MS) return alive
        }
        val alive = isReachable(ip)
        reachabilityCache[ip] = alive to now
        return alive
    }

    /**
     * 是不是能写进 hosts 的真实公网地址。
     *
     * **保留网段一律拒绝**：开着 Clash 类 TUN 代理时，DNS 会返回 198.18.x.x 的 fake-ip，
     * 它对 `isReachable` 也是通的（TCP 被 VPN 接管），但写进容器 hosts 后 GitHub 全断，
     * 用户只能手动删 hosts——这就是「修好了又被重写」的根因。
     */
    private fun isUsableIp(ip: String): Boolean {
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        return when {
            b0 == 0 -> false                     // 0.0.0.0/8
            b0 == 10 -> false                    // 私有
            b0 == 127 -> false                   // 回环
            b0 == 169 && b1 == 254 -> false      // link-local
            b0 == 172 && b1 in 16..31 -> false   // 私有
            b0 == 192 && b1 == 168 -> false      // 私有
            b0 == 100 && b1 in 64..127 -> false  // CGNAT
            b0 == 198 && b1 in 18..19 -> false   // 基准测试保留（fake-ip 常用）
            b0 >= 224 -> false                   // 组播与保留
            else -> true
        }
    }

    /** 按优先级挑一个 443 实测可达的真实 IP；超出总预算就放弃。 */
    private fun pickIp(host: String): String? {
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        // 宿主侧解析走 Android 系统 resolver，命中缓存时极快，拿到的 IP 也最贴近本机网络。
        // 宿主能解析不代表容器能解析，但 IP 只要真通就能用，所以这里只把它当候选。
        resolveOnHost(host)?.let { if (isUsableIp(it) && isReachableCached(it)) return it }

        if (System.currentTimeMillis() < deadline) {
            resolveViaDoh(host)?.let { if (isUsableIp(it) && isReachableCached(it)) return it }
        }
        SEED_IPS[host]?.let { seeds ->
            for (ip in seeds) {
                if (System.currentTimeMillis() >= deadline) break
                if (isUsableIp(ip) && isReachable(ip)) return ip
            }
        }
        // 前面全落空才拉在线列表：它有网给开销，不能拖慢每一条命令
        if (host in DEFAULT_HOSTS) {
            for (ip in onlineSeedFor(host)) {
                if (System.currentTimeMillis() >= deadline) break
                if (isUsableIp(ip) && isReachable(ip)) return ip
            }
        }
        return null
    }

    private fun resolveOnHost(host: String): String? =
        runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()

    /** IPV4 字面量（在线 hosts 只收 A 记录，IPv6 写进容器 hosts 对 git 没帮助）。 */
    private val IPV4_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * 在线 hosts 列表中的候选（带缓存）。拉取失败就返回空，不影响其他兜底。
     */
    private fun onlineSeedFor(host: String): List<String> {
        val now = System.currentTimeMillis()
        if (now - onlineHostsAt > ONLINE_HOSTS_TTL_MS) {
            onlineHostsAt = now
            val body = runCatching {
                dohClient.newCall(Request.Builder().url(ONLINE_HOSTS_URL).build())
                    .execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.onFailure { FileLogger.w(TAG, "拉取在线 hosts 失败", it) }.getOrNull()
            onlineHosts = body?.let { runCatching { parseOnlineHosts(it) }.getOrDefault(emptyMap()) }
                ?: emptyMap()
        }
        return onlineHosts[host].orEmpty()
    }

    /** 解析 hosts 格式：`IP 域名 [域名...]`，`#` 后面是注释。 */
    private fun parseOnlineHosts(text: String): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2 || !IPV4_RE.matches(parts[0])) return@forEach
            parts.drop(1).forEach { name ->
                val h = name.trim().lowercase()
                if (HOST_RE.matches(h)) out.getOrPut(h) { mutableListOf() }.add(parts[0])
            }
        }
        return out
    }

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
