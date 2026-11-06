package com.aharou.feature.voice.tts

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一个克隆音色的元数据。 */
data class VoiceProfile(
    val id: String,
    val name: String,
    /** 参考音频的逐字文本：ZipVoice 靠它对齐音色，错一个字音质就掉。 */
    val referenceText: String,
    val sampleRate: Int,
    val createdAt: Long,
    /**
     * 云端克隆出来的音色 ID（硅基流动的 `speech:名字:id:id`）。
     *
     * 非空时朗读优先用云端（又快又清楚）；断网或服务出错再回落本机 ZipVoice。
     */
    val cloudVoiceUri: String = "",
)

/** 元数据 + 参考音频样本（[-1,1] 归一化）。 */
data class LoadedVoiceProfile(
    val profile: VoiceProfile,
    val samples: FloatArray,
)

/**
 * 克隆音色的落盘。
 *
 * 布局：`filesDir/voice_profiles/<id>/` 下 `meta.json` 与 `sample.f32`（Float32 小端）。
 * 不存 wav：解出来就是要喂给识别引擎的 FloatArray，转一道反而多一次解析与容错点；
 * 十来秒的样本按 16k 算也就几百 KB，没必要压缩。
 */
@Singleton
class VoiceProfileStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val root: File get() = File(context.filesDir, PROFILE_ROOT).apply { mkdirs() }

    /** 列出全部音色，按创建时间倒序。坏掉的目录直接跳过，不因为一条脏数据让整个列表打不开。 */
    suspend fun list(): List<VoiceProfile> = withContext(Dispatchers.IO) {
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    /** 读取指定音色的元数据与参考音频；缺文件或解析失败返回 null。 */
    suspend fun load(id: String): LoadedVoiceProfile? = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        if (!dir.isDirectory) return@withContext null
        val profile = runCatching { readMeta(dir) }.getOrNull() ?: return@withContext null
        val sampleFile = File(dir, SAMPLE_FILE)
        if (!sampleFile.isFile) return@withContext null
        val bytes = runCatching { sampleFile.readBytes() }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty() || bytes.size % 4 != 0) return@withContext null
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining())
        floats.get(samples)
        LoadedVoiceProfile(profile, samples)
    }

    /** 保存一段新音色，返回落盘后的元数据。 */
    suspend fun save(
        name: String,
        samples: FloatArray,
        sampleRate: Int,
        referenceText: String,
    ): VoiceProfile = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) throw IOException("参考音频为空")
        val id = "vp-${System.currentTimeMillis().toString(36)}"
        val dir = File(root, id).apply { mkdirs() }
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        File(dir, SAMPLE_FILE).writeBytes(buffer.array())
        val profile = VoiceProfile(
            id = id,
            name = name.ifBlank { DEFAULT_NAME },
            referenceText = referenceText.trim(),
            sampleRate = sampleRate,
            createdAt = System.currentTimeMillis(),
        )
        File(dir, META_FILE).writeText(profile.toJson().toString())
        FileLogger.i(TAG, "保存音色 ${profile.name}：${samples.size} 采样点 / ${sampleRate}Hz")
        profile
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        if (dir.isDirectory) dir.deleteRecursively()
        Unit
    }

    private fun readMeta(dir: File): VoiceProfile {
        val raw = File(dir, META_FILE).readText()
        val json = JSONObject(raw)
        return VoiceProfile(
            id = dir.name,
            name = json.optString("name", DEFAULT_NAME),
            referenceText = json.optString("referenceText", ""),
            sampleRate = json.optInt("sampleRate", 16000),
            createdAt = json.optLong("createdAt", 0L),
            cloudVoiceUri = json.optString("cloudVoiceUri", ""),
        )
    }

    private fun VoiceProfile.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("referenceText", referenceText)
        put("sampleRate", sampleRate)
        put("createdAt", createdAt)
        put("cloudVoiceUri", cloudVoiceUri)
    }

    /** 把云端音色 ID 写回档案；[uri] 为空即清掉云端关联。 */
    suspend fun updateCloudVoiceUri(id: String, uri: String) = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        if (!dir.isDirectory) return@withContext
        val profile = runCatching { readMeta(dir) }.getOrNull() ?: return@withContext
        File(dir, META_FILE).writeText(profile.copy(cloudVoiceUri = uri).toJson().toString())
    }

    private companion object {
        const val TAG = "VoiceProfileStore"
        const val PROFILE_ROOT = "voice_profiles"
        const val META_FILE = "meta.json"
        const val SAMPLE_FILE = "sample.f32"
        const val DEFAULT_NAME = "我的音色"
    }
}
