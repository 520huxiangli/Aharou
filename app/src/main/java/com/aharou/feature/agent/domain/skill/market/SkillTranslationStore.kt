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
private data class SkillTranslationData(
    val items: Map<String, SkillTranslation> = emptyMap()
)

/**
 * 技能市场的译文缓存：`filesDir/aharou/skill-translations.json`，键是「仓库@技能目录」。
 *
 * 翻译花的是用户自己的 token，所以译文要跨会话留着：换源、退出重进、重启都不该重翻一遍。
 * 写入失败只记日志——缓存没落盘顶多下次多翻一次，不影响当前显示。
 */
@Singleton
class SkillTranslationStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    @Volatile
    private var cached: Map<String, SkillTranslation>? = null

    /** 全部译文（内存命中直接返回，否则读盘一次）。 */
    fun all(): Map<String, SkillTranslation> = cached ?: read().also { cached = it }

    fun putAll(items: Map<String, SkillTranslation>) {
        if (items.isEmpty()) return
        val merged = all() + items
        cached = merged
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeTextSafely(json.encodeToString(SkillTranslationData(merged)), TAG)
        }.onFailure { FileLogger.e(TAG, "写入译文缓存失败", it) }
    }

    private fun read(): Map<String, SkillTranslation> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<SkillTranslationData>(f.readText(Charsets.UTF_8)).items
        }.getOrElse {
            FileLogger.w(TAG, "译文缓存解析失败：${it.message}")
            emptyMap()
        }
    }

    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    private companion object {
        const val TAG = "SkillTranslationStore"
        const val FILE_NAME = "skill-translations.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
