package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.permission.PermissionDecision
import com.aharou.feature.agent.domain.permission.PermissionRule
import com.aharou.feature.agent.domain.permission.PermissionRulesRepository
import com.aharou.feature.agent.domain.permission.PermissionScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * 工具授权规则 → config 通道。
 *
 * 只暴露**全局长期规则**（App 私有目录里的 `permissions.json`）：会话级与 24 小时的临时授权是运行期
 * 状态，改了会和正在跑的会话打架；项目级规则跟着仓库走，用 git 管更自然。
 *
 * 条目 id 用 `r0` / `r1`… 而不是紧凑格式 `Bash(git pull)`：pattern 里常有 `.`（`git config user.name`），
 * 直接当路径段会把 config 路径切碎。
 */
@Singleton
class ConfigPermissionFields @Inject constructor(
    private val rules: PermissionRulesRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(RulesCollection())
    }

    private inner class RulesCollection : ConfigCollection {
        override val basePath = "permission.rules"
        override val displayName = "工具授权规则（全局）"
        override val description =
            "长期有效的全局授权规则；会话内与 24 小时临时授权不在此列。子字段：tool / pattern / decision / expires_at。" +
                "新增时传 {\"tool\":\"Bash\",\"pattern\":\"git pull\",\"decision\":\"ALLOW\"}；非 shell 工具 pattern 填 *。"
        override val addable = true
        override val removable = true

        override fun childIds(): List<String> = globalRules().indices.map { "r$it" }

        override fun fields(forId: String): List<ConfigField> {
            val index = indexOf(forId) ?: return emptyList()
            val rule = globalRules().getOrNull(index) ?: return emptyList()
            val label = rule.toCompact()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.tool",
                    displayName = "工具（$label）",
                    description = "工具名，如 Bash、readFile。要换工具请删掉这条重建。",
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(rule.toolName) },
                    writer = { throw ConfigError.PermissionDenied("工具名为只读字段，换工具请重建规则") },
                ),
                ClosureField(
                    path = "$basePath.$forId.pattern",
                    displayName = "匹配模式（$label）",
                    description = "shell 工具是命令前缀（`git pull` 只命中 git pull）；其它工具为 `*` 表示整个工具。",
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(rule.pattern) },
                    writer = { throw ConfigError.PermissionDenied("匹配模式为只读字段，改它请重建规则") },
                ),
                ClosureField(
                    path = "$basePath.$forId.decision",
                    displayName = "判定（$label）",
                    description = "ALLOW 直接放行；DENY 直接拒绝（DENY 跨作用域优先于 ALLOW）。",
                    valueSchema = ConfigSchema.Str(),
                    revertable = true,
                    reader = { ConfigValue.Str(rule.decision.name) },
                    writer = { v ->
                        val name = (v as? ConfigValue.Str)?.value?.uppercase()
                            ?: throw ConfigError.TypeMismatch("string")
                        val decision = PermissionDecision.entries.firstOrNull { it.name == name }
                            ?: throw ConfigError.InvalidValue("decision 只能是 ALLOW 或 DENY")
                        val current = globalRules().toMutableList()
                        if (index !in current.indices) throw ConfigError.InvalidValue("规则已变化，请重新读取")
                        current[index] = rule.copy(decision = decision)
                        runBlocking { rules.setGlobalRules(current) }
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.expires_at",
                    displayName = "过期时刻（$label）",
                    description = "epoch 毫秒；空表示长期有效。临时授权的时效不从这里改。",
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(rule.expiresAt?.toString().orEmpty()) },
                    writer = { throw ConfigError.PermissionDenied("过期时刻为只读字段") },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("permission.rules 的新项需为 JSON 对象")
            val tool = (obj["tool"] as? ConfigValue.Str)?.value?.trim()?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 tool（如 Bash）")
            val pattern = (obj["pattern"] as? ConfigValue.Str)?.value?.trim()
                ?.takeIf { it.isNotBlank() } ?: PermissionRule.WHOLE_TOOL
            val decisionName = (obj["decision"] as? ConfigValue.Str)?.value?.uppercase()
            val decision = decisionName?.let { name ->
                PermissionDecision.entries.firstOrNull { it.name == name }
                    ?: throw ConfigError.InvalidValue("decision 只能是 ALLOW 或 DENY")
            } ?: PermissionDecision.ALLOW
            val rule = PermissionRule(tool, pattern, decision)
            runBlocking { rules.add(PermissionScope.GLOBAL, rule, "") }
            val index = globalRules().indexOfFirst { it.toolName == tool && it.pattern == pattern }
            if (index < 0) throw ConfigError.InvalidValue("规则写入后未找到，请重新读取")
            return "r$index"
        }

        override fun remove(id: String) {
            val index = indexOf(id) ?: throw ConfigError.InvalidValue("无效 id：$id")
            val rule = globalRules().getOrNull(index)
                ?: throw ConfigError.InvalidValue("规则不存在：$id")
            runBlocking { rules.removeGlobalRule(rule) }
        }
    }

    private fun indexOf(forId: String): Int? =
        forId.removePrefix("r").toIntOrNull()?.takeIf { forId.startsWith("r") }

    private fun globalRules(): List<PermissionRule> = runBlocking { rules.getGlobalRulesOnce() }
}
