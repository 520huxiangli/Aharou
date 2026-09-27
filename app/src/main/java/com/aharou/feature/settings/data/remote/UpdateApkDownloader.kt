package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.core.net.AppProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    /** 下载目录（FileProvider 已覆盖 filesDir，无需改 file_paths.xml）。 */
    fun downloadDir(): File = File(context.filesDir, "updates").apply { mkdirs() }

    /**
     * 逐个候选源尝试下载。单个源连接不上、返回非 200、内容不是 APK 或长度对不上，
     * 都视为该源失败并删除半成品，换下一个；全部失败抛 [IOException]。
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
        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val file = fetch(url, dest, expectedSize, onSource, onProgress)
                return@withContext file
            } catch (e: CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw IOException(lastError?.message ?: "没有可用的下载源")
    }

    private suspend fun fetch(
        url: String,
        dest: File,
        expectedSize: Long,
        onSource: (String) -> Unit,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
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
                    val body = response.body ?: throw IOException("响应体为空")
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                    onSource(url)
                    val startedAt = System.currentTimeMillis()
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                onProgress(read, total)
                                // 慢源早退：实测 GitHub 直连约 0.03 MB/s、已失效的反代约 0.04 MB/s，
                                // 正常源至少 1 MB/s。读到阀定量还没达最低速度就换下一个候选，
                                // 别让用户抱着一个龟速源干等十几分钟。
                                val elapsed = System.currentTimeMillis() - startedAt
                                if (read >= SLOW_PROBE_BYTES && elapsed > SLOW_PROBE_MILLIS) {
                                    throw IOException(
                                        "下载源速度过慢（${read / 1024} KB 用了 ${elapsed / 1000} s）"
                                    )
                                }
                            }
                        }
                    }
                    if (expectedSize > 0 && read != expectedSize) {
                        throw IOException("安装包不完整（$read / $expectedSize 字节）")
                    }
                    cont.resume(dest)
                } catch (e: Exception) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        /** 慢源判定：读到 1 MB 仍耗时超过 10 秒（低于 0.1 MB/s）即判该源不可用。 */
        const val SLOW_PROBE_BYTES = 1L * 1024 * 1024
        const val SLOW_PROBE_MILLIS = 10_000L
    }
}
