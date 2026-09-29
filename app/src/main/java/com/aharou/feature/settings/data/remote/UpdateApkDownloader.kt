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
        for (url in candidates) {
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
