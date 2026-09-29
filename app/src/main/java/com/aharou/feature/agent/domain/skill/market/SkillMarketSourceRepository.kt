package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 用户自定义的技能市场源：`filesDir/aharou/skill-sources.json`。
 *
 * 与官方源清单（`data/skills.json`，远端托管、随版本更新）分开存放——那个是只读的出厂内容，
 * 这个是用户自己的，升级不能覆盖。[SkillMarketCatalog] 读取时把两者合并，用户源同名可覆盖官方源。
 */
@Singleton
class SkillMarketSourceRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    /** 全部自定义源（id → 定义）。 */
    fun all(): Map<String, SkillMarketSourceDef> = read()

    fun exists(id: String): Boolean = id in read()

    fun add(id: String, definition: SkillMarketSourceDef) {
        write(read() + (id to definition))
    }

    /** 按 id 删除自定义源；不存在时静默返回。 */
    fun remove(id: String) {
        val current = read()
        if (id !in current) return
        write(current - id)
    }

    private fun read(): Map<String, SkillMarketSourceDef> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<SkillMarketData>(f.readText(Charsets.UTF_8)).sources
        }.getOrElse {
            FileLogger.w(TAG, "自定义源解析失败：${it.message}")
            emptyMap()
        }
    }

    private fun write(sources: Map<String, SkillMarketSourceDef>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeTextSafely(prettyJson.encodeToString(SkillMarketData(sources)), TAG)
        }.onFailure { FileLogger.e(TAG, "写入自定义源失败", it) }
    }

    private companion object {
        const val TAG = "SkillMarketSourceRepository"
        const val FILE_NAME = "skill-sources.json"
        val json = Json { ignoreUnknownKeys = true }
        val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
