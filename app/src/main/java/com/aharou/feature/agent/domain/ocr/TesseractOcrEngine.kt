package com.aharou.feature.agent.domain.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesseract 本地 OCR：把图片转成本机识别的纯文字，供不支持图片输入的模型使用。
 *
 * 语言包随 APK 内置（assets/tessdata），首次使用时解压到私有目录——Android 10 起
 * Tesseract 只能从应用私有目录读取 tessdata。
 *
 * TessBaseAPI 非线程安全，且 init 要加载语言包（数百毫秒），故全局单实例 + 串行化。
 * 识别结果按图片内容哈希缓存：影子屏截图与图片附件在每轮对话里会重复下发，
 * 不缓存就等于每轮都重认一遍。
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
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

    private fun createEngine(): TessBaseAPI {
        val dataParent = File(context.filesDir, TESSDATA_PARENT)
        ensureTessdata(dataParent)
        val engine = TessBaseAPI()
        if (!engine.init(dataParent.absolutePath, LANGUAGES)) {
            throw IllegalStateException("Tesseract 初始化失败: ${dataParent.absolutePath}")
        }
        FileLogger.i(TAG, "Tesseract 就绪，语言=$LANGUAGES")
        return engine
    }

    private fun ensureTessdata(dataParent: File) {
        val targetDir = File(dataParent, TESSDATA_DIR)
        val langs = LANGUAGES.split('+').filter { it.isNotBlank() }
        if (langs.all { File(targetDir, "$it.traineddata").length() > 0 }) return

        targetDir.mkdirs()
        langs.forEach { lang ->
            val out = File(targetDir, "$lang.traineddata")
            if (out.length() > 0) return@forEach
            context.assets.open("$TESSDATA_DIR/$lang.traineddata").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        FileLogger.i(TAG, "语言包已释放到 ${targetDir.absolutePath}")
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "TesseractOcr"
        const val LANGUAGES = "chi_sim+eng"
        const val TESSDATA_PARENT = "tessdata-parent"
        const val TESSDATA_DIR = "tessdata"
        /** 影子屏截图单张可达数百 KB，缓存条目按张数限制即可。 */
        const val CACHE_SIZE = 8
    }
}
