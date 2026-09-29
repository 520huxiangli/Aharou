package com.aharou.feature.voice.domain

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** 模型准备进度。 */
internal sealed interface VoiceModelState {
    data object Idle : VoiceModelState
    data class Downloading(val readBytes: Long, val totalBytes: Long) : VoiceModelState
    data object Extracting : VoiceModelState
    data object Ready : VoiceModelState
    data class Failed(val message: String) : VoiceModelState
}

/**
 * 离线语音模型的获取与解压。
 *
 * 目录布局：`filesDir/voice_models/<spec.dirName>/`，就绪判据是 [VoiceModelSpec.requiredFiles]
 * 全部存在——不做「是否下载过」的标记文件，避免解压中断后标记与实体不一致。
 * 下载走临时文件，解压成功才落成正式目录，中途失败删除半成品。
 */
@Singleton
internal class VoiceModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS)
            // 模型几十 MB，读超时给宽；慢源由 [fetch] 内的速度探测兜底
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 同一模型的并发准备串行化：两处同时触发下载会互相覆盖临时文件。 */
    private val mutex = Mutex()

    fun modelRoot(): File = File(context.filesDir, VoiceModels.MODEL_ROOT_DIR).apply { mkdirs() }

    fun modelDir(spec: VoiceModelSpec): File = File(modelRoot(), spec.dirName)

    /** 模型是否已就绪（运行所需文件齐全）。 */
    fun isReady(spec: VoiceModelSpec): Boolean {
        val dir = modelDir(spec)
        return spec.requiredFiles.all { File(dir, it).isFile }
    }

    /**
     * 确保模型可用，返回模型目录。已就绪时直接返回，不重复下载。
     *
     * @throws IOException 全部源都失败
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
            onState(VoiceModelState.Downloading(0L, spec.archiveSize))
            val archive = File(modelRoot(), "${spec.dirName}.tar.bz2")
            try {
                // 内置模型直接释放：APK 里带着压缩包时不必走网络（模型 78MB，网络源在国内普遍很慢）。
                // 释放失败（如包裹损坏）不直接判失败，回退到下载。
                onState(VoiceModelState.Extracting)
                val staging = File(modelRoot(), "${spec.dirName}.staging")
                staging.deleteRecursively()
                staging.mkdirs()
                val bundled = runCatching { extractBundled(spec, staging) }.getOrDefault(false)
                if (!bundled) {
                    staging.deleteRecursively()
                    downloadArchive(spec, archive, onState)
                    onState(VoiceModelState.Extracting)
                    staging.mkdirs()
                    extractTarBz2(archive, staging)
                }
                dir.deleteRecursively()
                if (!staging.renameTo(dir)) {
                    staging.deleteRecursively()
                    throw IOException("模型目录改名失败")
                }
                if (!isReady(spec)) {
                    dir.deleteRecursively()
                    throw IOException("模型文件不完整")
                }
                onState(VoiceModelState.Ready)
                dir
            } catch (e: Exception) {
                // CancellationException 也走这里：半成品必须清掉，否则下次启动会认为已就绪
                archive.delete()
                File(modelRoot(), "${spec.dirName}.staging").deleteRecursively()
                if (e !is kotlinx.coroutines.CancellationException) {
                    FileLogger.e(TAG, "准备语音模型失败：${spec.displayName}", e)
                    onState(VoiceModelState.Failed(e.message ?: "下载失败"))
                }
                throw e
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * 从 APK 内置 assets 释放模型，成功返回 true。
     *
     * 只拷 [VoiceModelSpec.requiredFiles]：推送时 tar 包里有 README、测试音频等无关文件，
     * 单独打进 assets 时已剔除，这里按清单直取，不用遍历目录。
     */
    private fun extractBundled(spec: VoiceModelSpec, destDir: File): Boolean {
        val prefix = "${VoiceModels.ASSET_ROOT}/${spec.dirName}"
        val names = context.assets.list(prefix)?.toSet().orEmpty()
        if (names.isEmpty()) return false
        for (rel in spec.requiredFiles) {
            if (rel !in names) return false
            val out = File(destDir, rel)
            out.parentFile?.mkdirs()
            context.assets.open("$prefix/$rel").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return true
    }

    /** 逐个候选源尝试；任一源失败即删半成品换下一个。 */
    private suspend fun downloadArchive(
        spec: VoiceModelSpec,
        dest: File,
        onState: (VoiceModelState) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in spec.urls) {
            try {
                fetch(url, dest, spec.archiveSize, onState)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "模型下载源失败：$url", e)
                lastError = e
                dest.delete()
            }
        }
        throw IOException(lastError?.message ?: "没有可用的模型下载源")
    }

    private fun fetch(
        url: String,
        dest: File,
        expectedSize: Long,
        onState: (VoiceModelState) -> Unit,
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.header("Content-Type")?.takeIf { it.startsWith("text/") }?.let {
                // 镜像故障时会回一段 HTML 错误页，别当压缩包存下来
                throw IOException("响应不是模型包（$it）")
            }
            val body = resp.body ?: throw IOException("响应体为空")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
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
                        onState(VoiceModelState.Downloading(read, total))
                        // 慢源早退：GitHub 直连对国内约 0.03 MB/s，正常镜像 ≥1 MB/s。
                        // 读到阀定量还没达最低速度就换下一个候选，别让用户干等。
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (read >= SLOW_PROBE_BYTES && elapsed > SLOW_PROBE_MILLIS) {
                            throw IOException("下载源过慢（${read / 1024} KB 用了 ${elapsed / 1000} s）")
                        }
                    }
                }
            }
            if (expectedSize > 0 && read != expectedSize) {
                throw IOException("模型包不完整（$read / $expectedSize 字节）")
            }
        }
    }

    /** 解压 tar.bz2 到 [destDir]，带 zip-slip 防护。模型包内只有普通文件与目录，不处理链接/权限位。 */
    private fun extractTarBz2(archive: File, destDir: File) {
        val canonicalRoot = destDir.canonicalPath
        archive.inputStream().use { raw ->
            BZip2CompressorInputStream(raw, true).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val out = File(destDir, entry.name)
                        if (!out.canonicalPath.startsWith(canonicalRoot + File.separator)) {
                            throw IOException("压缩包内含越界路径：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os -> tar.copyTo(os) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "VoiceModelManager"
        const val SLOW_PROBE_BYTES = 1L * 1024 * 1024
        const val SLOW_PROBE_MILLIS = 10_000L
    }
}
