package com.aharou.feature.voice.data

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端合成结果的本地磁盘缓存。
 *
 * 同一句话在「自动朗读」与「通话播报」里会被反复合成，云端 TTS 既慢又计费；
 * 按 文本+音色+模型+供应商 取键落盘，命中就直接播本地文件，省掉一次往返。
 *
 * 键里带上音色/模型/baseUrl：换了音色或供应商就是另一段音频，不能串味。
 */
@Singleton
internal class TtsCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dir: File get() = File(context.filesDir, DIR_NAME)

    /** 命中且文件非空才算，返回可直接播放的文件。 */
    suspend fun get(key: String): File? = withContext(Dispatchers.IO) {
        val file = File(dir, "$key.mp3")
        if (file.isFile && file.length() > 0) file else null
    }

    /** 写入缓存并做一次容量回收。 */
    suspend fun put(key: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@withContext
            File(dir, "$key.mp3").writeBytes(bytes)
            prune()
        }.onFailure { FileLogger.w(TAG, "写入 TTS 缓存失败", it) }
    }

    /**
     * 容量回收：文件数或总字节超限时，按最后修改时间从旧到新删，直到回到限内。
     * 用最后修改时间而不是访问时间，是因为访问时间在部分文件系统上不更新。
     */
    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var count = files.size
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (count <= MAX_FILES && total <= MAX_BYTES) break
            val length = file.length()
            if (file.delete()) {
                count -= 1
                total -= length
            }
        }
    }

    companion object {
        private const val TAG = "TtsCache"
        private const val DIR_NAME = "tts_cache"

        /** 文件数上限。 */
        private const val MAX_FILES = 200

        /** 总字节上限 20MB。 */
        private const val MAX_BYTES = 20L * 1024 * 1024

        /**
         * 缓存键：`text|voice|model|baseUrl` 的 sha256 十六进制。
         * 四项任一变化都该视作另一段音频，故全部并入键。
         */
        fun key(text: String, voice: String, model: String, baseUrl: String): String {
            val raw = "$text|$voice|$model|$baseUrl"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
