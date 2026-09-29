package com.aharou.feature.voice.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 模型准备进度。 */
sealed interface VoiceModelState {
    data object Idle : VoiceModelState
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
}

/**
 * 离线语音模型的获取与解压。
 *
 * 目录布局：`filesDir/voice_models/<spec.dirName>/`，就绪判据是 [VoiceModelSpec.requiredFiles]
 * 全部存在**且字节数对得上**（见 [VoiceModelSpec.fileSizes]）——不写「已下载」标记文件，
 * 避免解压中断后标记与实体不一致。释放走临时目录，成功才落成正式目录，中途失败删除半成品。
 */
@Singleton
class VoiceModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 同一模型的并发准备串行化：两处同时触发释放会互相覆盖临时目录。 */
    private val mutex = Mutex()

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
     * 强制从安装包重新释放（设置页那个「重新释放」按钮）：
     * 先删掉现存的，再走一遍 [ensureModel]。文件看着在、内容坏了时用它。
     */
    suspend fun rerelease(spec: VoiceModelSpec): File {
        mutex.withLock { modelDir(spec).deleteRecursively() }
        return ensureModel(spec)
    }

    /**
     * 确保模型可用，返回模型目录。已就绪时直接返回。
     *
     * 模型随安装包内置（见 [VoiceModels.ASR_ZH]），这里只是把它从 assets 释放到私有目录，
     * **不走网络**：模型下载源又慢又不稳，内置才是正经分发方式。
     *
     * @throws IOException 内置模型取不到（安装包被裁剪 / 损坏）
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
            try {
                onState(VoiceModelState.Extracting)
                val staging = File(modelRoot(), "${spec.dirName}.staging")
                staging.deleteRecursively()
                staging.mkdirs()
                if (!runCatching { extractBundled(spec, staging) }.getOrDefault(false)) {
                    throw IOException("安装包里的语音模型不完整，请重新安装 App")
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
                File(modelRoot(), "${spec.dirName}.staging").deleteRecursively()
                if (e !is kotlinx.coroutines.CancellationException) {
                    FileLogger.e(TAG, "准备语音模型失败：${spec.displayName}", e)
                    onState(VoiceModelState.Failed(e.message ?: "准备失败"))
                }
                throw e
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

    private companion object {
        const val TAG = "VoiceModelManager"
    }
}
