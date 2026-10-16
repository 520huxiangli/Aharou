package com.aharou.feature.plugin.data

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条已安装插件的记录（跨升级保留）。 */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String = "",
    val version: String = "",
    val installedAt: Long = 0L,
    /** 是否随应用启动自动拉起（仅对常驻服务插件有意义）。 */
    val autoStart: Boolean = false,
)

@Serializable
private data class PluginRecordData(val installed: Map<String, PluginRecord> = emptyMap())

/**
 * 插件安装记录：`filesDir/aharou/plugin-installs.json`。
 *
 * 与 `skill-installs.json` 同一取舍——独立于插件目录本身，只回答「装了哪些、什么版本、要不要自启」。
 * 插件目录被手工删除时记录成为孤儿，列表加载前会对一次账。
 */
@Singleton
class PluginRecordStore @Inject constructor(
    private val containerInstaller: ContainerInstaller,
) {
    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    /** 全部安装记录（id → 记录）。 */
    fun all(): Map<String, PluginRecord> = read().installed

    fun get(id: String): PluginRecord? = read().installed[id]

    fun save(record: PluginRecord) {
        write(read().installed + (record.id to record))
    }

    /** 只更新自启开关；记录不存在时静默返回。 */
    fun setAutoStart(id: String, autoStart: Boolean) {
        val current = read().installed[id] ?: return
        write(read().installed + (id to current.copy(autoStart = autoStart)))
    }

    fun remove(id: String) {
        val installed = read().installed
        if (id !in installed) return
        write(installed - id)
    }

    private fun read(): PluginRecordData {
        val f = file()
        if (!f.isFile) return PluginRecordData()
        return runCatching {
            json.decodeFromString<PluginRecordData>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "插件记录解析失败：${it.message}")
            PluginRecordData()
        }
    }

    private fun write(installed: Map<String, PluginRecord>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeTextSafely(prettyJson.encodeToString(PluginRecordData(installed)), TAG)
        }.onFailure { FileLogger.e(TAG, "写入插件记录失败", it) }
    }

    private companion object {
        const val TAG = "PluginRecordStore"
        const val FILE_NAME = "plugin-installs.json"
        val json = Json { ignoreUnknownKeys = true }
        val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
