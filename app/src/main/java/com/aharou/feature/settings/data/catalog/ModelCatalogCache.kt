package com.aharou.feature.settings.data.catalog

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

// 运行时模型目录的磁盘缓存：正文与元数据（ETag / 抓取时间）分开两个文件，
// 正文是 models.dev 原始目录过滤后的 JSON（只含内置官方 provider），元数据是按键值 JSON。
// 写盘一律「写临时文件 -> fsync -> rename」，避免进程被杀时留下半份正文让解析整份失败。
@Singleton
class ModelCatalogCache @Inject constructor(
    @param:ApplicationContext context: Context
) {
    data class Snapshot(val body: String, val etag: String?, val fetchedAt: Long)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val dir = File(context.filesDir, CACHE_DIR_NAME)
    private val bodyFile = File(dir, BODY_FILE_NAME)
    private val metaFile = File(dir, META_FILE_NAME)

    // 读缓存正文与元数据；正文缺失或为空返回 null。元数据损坏 / 缺失时退回文件修改时间，
    // 不算失败——正文仍然可用，只是这次请求会带上完整正文而不是 304。
    fun read(): Snapshot? {
        if (!bodyFile.isFile) return null
        val body = runCatching { bodyFile.readText(Charsets.UTF_8) }.getOrNull()
        if (body.isNullOrBlank()) return null
        val meta = readMeta()
        return Snapshot(
            body = body,
            etag = meta?.etag,
            fetchedAt = meta?.fetchedAt ?: bodyFile.lastModified()
        )
    }

    // 只取未过期的缓存；maxAgeMs <= 0 表示不看过期时间（仍要求缓存存在）。
    fun readFresh(maxAgeMs: Long): Snapshot? {
        val snapshot = read() ?: return null
        if (maxAgeMs > 0 && System.currentTimeMillis() - snapshot.fetchedAt > maxAgeMs) return null
        return snapshot
    }

    // 写入正文与元数据；正文落盘失败返回 false，调用方据此决定是否认为刷新成功。
    // 元数据写失败不影响正文可用性，只记日志（下次请求拿不到 ETag，退化为全量拉取）。
    fun write(body: String, etag: String?, fetchedAt: Long = System.currentTimeMillis()): Boolean {
        if (body.isBlank()) return false
        if (!writeAtomically(bodyFile, body)) {
            FileLogger.w(TAG, "模型目录正文缓存写入失败")
            return false
        }
        if (!writeAtomically(metaFile, encodeMeta(etag, fetchedAt))) {
            FileLogger.w(TAG, "模型目录元数据写入失败，下次将退化为全量拉取")
        }
        return true
    }

    // 304 时正文未变、但这次条件请求已确认缓存仍有效：只更新抓取时间，避免缓存被一直判为过期。
    fun markValidated(fetchedAt: Long = System.currentTimeMillis()): Boolean {
        if (!bodyFile.isFile) return false
        val currentEtag = readMeta()?.etag
        return writeAtomically(metaFile, encodeMeta(currentEtag, fetchedAt))
    }

    fun clear() {
        runCatching {
            bodyFile.delete()
            metaFile.delete()
        }
    }

    private data class Meta(val etag: String?, val fetchedAt: Long?)

    private fun readMeta(): Meta? = runCatching {
        if (!metaFile.isFile) return@runCatching null
        val obj = json.parseToJsonElement(metaFile.readText(Charsets.UTF_8)).jsonObject
        Meta(
            etag = obj["etag"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            fetchedAt = obj["fetchedAt"]?.jsonPrimitive?.longOrNull
        )
    }.getOrNull()

    private fun encodeMeta(etag: String?, fetchedAt: Long): String = buildJsonObject {
        put("fetchedAt", JsonPrimitive(fetchedAt))
        if (!etag.isNullOrBlank()) put("etag", JsonPrimitive(etag))
    }.toString()

    private fun writeAtomically(target: File, content: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        // 个别文件系统上 rename 到已存在的目标会失败：删一次目标再重试，仍失败则清理临时文件。
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching false
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        const val TAG = "ModelCatalogCache"
        const val CACHE_DIR_NAME = "model_catalog"
        const val BODY_FILE_NAME = "catalog.json"
        const val META_FILE_NAME = "catalog.meta.json"
        private const val TMP_SUFFIX = ".tmp"
    }
}
