package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SkillCategoryData(
    val items: Map<String, List<String>> = emptyMap()
)

/**
 * 技能市场的分类缓存：`filesDir/aharou/skill-categories.json`，键是「仓库@技能目录」。
 *
 * 打标花的是用户自己的 token，所以结果要跨会话留着：换源、退出重进、重启都不该重跑一遍。
 * 写入失败只记日志——缓存没落盘顶多下次重打一次，不影响当前显示。
 */
@Singleton
class SkillCategoryStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    @Volatile
    private var cached: Map<String, List<String>>? = null

    /** 全部分类（内存命中直接返回，否则读盘一次）。 */
    fun all(): Map<String, List<String>> = cached ?: read().also { cached = it }

    fun putAll(items: Map<String, List<String>>) {
        if (items.isEmpty()) return
        val merged = all() + items
        cached = merged
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeTextSafely(json.encodeToString(SkillCategoryData(merged)), TAG)
        }.onFailure { FileLogger.e(TAG, "写入技能分类缓存失败", it) }
    }

    private fun read(): Map<String, List<String>> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<SkillCategoryData>(f.readText(Charsets.UTF_8)).items
        }.getOrElse {
            FileLogger.w(TAG, "技能分类缓存解析失败：${it.message}")
            emptyMap()
        }
    }

    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    private companion object {
        const val TAG = "SkillCategoryStore"
        const val FILE_NAME = "skill-categories.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
