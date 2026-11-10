package com.aharou.feature.localmodel.data

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** 清单里一个要下载的文件。单文件模型只有一项，MNN 之类是多文件。 */
internal data class LocalModelFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** 一个可安装的本地模型条目。 */
internal data class LocalModelEntry(
    val id: String,
    val name: String,
    val description: String,
    /** chat / stt / tts */
    val kind: String,
    /** llama-cpp / mnn */
    val engine: String,
    val license: String,
    val homepage: String,
    /** 下载根地址，文件用 path 拼在后面 */
    val downloadBase: String,
    val files: List<LocalModelFile>,
    val tags: List<String>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** 当前能真跑起来的才算可安装：MNN 引擎还没接，条目留着但装不了。 */
    val runnable: Boolean get() = engine == ENGINE_LLAMA_CPP

    companion object {
        const val ENGINE_LLAMA_CPP = "llama-cpp"
        const val ENGINE_MNN = "mnn"
    }
}

/**
 * 本地模型清单，随 APK 打包（assets/local-models.json）。
 *
 * 模型文件不进安装包，这里只有元信息：来源、大小、sha256、许可证。
 * 条目数据取自各模型在 Hugging Face 上的固定 commit，换版本要连 sha256 一起换。
 */
@Singleton
internal class LocalModelCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val cached: List<LocalModelEntry> by lazy { load() }

    fun entries(): List<LocalModelEntry> = cached

    fun find(id: String): LocalModelEntry? = cached.firstOrNull { it.id == id }

    private fun load(): List<LocalModelEntry> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val models = root.optJSONArray("models") ?: JSONArray()
        buildList {
            for (i in 0 until models.length()) {
                val item = models.getJSONObject(i)
                val files = item.optJSONArray("files")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val file = array.getJSONObject(index)
                            add(
                                LocalModelFile(
                                    path = file.optString("path"),
                                    sizeBytes = file.optLong("sizeBytes"),
                                    sha256 = file.optString("sha256"),
                                )
                            )
                        }
                    }
                } ?: listOf(
                    LocalModelFile(
                        path = item.optString("file"),
                        sizeBytes = item.optLong("sizeBytes"),
                        sha256 = item.optString("sha256"),
                    )
                )
                val base = item.optString("downloadBase")
                if (base.isBlank() || files.isEmpty()) continue
                add(
                    LocalModelEntry(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        description = item.optString("description"),
                        kind = item.optString("kind", "chat"),
                        engine = item.optString("engine"),
                        license = item.optString("license"),
                        homepage = item.optString("homepage"),
                        downloadBase = base,
                        files = files,
                        tags = item.optJSONArray("tags")?.let { array ->
                            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
                        }.orEmpty(),
                    )
                )
            }
        }
    }.onFailure { FileLogger.w(TAG, "本地模型清单读取失败", it) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "LocalModelCatalog"
        const val ASSET = "local-models.json"
    }
}
