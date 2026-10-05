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

    /** 角色数不超过该值的部门，索引里几乎把角色名全列出来，便于主代理直接点名。 */
    private const val SMALL_DEPARTMENT_SIZE = 15
    /** 小部门在索引里最多展示几个代表角色。 */
    private const val REPRESENTATIVES_SMALL = 5
    /** 大部门在索引里最多展示几个代表角色；小部门多列、大部门少列，保证索引体量可控。 */
    private const val REPRESENTATIVES_LARGE = 3

    /** 匹配落空时，最多列出多少个同范围角色名供主代理改选。 */
    private const val MAX_MEMBERS_IN_HINT = 12

    private const val TOKEN_HIT = 3
    private const val SUBSTRING_HIT = 1
    private const val NAME_BONUS = 2

    /** 按字母/数字切词，保留连续的 CJK 串（中文关键词走子串命中）。 */
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * 中英同义词组：同一组里的词视为等价，查询命中组里任意一个词就把整组词补进关键词。
     *
     * 存在的理由：内置角色里绝大多数 description 是纯英文，而 [score] 是字面匹配，
     * 中文关键词（「代码审查」这类）在英文描述里没有任何命中可能。
     * 英文成员只写**会出现在角色名/描述里的词**（`code review` 这种带空格的词组永远切不出单个 token，不要放）。
     */
    private val SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("代码审查", "代码评审", "审查", "评审", "review", "reviewer"),
        setOf("重构", "refactor", "refactoring"),
        setOf("测试", "用例", "自动化测试", "test", "testing", "qa"),
        setOf("性能", "优化", "调优", "performance", "optimization", "benchmark"),
        setOf("安全", "漏洞", "渗透", "security", "vulnerability", "pentest", "threat"),
        setOf("数据库", "database", "sql", "schema", "postgres", "mysql"),
        setOf("迁移", "升级", "migration", "migrate"),
        setOf("文档", "说明", "docs", "documentation", "writer", "readme"),
        setOf("部署", "发布", "上线", "deploy", "deployment", "release", "pipeline"),
        setOf("界面", "交互", "ui", "ux", "layout"),
        setOf("用户体验", "体验", "可用性", "usability"),
        setOf("架构", "架构设计", "architect", "architecture"),
        setOf("后端", "服务端", "backend", "server", "service"),
        setOf("前端", "网页", "frontend", "web"),
        setOf("移动端", "手机", "客户端", "mobile", "android", "ios"),
        setOf("安卓", "android", "kotlin"),
        setOf("调试", "排错", "排障", "debug", "debugging", "troubleshoot", "diagnose"),
        setOf("接口", "api", "endpoint", "rest", "graphql"),
        setOf("游戏", "玩法", "game", "gameplay"),
        setOf("关卡", "关卡设计", "level"),
        setOf("数值", "经济", "平衡", "economy", "balance", "monetization"),
        setOf("剧情", "叙事", "narrative", "story"),
        setOf("无障碍", "accessibility", "a11y", "wcag"),
        setOf("合规", "隐私", "compliance", "privacy", "gdpr"),
        setOf("监控", "可观测", "告警", "monitoring", "observability", "metrics", "alerting"),
        setOf("爬虫", "抓取", "scraper", "crawler", "scraping"),
        setOf("依赖", "dependency", "dependencies", "upgrade"),
        setOf("数据", "数据集", "分析", "data", "dataset", "analytics"),
        setOf("需求", "产品", "prd", "product", "requirement"),
        setOf("竞品", "市场", "调研", "competitor", "market", "research"),
        setOf("本地化", "国际化", "翻译", "localization", "l10n", "i18n", "translation"),
        setOf("认证", "身份", "登录", "identity", "auth", "authentication"),
        setOf("权限", "授权", "permission", "authorization", "rbac"),
        setOf("提示词", "prompt", "prompting")
    )

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

    /** 按部门汇总（角色数降序，同数按部门名），每个部门只带最多 [REPRESENTATIVES_SMALL]/[REPRESENTATIVES_LARGE] 个代表角色。 */
    fun departments(definitions: List<AgentDefinition>): List<DepartmentSummary> =
        definitions.groupBy { departmentOf(it) }
            .map { (name, members) ->
                val sorted = members.sortedBy { it.name.lowercase() }
                val limit = if (sorted.size <= SMALL_DEPARTMENT_SIZE) REPRESENTATIVES_SMALL else REPRESENTATIVES_LARGE
                DepartmentSummary(name, sorted.size, sorted.take(limit).map { it.name })
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
            append("确知角色名时可直接 `agent=\"Code Reviewer\"`；不确定有哪些角色名时，先用 `task(action=\"roles\", department=\"…\", agentQuery=\"…\")` 把候选与各自的工具集列出来再点名；")
            append("department 与 agentQuery 都不给则用默认通用子代理。")
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
        val keywords = expand(tokenize(query))
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

    /** CJK 词做「包含」判断：中文常连写（「数据库迁移」要同时命中「数据库」与「迁移」两组）。 */
    private fun String.hasCjk(): Boolean = any { it.code in 0x4E00..0x9FFF }

    /** 按 [SYNONYM_GROUPS] 扩展关键词：命中组里任意一个词（英文需完全相等，中文可包含）就补上整组。 */
    private fun expand(keywords: List<String>): List<String> {
        val expanded = LinkedHashSet(keywords)
        keywords.forEach { keyword ->
            SYNONYM_GROUPS.forEach { group ->
                val hit = group.any { member ->
                    member == keyword || (member.hasCjk() && keyword.contains(member))
                }
                if (hit) expanded.addAll(group)
            }
        }
        return expanded.toList()
    }

    /**
     * 按部门/关键词列出候选角色，供 `task(action="roles")` 按需拉取——
     * 索引里每部门只展示 2 个代表角色，主代理想点名时得先这样把名字查出来。
     * [query] 为空时按名字排序返回全部（分数 0）；给了查询词则只留有命中的并按分数降序。
     */
    fun candidates(
        definitions: List<AgentDefinition>,
        department: String?,
        query: String?,
        limit: Int
    ): List<Pair<AgentDefinition, Int>> {
        val canonical = department?.trim()?.takeIf { it.isNotEmpty() }?.let { canonicalDepartment(it) }
        val scoped = if (canonical == null) definitions else definitions.filter { departmentOf(it) == canonical }
        val keywords = tokenize(query.orEmpty())
        val ranked = if (keywords.isEmpty()) {
            scoped.sortedBy { it.name.lowercase() }.map { it to 0 }
        } else {
            scoped.map { it to score(it, expand(keywords)) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<AgentDefinition, Int>> { it.second }.thenBy { it.first.name.lowercase() })
        }
        return ranked.take(limit)
    }

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
        append("请换更具体的功能关键词（如 code review、api、database、ui、test，中文词现在也能匹配）再试，")
        append("或用 agent=\"确切名称\" 点名——不确定名字时先 task(action=\"roles\", department=\"…\", agentQuery=\"…\") 列出候选。")
    }
}
