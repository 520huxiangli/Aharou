package com.aharou.feature.voice.domain

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** 模型准备进度。 */
sealed interface VoiceModelState {
    data object Idle : VoiceModelState

    /** 下载中；[progress] 为 0f~1f，服务端没给总长度时恒为 0f。 */
    data class Downloading(val progress: Float) : VoiceModelState

    data object Extracting : VoiceModelState
    data object Ready : VoiceModelState
    data class Failed(val message: String) : VoiceModelState
}

/** 单个必需文件的状态。[actual] 为 null 表示文件不存在。 */
data class VoiceModelFileStatus(val name: String, val expected: Long, val actual: Long?) {
    val ok: Boolean get() = actual != null && actual == expected
}

/** 模型当前状态：哪个文件缺了、哪个字节数不对——设置页照这个显示，不用靠猜。 */
data class VoiceModelStatus(val files: List<VoiceModelFileStatus>) {
    val ready: Boolean get() = files.all { it.ok }

    /** 一个文件都没有：从未下载过（与「下载过但残缺」区分开，设置页文案不同）。 */
    val neverDownloaded: Boolean get() = files.all { it.actual == null }
}

/**
 * 离线语音模型的下载、解压与就绪判定。
 *
 * 目录布局：`filesDir/voice_models/<spec.dirName>/`，就绪判据是 [VoiceModelSpec.requiredFiles]
 * 全部存在**且字节数对得上**（见 [VoiceModelSpec.fileSizes]）——不写「已下载」标记文件，
 * 避免解压中断后标记与实体不一致。下载与解压都走临时目录，成功才落成正式目录，中途失败删干净。
 *
 * 压缩包只在解压期间占盘，随后即删；只拷 [VoiceModelSpec.requiredFiles] 列出的文件。
 */
@Singleton
class VoiceModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 同一模型的并发准备串行化：两处同时触发下载会互相覆盖临时目录。 */
    private val mutex = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    fun modelRoot(): File = File(context.filesDir, VoiceModels.MODEL_ROOT_DIR).apply { mkdirs() }

    fun modelDir(spec: VoiceModelSpec): File = File(modelRoot(), spec.dirName)

    /** 模型是否已就绪（运行所需文件齐全**且字节数对得上**）。 */
    fun isReady(spec: VoiceModelSpec): Boolean = status(spec).ready

    /** 当前模型状态：逐个文件核「在不在 + 字节数对不对」。 */
    fun status(spec: VoiceModelSpec): VoiceModelStatus {
        val dir = modelDir(spec)
        return VoiceModelStatus(
            spec.requiredFiles.map { name ->
                val file = File(dir, name)
                VoiceModelFileStatus(
                    name = name,
                    expected = spec.fileSizes[name] ?: 0L,
                    actual = file.takeIf { it.isFile }?.length(),
                )
            }
        )
    }

    /**
     * 强制重新下载（设置页那个「下载」按钮点第二次时走它）：
     * 先删掉现存的，再走一遍 [ensureModel]。文件看着在、内容坏了时用它。
     */
    suspend fun redownload(
        spec: VoiceModelSpec,
        onState: (VoiceModelState) -> Unit = {},
    ): File {
        mutex.withLock { modelDir(spec).deleteRecursively() }
        return ensureModel(spec, onState)
    }

    /** 全部模型的聚合状态（设置页那一行照它显示）。 */
    fun statusAll(): VoiceModelStatus = VoiceModelStatus(VoiceModels.ALL.flatMap { status(it).files })

    /** 依次重新下载全部模型；有一个失败就返回 false，但不半途停下。 */
    suspend fun redownloadAll(onState: (VoiceModelState) -> Unit = {}): Boolean {
        var ok = true
        VoiceModels.ALL.forEach { spec ->
            if (runCatching { redownload(spec, onState) }.isFailure) ok = false
        }
        return ok
    }

    /**
     * 确保模型可用，返回模型目录。已就绪时直接返回。
     *
     * 未就绪时**联网下载**：从 [VoiceModelSpec.url] 拉 tar.bz2，只解出运行所需的文件，
     * 解压完把压缩包删掉。首次使用某个模型会有一次几百 MB 的下载，之后一直命中本地。
     *
     * @throws IOException 下载失败或解压出的文件不完整
     */
    suspend fun ensureModel(
        spec: VoiceModelSpec,
        onState: (VoiceModelState) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = modelDir(spec)
            if (isReady(spec)) {
                onState(VoiceModelState.Ready)
                return@withLock dir
            }
            val staging = File(modelRoot(), "${spec.dirName}.staging")
            val archive = File(modelRoot(), "${spec.dirName}.tar.bz2")
            try {
                staging.deleteRecursively()
                staging.mkdirs()
                onState(VoiceModelState.Downloading(0f))
                downloadArchive(spec, archive) { progress ->
                    onState(VoiceModelState.Downloading(progress))
                }
                onState(VoiceModelState.Extracting)
                extractArchive(spec, archive, staging)
                dir.deleteRecursively()
                if (!staging.renameTo(dir)) {
                    throw IOException("模型目录改名失败")
                }
                if (!isReady(spec)) {
                    dir.deleteRecursively()
                    throw IOException("模型文件不完整，下载可能被截断")
                }
                onState(VoiceModelState.Ready)
                dir
            } catch (e: Exception) {
                // CancellationException 也走这里：半成品必须清掉，否则下次启动会认为已就绪
                staging.deleteRecursively()
                if (e !is CancellationException) {
                    FileLogger.e(TAG, "下载语音模型失败：${spec.displayName}", e)
                    onState(VoiceModelState.Failed(e.message ?: "下载失败"))
                }
                throw e
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * 下载压缩包到 [dest]：按 [VoiceModelSpec.urls] 的顺序逐个尝试，前一个失败才换下一个。
     *
     * 失败的源只清掉半成品重来——包最大 240MB，换源续传要额外保证分片一致，而候选源本来就少，
     * 重下更简单。
     */
    private suspend fun downloadArchive(
        spec: VoiceModelSpec,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in spec.urls) {
            try {
                fetchArchive(url, dest, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dest.delete()
                FileLogger.w(TAG, "下载源失败，换下一个：$url", e)
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: "没有可用的下载源")
    }

    /**
     * 从单个地址拉压缩包。
     *
     * 走 [AppProxy] 的代理认证器，与容器镜像下载一致——国内直连 GitHub 不稳，用户配了代理就该用上。
     * 协程取消时同步 cancel 底层 call，半成品文件一并删掉。
     */
    private suspend fun fetchArchive(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
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
                        val total = body.contentLength()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    read += n
                                    if (total > 0) {
                                        onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                                    }
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

    /**
     * 从 tar.bz2 压缩包里挑出 [VoiceModelSpec.requiredFiles] 写进 [destDir]，缺一个就报错。
     *
     * 只按 basename 匹配：包内是 `<模型目录>/<文件名>` 的单层结构，取 basename 顺带挡掉
     * 压缩包里可能出现的路径穿越（`../`）。
     */
    private fun extractArchive(spec: VoiceModelSpec, archive: File, destDir: File) {
        val wanted = spec.requiredFiles.toSet()
        val found = mutableSetOf<String>()
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isFile && name in wanted && found.add(name)) {
                        File(destDir, name).outputStream().use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        val missing = wanted - found
        if (missing.isNotEmpty()) {
            throw IOException("模型包里找不到文件：" + missing.joinToString("、"))
        }
    }

    private companion object {
        const val TAG = "VoiceModelManager"
    }
}
