package com.aharou.feature.agent.domain.subagent

/**
 * 子代理选角匹配器：把「全量清单 + 精确点名」改为「部门 + 关键词」路由。
 *
 * 纯本地、确定性：只读 [AgentDefinition] 的 name / description / filePath，不发起 AI 调用或网络请求。
 * 部门由定义文件名（`agents/engineering-code-reviewer.md`）的前缀派生，未知前缀归到 [GENERAL]；
 * 渲染出的部门索引每行只含「部门 / 角色数 / 代表角色」，体量与角色总数无关。
 */
object SubAgentProfileMatcher {

    /** 没有可识别部门前缀时的兜底部门。 */
    const val GENERAL = "general"

    /** 每个部门在索引里最多展示的代表角色数：决定索引体量恒定，不随角色总数增长。 */
    private const val MAX_REPRESENTATIVES = 2

    /** 匹配落空时，最多列出多少个同范围角色名供主代理改选。 */
    private const val MAX_MEMBERS_IN_HINT = 12

    private const val TOKEN_HIT = 3
    private const val SUBSTRING_HIT = 1
    private const val NAME_BONUS = 2

    /** 按字母/数字切词，保留连续的 CJK 串（中文关键词走子串命中）。 */
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * 部门前缀 → 规范部门名。按声明顺序（长前缀在前）匹配，让 `project-manager-senior`
     * 与 `project-management-*` 同归 `project-management`，`test-writer` 与 `testing-*` 同归 `testing`。
     */
    private val DEPARTMENT_PREFIXES: List<Pair<String, String>> = listOf(
        "project-management" to "project-management",
        "project-manager" to "project-management",
        "project" to "project-management",
        "game-development" to "game-development",
        "game" to "game-development",
        "level" to "game-development",
        "narrative" to "game-development",
        "economy" to "game-development",
        "blender" to "game-development",
        "technical" to "game-development",
        "engineering" to "engineering",
        "security" to "security",
        "testing" to "testing",
        "test" to "testing",
        "design" to "design",
        "product" to "product",
        "specialized" to "specialized",
        "unity" to "unity",
        "unreal" to "unreal",
        "godot" to "godot",
        "roblox" to "roblox",
        "xr" to "xr",
        "visionos" to "xr",
        "macos" to "xr",
        "explore" to "explore"
    )

    /** 一个部门在索引里的汇总行。 */
    data class DepartmentSummary(val name: String, val count: Int, val representatives: List<String>)

    /** 选角结果：命中一个定义，或没命中并附带可读原因。 */
    sealed interface MatchOutcome {
        data class Matched(val definition: AgentDefinition, val department: String, val score: Int) : MatchOutcome
        data class Unmatched(val reason: String) : MatchOutcome
    }

    /** 定义的部门：优先取文件名的首个连字符段，取不到再退回 name。 */
    fun departmentOf(definition: AgentDefinition): String {
        val stem = definition.filePath
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: definition.name
        return canonicalDepartment(stem) ?: GENERAL
    }

    /** 把部门名（或文件名前缀）规范化到 [DEPARTMENT_PREFIXES] 里的部门；无法识别返回 null。 */
    fun canonicalDepartment(raw: String): String? {
        val token = raw.trim().lowercase().replace(Regex("\\s+"), "-")
        if (token.isEmpty()) return null
        if (token == GENERAL) return GENERAL
        return DEPARTMENT_PREFIXES
            .firstOrNull { (prefix, _) -> token == prefix || token.startsWith("$prefix-") }
            ?.second
    }

    /** 按部门汇总（角色数降序，同数按部门名），每个部门只带最多 [MAX_REPRESENTATIVES] 个代表角色。 */
    fun departments(definitions: List<AgentDefinition>): List<DepartmentSummary> =
        definitions.groupBy { departmentOf(it) }
            .map { (name, members) ->
                val sorted = members.sortedBy { it.name.lowercase() }
                DepartmentSummary(name, sorted.size, sorted.take(MAX_REPRESENTATIVES).map { it.name })
            }
            .sortedWith(compareByDescending<DepartmentSummary> { it.count }.thenBy { it.name })

    /** 一行式部门清单（供报错提示）：`engineering(59)、security(12)…`。 */
    fun departmentHint(definitions: List<AgentDefinition>): String =
        departments(definitions).joinToString("、") { "${it.name}(${it.count})" }

