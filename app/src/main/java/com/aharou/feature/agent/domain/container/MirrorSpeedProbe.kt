package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首次写死国内源前的选源：按真实发行版和架构请求一个很小的索引段，比较实际耗时。
 *
 * 首次解压时容器还不能执行命令，所以不能复用 `env-common.sh` 的测速；
 * 这里只做极小的 GET 并读掉前 16 KiB，成功且耗时最短者优先。全部失败时返回 null，
 * 由调用方保留镜像自带源并明确记录，而不是把某一家说成“自动选好的”。
 */
@Singleton
class MirrorSpeedProbe @Inject constructor() {

    suspend fun fastest(candidates: List<String>, distro: String, arch: String, codename: String): String? =
        withContext(Dispatchers.IO) {
            val measured = candidates.mapNotNull { host ->
                val url = probeUrl(host, distro, arch, codename) ?: return@mapNotNull null
                runCatching { measure(url) }.getOrNull()?.let { host to it }
            }
            measured.forEach { (host, ms) -> FileLogger.i(TAG, "镜像测速 $host ${ms}ms") }
            fastestOf(measured).also {
                if (it == null) FileLogger.w(TAG, "所有国内镜像探测失败，保留镜像自带源")
            }
        }

    internal fun fastestOf(measured: List<Pair<String, Long>>): String? {
        val usable = measured.filter { it.second >= 0 }
        return usable.minByOrNull { it.second }?.first
    }

    internal fun probeUrl(host: String, distro: String, arch: String, codename: String): String? = when (distro) {
        "alpine" -> "http://$host/alpine/v${if (codename.isBlank()) "3.24" else codename}/main/$arch/APKINDEX.tar.gz"
        "debian" -> "http://$host/debian/dists/${codename.ifBlank { "stable" }}/Release"
        "ubuntu" -> "http://$host/${if (arch == "amd64") "ubuntu" else "ubuntu-ports"}/dists/${codename.ifBlank { "noble" }}/Release"
        else -> null
    }

    private fun measure(url: String): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-16383")
        }
        return try {
            val started = System.nanoTime()
            val code = connection.responseCode
            check(code in 200..399) { "HTTP $code" }
            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            (System.nanoTime() - started) / 1_000_000
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "MirrorSpeedProbe"
    }
}
