package com.aharou.feature.localmodel.data

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地模型的安装与登记。
 *
 * 每个模型一个目录 `files/localmodels/<id>/`，按清单里的相对路径落盘；
 * 下载先写 `.part`，校验大小与 sha256 通过才改名替换，
 * 并记进登记表——没登记的文件不参与删除与展示。
 */
@Singleton
internal class LocalModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: LocalModelCatalog,
) {

    enum class Stage { Downloading, Verifying, Writing }

    data class Progress(
        val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val stage: Stage,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** 已安装的模型：id → 落盘字节数。 */
    fun installed(): Map<String, Long> {
        val raw = prefs.getString(KEY_REGISTRY, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    put(item.optString("id"), item.optLong("sizeBytes"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun isInstalled(id: String): Boolean = installed().containsKey(id)

    /** 已安装模型占用的磁盘空间。 */
    fun usedBytes(): Long = rootDir().listFiles()?.sumOf { it.walkBottomUp().filter { f -> f.isFile }.sumOf { f -> f.length() } } ?: 0L

    fun modelDir(id: String): File = File(rootDir(), id)

    /** 供 llama.cpp 加载的权重文件路径；多文件模型取清单里第一个文件。 */
    fun primaryFile(entry: LocalModelEntry): File =
        File(modelDir(entry.id), entry.files.first().path)

    suspend fun install(
        id: String,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val entry = catalog.find(id) ?: return@withContext Result.failure(IllegalArgumentException("清单里没有 $id"))
        if (!entry.runnable) {
            return@withContext Result.failure(IllegalStateException("${entry.name} 需要 ${entry.engine} 引擎，暂不支持"))
        }
        val dir = modelDir(id)
        dir.mkdirs()
        val total = entry.totalBytes
        var done = 0L
        try {
            for (file in entry.files) {
                val target = File(dir, file.path)
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, target.name + ".part")
                download(entry.downloadBase + "/" + file.path, part) { read ->
                    onProgress(Progress(id, done + read, total, Stage.Downloading))
                }
                if (file.sizeBytes > 0 && part.length() != file.sizeBytes) {
                    part.delete()
                    throw IllegalStateException("${file.path} 大小不对：${part.length()} != ${file.sizeBytes}")
                }
                onProgress(Progress(id, done + file.sizeBytes, total, Stage.Verifying))
                if (file.sha256.isNotBlank() && !sha256(part).equals(file.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException("${file.path} 校验失败，可能下载不完整")
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    throw IllegalStateException("${file.path} 落盘失败")
                }
                done += file.sizeBytes
            }
            record(id, dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
            onProgress(Progress(id, total, total, Stage.Writing))
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.w(TAG, "安装本地模型失败：$id", e)
            dir.deleteRecursively()
            removeRecord(id)
            onProgress(Progress(id, done, total, Stage.Writing, e.message ?: "安装失败"))
            Result.failure(e)
        }
    }

    fun remove(id: String) {
        modelDir(id).deleteRecursively()
        removeRecord(id)
    }

    private fun download(url: String, target: File, onRead: (Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载失败 HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("下载无响应体")
            val length = body.contentLength()
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var total = 0L
                    while (input.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                        total += read
                        onRead(total)
                    }
                    if (length > 0 && total != length) throw IllegalStateException("下载中断：$total / $length")
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun record(id: String, sizeBytes: Long) {
        val current = installed().toMutableMap()
        current[id] = sizeBytes
        val array = JSONArray()
        current.forEach { (key, size) ->
            array.put(JSONObject().put("id", key).put("sizeBytes", size).put("installedAt", System.currentTimeMillis()))
        }
        prefs.edit().putString(KEY_REGISTRY, array.toString()).apply()
    }

    private fun removeRecord(id: String) {
        val current = installed().toMutableMap()
        current.remove(id)
        val array = JSONArray()
        current.forEach { (key, size) ->
            array.put(JSONObject().put("id", key).put("sizeBytes", size))
        }
        prefs.edit().putString(KEY_REGISTRY, array.toString()).apply()
    }

    private fun rootDir(): File = File(context.filesDir, "localmodels").apply { mkdirs() }

    private val prefs by lazy { context.getSharedPreferences("local_models", Context.MODE_PRIVATE) }

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val TAG = "LocalModelStore"
        const val KEY_REGISTRY = "installed"
    }
}