    /** 渲染注入主代理提示词的部门索引（含派发用法），角色总数为 0 时返回空串。 */
    fun renderIndex(definitions: List<AgentDefinition>): String {
        val summaries = departments(definitions)
        if (summaries.isEmpty()) return ""
        val lines = summaries.joinToString("\n") { summary ->
            val reps = summary.representatives.joinToString("、")
            "- ${summary.name} / ${summary.count} / $reps"
        }
        return buildString {
            append("可用子代理 (subagents)：按部门汇总的索引，每行「部门 / 角色数 / 代表角色」；")
            append("不逐条注入全部角色，选角走「部门 + 关键词」。\n")
            append(lines)
            append("\n派发：`task(action=\"create\", department=\"engineering\", agentQuery=\"code review\", prompt=\"...\")`")
            append("——给出部门名与 2~5 个描述任务功能/领域的关键词，系统在该部门内按名称与用途描述挑最对口的角色；")
            append("确知角色名时可直接 `agent=\"Code Reviewer\"`；department 与 agentQuery 都不给则用默认通用子代理。")
        }
    }

    /**
     * 按部门与关键词选一个定义。
     * - `department` 给部门名（可省略）；`agentQuery` 是 2~5 个关键词（可省略，但只给部门时无法选角）。
     * - 命中返回 [MatchOutcome.Matched]；未知部门、无候选或关键词一个都没打中就返回 [MatchOutcome.Unmatched]，
     *   由调用方原样报错，不静默退化。
     */
    fun match(
        definitions: List<AgentDefinition>,
        department: String?,
        agentQuery: String?
    ): MatchOutcome {
        val rawDepartment = department?.trim().orEmpty()
        val rawQuery = agentQuery?.trim().orEmpty()
        if (rawDepartment.isEmpty() && rawQuery.isEmpty()) {
            return MatchOutcome.Unmatched("需要给出 department 或 agentQuery 才能选角。可用部门：${departmentHint(definitions)}")
        }

        val scoped: List<AgentDefinition>
        val departmentLabel: String?
        if (rawDepartment.isEmpty()) {
            scoped = definitions
            departmentLabel = null
        } else {
            val canonical = canonicalDepartment(rawDepartment)
                ?: return MatchOutcome.Unmatched("未知部门：$rawDepartment。可用部门：${departmentHint(definitions)}")
            if (rawQuery.isEmpty()) {
                return MatchOutcome.Unmatched("已指定部门 $canonical，还需 agentQuery 关键词（2~5 个词，描述任务的功能或领域）。")
            }
            departmentLabel = canonical
            scoped = definitions.filter { departmentOf(it) == canonical }
            if (scoped.isEmpty()) {
                return MatchOutcome.Unmatched("部门 $canonical 下没有可用子代理。可用部门：${departmentHint(definitions)}")
            }
        }

        return rank(scoped, rawQuery)
            ?: MatchOutcome.Unmatched(describeMiss(definitions, departmentLabel, scoped, rawQuery))
    }

    private fun rank(candidates: List<AgentDefinition>, query: String): MatchOutcome.Matched? {
        val keywords = tokenize(query)
        if (keywords.isEmpty()) return null
        val best = candidates
            .map { it to score(it, keywords) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AgentDefinition, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .firstOrNull()
            ?: return null
        return MatchOutcome.Matched(best.first, departmentOf(best.first), best.second)
    }

    private fun score(definition: AgentDefinition, keywords: List<String>): Int {
        val name = definition.name.lowercase()
        val haystack = "$name ${definition.description.lowercase()}"
        val tokens = SPLIT.split(haystack).filter { it.isNotBlank() }.toHashSet()
        var total = 0
        keywords.forEach { keyword ->
            if (keyword in tokens) total += TOKEN_HIT else if (haystack.contains(keyword)) total += SUBSTRING_HIT
            if (name.contains(keyword)) total += NAME_BONUS
        }
        return total
    }

    private fun tokenize(query: String): List<String> =
        SPLIT.split(query.lowercase()).map { it.trim() }.filter { it.length >= 2 }.distinct()

    private fun describeMiss(
        definitions: List<AgentDefinition>,
        department: String?,
        scoped: List<AgentDefinition>,
        query: String
    ): String = buildString {
        append("未匹配到子代理（department=").append(department ?: "-")
        append(", agentQuery=\"").append(query).append("\"）。")
        append("可用部门：").append(departmentHint(definitions)).append("。")
        val members = scoped.sortedBy { it.name.lowercase() }
            .take(MAX_MEMBERS_IN_HINT)
            .joinToString("、") { it.name }
        if (members.isNotEmpty()) append("该范围内的角色：").append(members).append("。")
        append("请换更具体的功能关键词（如 code review、api、database、ui、test）再试，或用 agent=\"确切名称\" 点名。")
    }
}
