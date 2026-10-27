package com.aharou.feature.agent.domain.ocr

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 本机 OCR 语言包（tessdata）的下载与就绪判定。
 *
 * 与离线语音模型同一套做法（见 `VoiceModelManager`），差别只在语言包是裸文件、不用解压：
 * 下载到 `<文件>.part` → 校验字节数 → 改名转正，中途失败删干净。就绪判据是
 * [TessdataPack.FILES] 全部存在**且字节数对得上**，不写「已下载」标记文件，避免标记与实体不一致。
 */
@Singleton
class TessdataManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 并发准备串行化：两处同时触发下载会互相覆写同一个 .part 文件。 */
    private val mutex = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    /** Tesseract `init` 要的父目录，里面是 `tessdata/`。不主动 mkdirs：没下载过时它就该不存在。 */
    fun dataParent(): File = File(context.filesDir, TessdataPack.PARENT_DIR)

    /** 语言包目录 `<filesDir>/tessdata-parent/tessdata`。 */
    fun dataDir(): File = File(dataParent(), TessdataPack.DIR)

    /** 语言包是否已就绪（文件齐全且字节数对得上）。 */
    fun isReady(): Boolean = status().ready

    /** 当前状态：逐个文件核「在不在 + 字节数对不对」。 */
    fun status(): TessdataStatus = TessdataStatus(
        TessdataPack.FILES.map { pack ->
            val file = File(dataDir(), pack.name)
            TessdataFileStatus(
                name = pack.name,
                expected = pack.bytes,
                actual = file.takeIf { it.isFile }?.length(),
            )
        }
    )

    /** 强制重新下载（设置页「下载」点了第二次就走它）。 */
    suspend fun redownload(onState: (TessdataState) -> Unit = {}): File {
        mutex.withLock { dataDir().deleteRecursively() }
        return ensureReady(onState = onState)
    }

    /**
     * 确保语言包可用，返回 Tesseract 要的父目录。已就绪时立即返回。
     *
     * [allowDownload] 为 false 时不联网，未就绪直接抛异常，由调用方决定提示还是降级。
     *
     * @throws IOException 下载失败、字节数对不上，或 [allowDownload] 为 false 且语言包未就绪
     */
    suspend fun ensureReady(
        allowDownload: Boolean = true,
        onState: (TessdataState) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = dataParent()
            if (isReady()) {
                onState(TessdataState.Ready)
                return@withLock parent
            }
            if (!allowDownload) {
                throw IOException("OCR 语言包尚未下载，请到设置 → 默认模型 → OCR 语言包处下载")
            }

            val dir = dataDir()
            dir.mkdirs()
            val missing = TessdataPack.FILES.filter { File(dir, it.name).length() != it.bytes }
            val total = missing.sumOf { it.bytes }.takeIf { it > 0 } ?: 1L
            var done = 0L
            try {
                onState(TessdataState.Downloading(0f))
                for (pack in missing) {
                    val dest = File(dir, pack.name)
                    // 上一轮下到一半的 .part 直接复用会接不上，每次开新文件
                    val part = File(dir, "${pack.name}.part")
                    part.delete()
                    fetch(TessdataPack.url(pack.name), part) { written ->
                        onState(TessdataState.Downloading(((done + written).toFloat() / total).coerceIn(0f, 1f)))
                    }
                    if (part.length() != pack.bytes) {
                        part.delete()
                        throw IOException("${pack.name} 大小不对（期望 ${pack.bytes} 字节）")
                    }
                    dest.delete()
                    if (!part.renameTo(dest)) {
                        part.delete()
                        throw IOException("${pack.name} 落盘失败")
                    }
                    done += pack.bytes
                }
                if (!isReady()) {
                    dir.deleteRecursively()
                    throw IOException("语言包文件不完整，下载可能被截断")
                }
                FileLogger.i(TAG, "OCR 语言包就绪：${dir.absolutePath}")
                onState(TessdataState.Ready)
                parent
            } catch (e: Exception) {
                // CancellationException 也走这里：半成品必须清掉，否则下次会认为已就绪
                if (e !is CancellationException) {
                    FileLogger.e(TAG, "下载 OCR 语言包失败", e)
                    onState(TessdataState.Failed(e.message ?: "下载失败"))
                }
                throw e
            }
        }
    }

    /**
     * 从单个地址拉文件到 [dest]。
     *
     * 走 [AppProxy] 的代理认证器，与语音模型 / 容器镜像下载一致：国内直连不稳，用户配了代理就该用上。
     * 协程取消时同步 cancel 底层 call，半成品一并删掉。
     */
    private suspend fun fetch(url: String, dest: File, onProgress: (Long) -> Unit) {
        val call = client.newCall(Request.Builder().url(url).build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    dest.delete()
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("响应体为空")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    written += n
                                    onProgress(written)
                                }
                            }
                        }
                        cont.resume(Unit)
                    } catch (e: Exception) {
                        dest.delete()
                        if (!cont.isCancelled) cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    private companion object {
        const val TAG = "TessdataManager"
    }
}
