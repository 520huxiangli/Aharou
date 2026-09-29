package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条技能安装记录：从哪个源、什么版本、什么时候装的。 */
@Serializable
data class SkillInstallRecord(
    val source: String = "",
    val version: String = "",
    val installedAt: Long = 0L
)

@Serializable
data class SkillInstallData(
    val installed: Map<String, SkillInstallRecord> = emptyMap()
)

/**
 * 技能安装记录：`filesDir/aharou/skill-installs.json`（跨升级保留）。
 *
 * 独立于 `skills.json` 的启停名单——那个只回答「技能是否启用」，这个只回答「从哪装的、什么版本」，
 * 用于市场列表里显示「已安装 / 有新版」。技能被手工删除时记录会成为孤儿，不影响其它功能。
 */
@Singleton
class SkillInstallRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    /** 某个技能的安装记录（名称为键，忽略大小写）；未记录返回 null。 */
    fun record(name: String): SkillInstallRecord? = read().installed[name.lowercase()]

    /** 全部安装记录。 */
    fun all(): Map<String, SkillInstallRecord> = read().installed

    fun save(name: String, record: SkillInstallRecord) {
        val data = read()
        write(data.copy(installed = data.installed + (name.lowercase() to record)))
    }

    /** 删除技能时清掉对应记录；没有记录时静默返回。 */
    fun remove(name: String) {
        val data = read()
        if (name.lowercase() !in data.installed) return
        write(data.copy(installed = data.installed - name.lowercase()))
    }

    private fun read(): SkillInstallData {
        val f = file()
        if (!f.isFile) return SkillInstallData()
        return runCatching {
            json.decodeFromString<SkillInstallData>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "安装记录解析失败：${it.message}")
            SkillInstallData()
        }
    }

    private fun write(data: SkillInstallData) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeTextSafely(prettyJson.encodeToString(data), TAG)
        }.onFailure { FileLogger.e(TAG, "写入安装记录失败", it) }
    }

    private companion object {
        const val TAG = "SkillInstallRepository"
        const val FILE_NAME = "skill-installs.json"
        val json = Json { ignoreUnknownKeys = true }
        val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
