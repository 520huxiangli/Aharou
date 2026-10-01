package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.core.net.AppProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 更新包下载：按候选地址依次尝试，把 APK 落盘到 filesDir/updates/。
 *
 * 落点选在 filesDir 下是因为 FileProvider 的 `files-path` 已覆盖整个 filesDir，
 * 下载完可直接换成 content:// URI 交给系统安装器（file:// 在 targetSdk 28+ 会被拒）。
 * 请求走全局代理认证，与容器镜像下载保持一致。
 */
@Singleton
class UpdateApkDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 测速专用 client：与下载 client 的唯一区别是带 callTimeout。
     *
     * 探测跑在阻塞式 IO 上，协程层的 withTimeout 对它无效（取消要到阻塞调用返回后才抛出），
     * 只有 OkHttp 自己的 callTimeout 能真正挖断请求。不加这个，慢源会把「选择下载源」
     * 拖成几十秒：实测反代慢时 512KB 要读半分钟。
     */
    private val probeClient by lazy {
        client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 下载目录：优先 App 外部私有目录（Android/data/<pkg>/files/updates）。
     *
     * 之所以不用内部 filesDir：开关「下载后自动静默安装」时要把包交给 Shizuku（adb shell 身份）
     * 执行 `pm install`，而 shell 读不到 filesDir，外部私有目录实测 shell 可读写；取不到时回退内部目录
     *（此时只能走系统安装器，见 installDownloadedApk）。
     */
    fun downloadDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").apply { mkdirs() }
    }

    /**
     * 逐个候选源尝试。失败**不删半成品**：下次（含换个源）用 HTTP Range 接着下，
     * 不用从 0 重来——安装包 100MB+，断点续传是刚需。只有内容不是安装包、
     * 或全部源都失败才回错。
     *
     * @param expectedSize GitHub API 报的资产大小，用于校验完整性（<=0 表示未知，跳过校验）
     */
    suspend fun download(
        candidates: List<String>,
        fileName: String,
        expectedSize: Long,
        onSource: (String) -> Unit = {},
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dest = File(downloadDir(), fileName)
        // 已下完的直接复用：更新弹窗会被反复打开，没必要每次重下
        if (expectedSize > 0 && dest.isFile && dest.length() == expectedSize) {
            return@withContext dest
        }
        var lastError: Exception? = null
        // 先实测各源速度、按快的排前，再逐个尝试（失败仍按序降级）。
        // 只按「可达」顺序试的话，一个通但只有几十 KB/s 的源会把整包拖慢到底。
        for (url in rankBySpeed(candidates)) {
            try {
                val file = fetch(url, dest, expectedSize, onSource, onProgress)
                return@withContext file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: "没有可用的下载源")
    }

    /**
     * 并发探测各候选源的实测速度，按快慢排序返回。
     *
     * 「第一个可达的」不等于「最快」——实测里最慢的反代只有 ~0.04 MB/s（与直连 GitHub 相当），
     * 顺序试就会一路慢到底。这里让每个源各下 [PROBE_BYTES]，谁用时短谁排前；
     * 探测失败的源排到最后（它们本来也会被跳过）。
     *
     * 只探测、不写盘，对目标文件无影响；真正的下载仍带 Range 续传。
     */
    private suspend fun rankBySpeed(candidates: List<String>): List<String> = coroutineScope {
        candidates.map { url ->
            async(Dispatchers.IO) {
                val costMs = runCatching {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        val req = Request.Builder().url(url)
                            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                            .build()
                        probeClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful && resp.code != 206) {
                                throw IOException("HTTP ${resp.code}")
                            }
                            resp.header("Content-Type")?.takeIf { it.startsWith("text/") }
                                ?.let { throw IOException("响应不是安装包（$it）") }
                            val body = resp.body ?: throw IOException("响应体为空")
                            val started = System.currentTimeMillis()
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (read < PROBE_BYTES) {
                                val n = body.source().read(buf)
                                if (n <= 0) break
                                read += n
                            }
                            if (read <= 0) throw IOException("读不到数据")
                            System.currentTimeMillis() - started
                        }
                    }
                }.getOrElse { Long.MAX_VALUE }
                url to costMs
            }
        }.awaitAll().sortedBy { it.second }.map { it.first }
    }

    private companion object {
        /** 测速时每个源只下这么多：够分出快慢，又不白耗流量。
         *  128KB 在 16KB/s 的慢源上约 8 秒，配合 callTimeout 能及时收算。 */
        const val PROBE_BYTES = 128L * 1024

        /** 单个源的测速超时，超时算不可用、排到队尾。 */
        const val PROBE_TIMEOUT_MS = 6_000L
    }

    private suspend fun fetch(
        url: String,
        dest: File,
        expectedSize: Long,
        onSource: (String) -> Unit,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File = suspendCancellableCoroutine { cont ->
        // 半成品接着下：各个候选源给的是同一个资产，内容一致，换源也能续
        val already = if (dest.isFile && dest.length() > 0 &&
            (expectedSize <= 0 || dest.length() < expectedSize)
        ) {
            dest.length()
        } else {
            if (dest.isFile) dest.delete() // 比预期还大 = 上次是坏文件
            0L
        }
        val builder = Request.Builder().url(url)
        if (already > 0) builder.header("Range", "bytes=$already-")
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.header("Content-Type")?.takeIf { it.startsWith("text/") }?.let {
                        // 反代/镜像故障时会回一段 HTML 错误页，别把它当 APK 存下来
                        throw IOException("响应不是安装包（$it）")
                    }
                    // 请求了 Range 却回 200：该源不支持续传，从头写
                    val resuming = already > 0 && response.code == 206
                    if (already > 0 && !resuming) dest.delete()
                    val base = if (resuming) already else 0L
                    val body = response.body ?: throw IOException("响应体为空")
                    val total = body.contentLength().takeIf { it > 0 }?.let { base + it } ?: expectedSize
                    onSource(url)
                    val buffer = ByteArray(64 * 1024)
                    var session = 0L
                    // 不按速度判死源：慢但一直在下就让它下（手机流量本来就慢）。
                    // 「源彻底卡住」由 OkHttp 的 readTimeout(60s) 兜底，不用另设阀值。
                    body.byteStream().use { input ->
                        FileOutputStream(dest, resuming).use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                session += n
                                onProgress(base + session, total)
                            }
                        }
                    }
                    val downloaded = dest.length()
                    if (expectedSize > 0 && downloaded != expectedSize) {
                        throw IOException("安装包不完整（$downloaded / $expectedSize 字节）")
                    }
                    cont.resume(dest)
                } catch (e: Exception) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            }
        })
    }
}
