package com.aharou.feature.agent.domain.ocr

import android.graphics.BitmapFactory
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesseract 本地 OCR：把图片转成本机识别的纯文字，供不支持图片输入的模型使用。
 *
 * 语言包不随 APK 内置，由 [TessdataManager] 下载到私有目录（Android 10 起 Tesseract 只能从
 * 应用私有目录读取 tessdata）；识别前先确保它已就绪，缺就自动下一次（约 6.3MB）。
 *
 * TessBaseAPI 非线程安全，且 init 要加载语言包（数百毫秒），故全局单实例 + 串行化。
 * 识别结果按图片内容哈希缓存：影子屏截图与图片附件在每轮对话里会重复下发，
 * 不缓存就等于每轮都重认一遍。
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    private val tessdataManager: TessdataManager,
) {

    private val initLock = Mutex()
    private val recognizeLock = Mutex()
    private var api: TessBaseAPI? = null

    /** 有序 LRU：按图片内容哈希缓存识别结果。 */
    private val cache = object : LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > CACHE_SIZE
    }

    /** 识别图片文字；图片无文字、解码失败或引擎不可用时返回 null。 */
    suspend fun recognize(image: AgentImage): String? = withContext(Dispatchers.Default) {
        val bytes = runCatching { Base64.decode(image.base64Data, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return@withContext null
        if (!ensureTessdata()) return@withContext null

        val key = md5(bytes)
        synchronized(cache) { cache[key] }?.let { return@withContext it }

        val text = recognizeLock.withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }
            runCatching { recognizeBytes(bytes) }
                .onFailure { FileLogger.w(TAG, "OCR 识别失败", it) }
                .getOrNull()
                ?.also { synchronized(cache) { cache[key] = it } }
        }
        text
    }

    private fun recognizeBytes(bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val engine = api ?: createEngine().also { api = it }
            engine.setImage(bitmap)
            return engine.utF8Text.orEmpty().trim().ifBlank { null }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 语言包就绪检查：缺失时**自动下载一次**（约 6.3MB），失败就本次跳过本机 OCR。
     *
     * 不像几百 MB 的语音模型那样强制用户先去设置页：6.3MB 在识别前拉一次更快，
     * 失败也不抛给调用方——OCR 本来就是兜底路径，返回 null 会自然落回识图模型。
     */
    private suspend fun ensureTessdata(): Boolean {
        if (tessdataManager.isReady()) return true
        return try {
            tessdataManager.ensureReady()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "OCR 语言包不可用，跳过本机 OCR", e)
            false
        }
    }

    private fun createEngine(): TessBaseAPI {
        val dataParent = tessdataManager.dataParent()
        val engine = TessBaseAPI()
        if (!engine.init(dataParent.absolutePath, TessdataPack.LANGUAGE_TAG)) {
            throw IllegalStateException("Tesseract 初始化失败: ${dataParent.absolutePath}")
        }
        FileLogger.i(TAG, "Tesseract 就绪，语言=${TessdataPack.LANGUAGE_TAG}")
        return engine
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "TesseractOcr"
        /** 影子屏截图单张可达数百 KB，缓存条目按张数限制即可。 */
        const val CACHE_SIZE = 8
    }
}
