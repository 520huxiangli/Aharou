package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.rule.RuleFileRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则文件 → 配置通道。
 *
 * 规则正文是用户可配置项，接进来之后 AI 能直接把「每次都要执行的命令」写进全局或项目规则，
 * 不必让人进设置页手打。读写都走 [RuleFileRepository]，与注入侧／设置页同一份解析口径
 * （AGENTS.md → CLAUDE.md → rules.md，写回当前生效那一份）。
 *
 * `rules.d` 片段不拆成集合条目：它是目录式的，用文件工具在 `~/.aharou/rules.d/` 或
 * `<工作区>/rules.d/` 下增删更直接，这里只给只读清单。
 */
@Singleton
class ConfigRuleFields @Inject constructor(
    private val rules: RuleFileRepository,
    private val workspaceRepository: WorkspaceRepository
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "rules.global",
                displayName = "全局规则",
                description = "所有工作区共用的规则正文，写在 ~/.aharou/AGENTS.md（目录里已有 CLAUDE.md / rules.md 时写回生效那一份）；空串表示清空。每轮对话都会注入，只放常驻约定。",
                valueSchema = ConfigSchema.Str(maxLength = MAX_CHARS),
                reader = { ConfigValue.Str(rules.readGlobal().content) },
                writer = { value -> rules.saveGlobal(strOf(value)) }
            )
        )
        registry.register(
            ClosureField(
                path = "rules.project",
                displayName = "项目规则",
                description = "当前工作区的规则正文，写在 <工作区>/AGENTS.md（同目录有 CLAUDE.md / rules.md 时写回生效那一份）；没有选中工作区时写入会报错。",
                valueSchema = ConfigSchema.Str(maxLength = MAX_CHARS),
                reader = {
                    val root = workspaceRepository.currentPath()
                    val content = root.takeIf { it.isNotBlank() }?.let { rules.readProject(it)?.content }
                    ConfigValue.Str(content.orEmpty())
                },
                writer = { value ->
                    val root = workspaceRepository.currentPath()
                    if (root.isBlank()) throw ConfigError.InvalidValue("当前没有选中工作区，无法写项目规则")
                    rules.saveProject(root, strOf(value))
                }
            )
        )
        registry.register(
            ReadOnlyField(
                path = "rules.fragments",
                displayName = "规则片段",
                description = "rules.d 目录下的片段文件名（全局在前、项目在后，逗号分隔）。改片段请直接用文件工具读写对应目录。",
                valueSchema = ConfigSchema.Str(),
                reader = {
                    val global = rules.readGlobal().fragments
                    val root = workspaceRepository.currentPath()
                    val project = root.takeIf { it.isNotBlank() }
                        ?.let { rules.readProject(it)?.fragments }
                        .orEmpty()
                        .map { "project:$it" }
                    ConfigValue.Str((global + project).joinToString(","))
                }
            )
        )
    }

    private fun strOf(value: ConfigValue): String =
        (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")

    private companion object {
        /** 与注入侧的单文件上限一致：写得更长也不会被注入，索性挡在写入口。 */
        const val MAX_CHARS = 32_000
    }
}
