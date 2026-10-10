package com.aharou.feature.agent.domain.knowledge

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 用户自己加的知识库源：`filesDir/aharou/knowledge-sources.json`。
 *
 * 与出厂源清单（`data/knowledge.json`，远端托管、随版本更新）分开存放——那是只读的内置内容，
 * 这个是用户自己的，升级不能覆盖。[KnowledgeCatalog] 读取时把两者合并，用户源同名可覆盖内置源。
 */
@Singleton
class KnowledgeSourceRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    /** 全部自定义源（id → 定义）。 */
    fun all(): Map<String, KnowledgeSourceDef> = read()

    fun add(id: String, definition: KnowledgeSourceDef) {
        write(read() + (id to definition))
    }

    /** 按 id 删除自定义源；不存在时静默返回。 */
    fun remove(id: String) {
        val current = read()
        if (id !in current) return
        write(current - id)
    }

    private fun read(): Map<String, KnowledgeSourceDef> {
        val target = file()
        if (!target.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<KnowledgeData>(target.readText(Charsets.UTF_8)).sources
        }.getOrElse {
            FileLogger.w(TAG, "自定义知识库源解析失败：${it.message}")
            emptyMap()
        }
    }

    private fun write(sources: Map<String, KnowledgeSourceDef>) {
        runCatching {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeTextSafely(prettyJson.encodeToString(KnowledgeData(sources = sources)), TAG)
        }.onFailure { FileLogger.e(TAG, "写入自定义知识库源失败", it) }
    }

    private companion object {
        const val TAG = "KnowledgeSourceRepository"
        const val FILE_NAME = "knowledge-sources.json"
        val json = Json { ignoreUnknownKeys = true }
        val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
