package com.aharou.feature.agent.domain.reminder

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则提醒的配置来源：项目级 `.aharou/reminders.json` > 全局 `~/.aharou/reminders.json` > 内置
 * `assets/reminders.default.json`。同 id 条目按上述优先级覆盖，高优先级的 `enabled=false` 也能压掉低优先级的同名规则。
 *
 * 任何一层解析失败都只丢掉那一层，不影响另外两层（提醒是旁路能力，读不到就当没有，绝不能拖累正常对话）。
 */
@Singleton
class ReminderRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
) {
    private data class CacheEntry(
        val projectStamp: Long,
        val globalStamp: Long,
        val rules: List<ReminderRule>,
    )

    // 缓存 key 用项目根路径：会话在多个工作区并存，配置不能串台。
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun rules(projectRoot: String?): List<ReminderRule> = withContext(Dispatchers.IO) {
        val projectFile = projectRoot?.takeIf { it.isNotBlank() }
            ?.let { File(it, PROJECT_REMINDERS) }
        val globalFile = File(containerInstaller.aharouDir, GLOBAL_REMINDERS)
        val projectStamp = projectFile?.lastModified() ?: -1L
        val globalStamp = globalFile.lastModified()
        val key = projectRoot.orEmpty()

        cache[key]?.let { if (it.projectStamp == projectStamp && it.globalStamp == globalStamp) return@withContext it.rules }

        val merged = merge(
            builtin = runCatching {
                context.assets.open(BUILTIN_REMINDERS).bufferedReader().use { parse(it.readText(), BUILTIN_REMINDERS) }
            }.getOrElse { emptyList() },
            global = if (globalFile.isFile) parse(globalFile.readText(), globalFile.name) else emptyList(),
            project = projectFile?.takeIf { it.isFile }?.let { parse(it.readText(), it.path) } ?: emptyList(),
        )
        cache[key] = CacheEntry(projectStamp, globalStamp, merged)
        merged
    }

    /** 同 id 后者覆盖前者；顺序按「内置 → 全局 → 项目」排，保证规则执行顺序稳定可预期。 */
    private fun merge(
        builtin: List<ReminderRule>,
        global: List<ReminderRule>,
        project: List<ReminderRule>,
    ): List<ReminderRule> {
        val byId = LinkedHashMap<String, ReminderRule>()
        builtin.forEach { byId[it.id] = it }
        global.forEach { byId[it.id] = it }
        project.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    private fun parse(text: String, source: String): List<ReminderRule> = runCatching {
        ReminderJson.decodeFromString(ReminderConfigFile.serializer(), text).reminders
    }.onFailure {
        FileLogger.w(TAG, "规则提醒配置解析失败，已跳过：$source - ${it.message}")
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "ReminderRepository"
        private const val BUILTIN_REMINDERS = "reminders.default.json"
        private const val PROJECT_REMINDERS = ".aharou/reminders.json"
        private const val GLOBAL_REMINDERS = "reminders.json"
    }
}
