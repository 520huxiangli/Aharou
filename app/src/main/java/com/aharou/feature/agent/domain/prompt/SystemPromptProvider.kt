package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.rule.RuleRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.InjectPart
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 每个 Source 维护内容缓存，避免重复读取与格式化。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`（见 [BASE_FRAGMENTS]），可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（模式提醒、子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，主代理提示词只由自定义数字片段组成，
 * 不再注入任何内置来源；此时用 `{{AICODE_*}}` 变量按需取回动态内容。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val aharouMemoryStore: com.aharou.core.memory.AharouMemoryStore,
    private val promptFileResolver: PromptFileResolver,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val ruleRepository: RuleRepository
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // 每次都重新读盘：未编辑时字符串一致，KV Cache 照常命中；编辑后立即生效，无需重启。
        override fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) { return null }
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" } }" }
            val content = "可用技能 (skills)（格式为 名称: 何时使用；相关时用 loadSkill 传入名称取完整正文，详见上文「技能」说明）：\n当清单里有与当前任务对口的技能时，在合适的时机主动 `loadSkill` 加载并按其正文行事，让技能辅助你更规范、更高效地完成工作，而不是仅凭默认流程硬做。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 可用子代理部门索引（仅注入主代理）：只注入按部门汇总的恒定大小索引（部门 / 角色数 / 代表角色），
     * 不再逐条列出全部角色；主代理用 `task(department=..., agentQuery=...)` 由 [SubAgentProfileMatcher] 选角。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val content = SubAgentProfileMatcher.renderIndex(entries.map { it.definition })
            if (content.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /**
     * 按需规则块清单（仅注入主代理）：低频规则不进常驻片段，只给名称 + 摘要，
     * AI 需要时用 `loadRule` 取正文。会话级缓存，避免每轮扫盘抖动 system prompt。
     */
    private inner class AvailableRulesSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val rules = try {
                ruleRepository.listRules()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描按需规则失败: ${e.message}", e)
                return null
            }
            if (rules.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = rules.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无摘要）" }}" }
            val content = "按需规则 (rules)（格式为 名称: 何时使用；相关时用 `loadRule` 传入名称取完整正文）：\n" +
                "当清单里有与当前任务对口的规则时，先 `loadRule` 加载它，再按其正文行事。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            val body = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }
            cached = "项目规则 (来自 ~/workspace/${file.second}，务必遵守):\n${body.trim()}"
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    /**
     * 按工作区类型注入工程规约（Android / Flutter / Node）：识别特征文件后读取
     * `prompts/agent/workspace-<type>.md`；未识别出类型时不注入。
     */
    private inner class WorkspaceTypeSource : PromptSource {
        private val cachedByType = ConcurrentHashMap<String, String>()

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val type = detectType(ctx.projectRoot) ?: return null
            cachedByType[type]?.let { return it.ifEmpty { null } }
            val body = resolvePrompt("agent/workspace-$type.md").replace(LEADING_COMMENT, "").trim()
            cachedByType[type] = body
            return body.ifEmpty { null }
        }

        private fun detectType(projectRoot: String): String? {
            val dir = File(projectRoot)
            return when {
                File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile -> "android"
                File(dir, "pubspec.yaml").isFile -> "flutter"
                File(dir, "package.json").isFile -> "node"
                else -> null
            }
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val hasWorkspace = ctx.projectRoot.isNotBlank()
            return "当前上下文:\n- 项目根目录: ${if (hasWorkspace) "~/workspace" else "（未选择工作区）"}"
        }
    }

    private inner class CurrentTimeSource : PromptSource {
        override fun build(ctx: AgentContext): String = "[System] 当前本地时间: ${currentDate()}"
    }

    private inner class MemoryListSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        // 相关性排序只在本会话首次 build 时按当时的用户文本算一次：每轮重排会让提示词前缀跟着变、
        // 打断 KV 缓存，代价大于收益；会话通常围绕一个任务，首轮文本已能代表它。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val memories = try {
                memoryRepository.listMemoriesForPrompt(ctx.projectRoot, ctx.currentUserText)
            } catch (e: Exception) { return null }
            val content = MemoryPromptRenderer.render(memories).ifEmpty { null }

            // 记忆纪律紧跟清单注入：清单告诉模型「有什么」，纪律告诉它「何时必须写」。
            val full = listOf(content, memoryDiscipline()).mapNotNull { it }.joinToString("\n\n")
            if (full.isEmpty()) return null

            cachedByKey[key] = full
            trimIfNeeded()
            return full
        }

        /** 记忆纪律正文（无记忆清单时单独注入）。 */
        private fun memoryDiscipline(): String? =
            resolvePrompt(MEMORY_DISCIPLINE_FILE).replace(LEADING_COMMENT, "").trim().ifEmpty { null }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }

        fun invalidate(key: SourceCacheKey) {
            cachedByKey.remove(key)
        }
    }

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val availableRulesSource = AvailableRulesSource()
    private val memoryListSource = MemoryListSource()
    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceTypeSource = WorkspaceTypeSource()
    private val workspaceSource = WorkspaceSource()
    private val currentTimeSource = CurrentTimeSource()

    private val soulSource = SoulSource()

    /** 人格（SOUL.md）：外置文件、可由用户/Agent 直接修改；作为身份段注入。 */
    private inner class SoulSource : PromptSource {
        override fun build(ctx: AgentContext): String? =
            com.aharou.core.soul.SystemPromptBuilder.identitySection(context)
                .takeIf { it.isNotBlank() }
    }

    private val aharouMemorySource = AharouMemorySource()

    /** 记忆（核心档案 + 今日日志尾巴）：外置文件在 filesDir/aharou-global/memory（容器内可读写）。 */
    private inner class AharouMemorySource : PromptSource {
        override fun build(ctx: AgentContext): String? =
            runCatching { aharouMemoryStore.buildPromptSection() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
    }

    private val customDir: File
        get() = File(containerInstaller.aharouDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun findAgentDefinitionIncludingDisabled(name: String): AgentDefinition? =
        agentDefinitionRepository.findIncludingDisabled(name)

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val soulContent = soulSource.build(agentContext)
        val aharouMemoryContent = aharouMemorySource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memoriesContent = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        val rulesContent = availableRulesSource.build(agentContext)
        val workspaceTypeContent = workspaceTypeSource.build(agentContext)

        // 2. Workspace 上下文固定输出（内容已精简，无需快照占位）
        val effectiveWorkspaceContent = workspaceSource.build(agentContext)
        val timeContent = currentTimeSource.build(agentContext)

        // 3. 变量就地展开：片段里写了 {{AICODE_*}} 就替换为真实内容，并跳过下方对应的自动追加，避免重复。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memoriesContent,
            subAgentsContent,
            projectRules,
            effectiveWorkspaceContent,
            currentDate()
        )

        // 4. 组装最终提示词：把稳定不变的重头基线放最前面（享受 KV Cache），变化部分放末尾
        return buildString {
            append(staticContent)

            // 人格（SOUL.md）：身份段紧随基线；外置文件，Agent/用户可直接修改
            soulContent?.let { if (it.isNotEmpty()) { append("\n\n"); append(it) } }

            // 记忆（核心档案 + 今日日志）：外置文件在 filesDir/aharou-global/memory
            aharouMemoryContent?.let { if (it.isNotEmpty()) { append("\n\n"); append(it) } }

            if (SKILLS_VAR !in rawStatic) skillsContent?.let { append("\n\n"); append(it) }
            if (SUBAGENTS_VAR !in rawStatic) subAgentsContent?.let { append("\n\n"); append(it) }
            if (MEMORY_VAR !in rawStatic) memoriesContent?.let { append("\n\n"); append(it) }
            if (PROJECT_RULES_VAR !in rawStatic) projectRules?.let { append("\n\n"); append(it) }

            if (WORKSPACE_VAR !in rawStatic) {
                append("\n\n")
                append(effectiveWorkspaceContent)
            }
            rulesContent?.let { append("\n\n"); append(it) }
            workspaceTypeContent?.let { append("\n\n"); append(it) }
            if (DATE_VAR !in rawStatic) {
                append("\n\n")
                append(timeContent)
            }
        }
    }

    /**
     * [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 生效时：只输出 `prompts.custom/` 顶层的数字片段，
     * 不注入任何内置来源；动态内容仅通过 `{{AICODE_*}}` 变量按需取回。
     */
    private fun buildCustomOnly(ctx: AgentContext): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，系统提示词为空"
            )
            return ""
        }
        return renderVariables(
            content,
            activeSkillsSource.build(ctx),
            memoryListSource.build(ctx),
            subAgentListSource.build(ctx),
            projectRuleSource.build(ctx),
            workspaceSource.build(ctx),
            currentDate()
        )
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理部门索引（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String {
        val raw = buildString {
            if (InjectPart.MAIN_RULES in definition.inject) {
                append(staticRuleSource.build(agentContext))
                append("\n\n")
            }
            if (InjectPart.BASE in definition.inject) {
                append(subAgentBaseSource.build(agentContext))
                append("\n\n")
            }

            append("当前角色 (subagent: ${definition.name})：你是一个由主代理派发的子代理，拥有独立上下文，看不到主对话历史。")
            append("专注完成本会话交给你的任务，并在最后一条回复里给出完整结论——主代理只能读到你的最后一条回复，中间过程与工具结果它看不到。\n\n")
            append(definition.prompt)
        }
        val memories = memoryListSource.build(agentContext)
        return buildString {
            append(
                renderVariables(
                    raw,
                    activeSkillsSource.build(agentContext),
                    memories,
                    subAgentListSource.build(agentContext),
                    projectRuleSource.build(agentContext),
                    workspaceSource.build(agentContext),
                    currentDate()
                )
            )

            if (InjectPart.SKILLS in definition.inject && SKILLS_VAR !in raw) {
                activeSkillsSource.build(agentContext)?.let {
                    append("\n\n")
                    append(it)
                }
            }
            if (InjectPart.MEMORY in definition.inject && MEMORY_VAR !in raw) {
                memories?.let {
                    append("\n\n")
                    append(it)
                }
            }
            if (InjectPart.PROJECT_RULES in definition.inject && PROJECT_RULES_VAR !in raw) {
                projectRuleSource.build(agentContext)?.let {
                    append("\n\n")
                    append(it)
                }
            }

            if (WORKSPACE_VAR !in raw) {
                append("\n\n")
                append(workspaceSource.build(agentContext))
            }
            if (DATE_VAR !in raw) {
                append("\n\n")
                append(currentTimeSource.build(agentContext))
            }
        }
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memories: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        date: String
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = MemoryPromptRenderer.expandOnce(out, MEMORY_VAR, memories)
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRules.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(DATE_VAR, date)
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     *
     * 实现已抽到 [PromptFileResolver]，这里只做委托。
     */
    fun resolvePrompt(name: String): String = promptFileResolver.resolve(name)

    /** 失效记忆清单的会话级缓存：整理器写入新记忆后调用，让下一轮 system prompt 看到新清单。 */
    fun invalidateMemoryCache(sessionId: String?, projectRoot: String?) {
        memoryListSource.invalidate(SourceCacheKey(sessionId, projectRoot.orEmpty()))
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val MEMORY_DISCIPLINE_FILE = "agent/memory-discipline.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        val BASE_FRAGMENTS = linkedMapOf(
            0 to "00-identity.md",
            10 to "10-communication.md",
            15 to "15-project-rules.md",
            20 to "20-coding-discipline.md",
            30 to "30-comments.md",
            40 to "40-approach.md",
            50 to "50-safety.md",
            60 to "60-tools-and-paths.md",
            70 to "70-skills-and-mcp.md"
        )

        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_VAR = "{{AICODE_MEMORY}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
    }
}