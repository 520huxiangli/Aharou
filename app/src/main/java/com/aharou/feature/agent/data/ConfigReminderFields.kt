package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.reminder.ReminderConfigFile
import com.aharou.feature.agent.domain.reminder.ReminderJson
import com.aharou.feature.agent.domain.reminder.ReminderRule
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则提醒 → 配置通道。
 *
 * 提醒是写给我自己（AI）看的，不做界面；但「新增可配置项必须接 config」是硬规则，
 * 所以这里把项目级 `.aharou/reminders.json` 的增删改暴露成 `reminders` 集合，
 * 以后被纠正时不用手改文件也能加一条。
 *
 * 只接项目级：提醒多半绑定具体仓库的流程（发版、迁移编号），写进全局文件会让别的项目也吃到。
 */
@Singleton
class ConfigReminderFields @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(ReminderCollection())
    }

    private fun rulesFile(): File {
        val root = workspaceRepository.currentPath()
        if (root.isBlank()) throw ConfigError.InvalidValue("当前没有选中工作区，无法读写规则提醒")
        return File(root, PROJECT_REMINDERS)
    }

    private fun load(): MutableList<ReminderRule> {
        val file = rulesFile()
        if (!file.isFile) return mutableListOf()
        return runCatching {
            ReminderJson.decodeFromString(ReminderConfigFile.serializer(), file.readText()).reminders.toMutableList()
        }.getOrElse { throw ConfigError.InvalidValue("reminders.json 解析失败：${it.message}") }
    }

    private fun save(rules: List<ReminderRule>) {
        val file = rulesFile()
        file.parentFile?.mkdirs()
        file.writeText(ReminderJson.encodeToString(ReminderConfigFile.serializer(), ReminderConfigFile(rules)))
    }

    private inner class ReminderCollection : ConfigCollection {
        override val basePath = "reminders"
        override val displayName = "规则提醒"
        override val description =
            "按工具参数/结果匹配的提醒规则，命中后附在工具结果末尾（只提醒、不拦截）。" +
                "add 时传一条完整规则的 JSON 字符串，见 assets/reminders.default.json。"

        override fun childIds(): List<String> = runCatching { load().map { it.id } }.getOrDefault(emptyList())

        override fun fields(forId: String): List<ConfigField> {
            val rule = runCatching { load().firstOrNull { it.id == forId } }.getOrNull() ?: return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.enabled",
                    displayName = "启用（$forId）",
                    description = "关掉后这条提醒不再触发；全局/内置的同 id 规则也会被这条压住。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(rule.enabled) },
                    writer = { value ->
                        val enabled = (value as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        save(load().map { if (it.id == forId) it.copy(enabled = enabled) else it })
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.message",
                    displayName = "提醒正文（$forId）",
                    description = "命中时追加到工具结果末尾的那句话；建议写「该做什么 + 去读哪条记忆」。",
                    valueSchema = ConfigSchema.Str(),
                    revertable = true,
                    reader = { ConfigValue.Str(rule.message) },
                    writer = { value ->
                        val message = (value as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        save(load().map { if (it.id == forId) it.copy(message = message) else it })
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val json = (payload as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("请传一条规则的 JSON 字符串（形如 {\"id\":\"...\",\"layer\":\"tool\",...}）")
            val rule = runCatching { ReminderJson.decodeFromString(ReminderRule.serializer(), json) }
                .getOrElse { throw ConfigError.InvalidValue("规则 JSON 解析失败：${it.message}") }
            if (rule.id.isBlank()) throw ConfigError.InvalidValue("规则缺少 id")
            save(load().filterNot { it.id == rule.id } + rule)
            return rule.id
        }

        override fun remove(id: String) {
            val rules = load()
            if (rules.none { it.id == id }) throw ConfigError.InvalidValue("没有这条规则：$id")
            save(rules.filterNot { it.id == id })
        }
    }

    private companion object {
        const val PROJECT_REMINDERS = ".aharou/reminders.json"
    }
}
