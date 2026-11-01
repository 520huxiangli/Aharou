package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import com.aharou.feature.workspace.domain.FileAccessProvider

/**
 * 解析工作流预设（`workflows/<name>.md`）：frontmatter 的 `teams` 是团队链，正文是工作流公约。
 *
 * 与 [com.aharou.feature.agent.domain.subagent.AgentTeamParser] 同一套 frontmatter 约定。
 * 团队链每层有两种写法：
 * ```
 * teams:
 *   - 需求分析团队                # 简写：只写团队名，默认接受驳回、协调者由团队首个角色兼任
 *   - team: 架构设计团队          # 完整写法
 *     acceptsReject: true
 *     lead: 系统架构师
 * ```
 * 校验沿用团队预设的策略：单层写错就丢这一层并记日志，重复引用同一团队只保留第一次；剩不足两层
 * 时整份视为非法（返回 null）——单团队不需要工作流，多出来的那一层才是转交与驳回发生的地方。
 */
object TeamWorkflowParser {
    private const val TAG = "TeamWorkflowParser"
    private const val MAX_DESC_CHARS = 500

    fun parse(provider: FileAccessProvider, filePath: String): TeamWorkflow? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse { e ->
            FileLogger.w(TAG, "读取工作流预设失败: $filePath", e)
            return null
        }

        val (frontmatter, body) = Frontmatter.splitAndParse(text, TAG, "解析工作流预设 YAML 失败")
        val teams = parseTeams(frontmatter["teams"], filePath)
        if (teams.size < 2) {
            FileLogger.w(TAG, "工作流预设的团队链不足两层，已忽略: $filePath")
            return null
        }

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return TeamWorkflow(
            name = name,
            description = description,
            teams = teams,
            charter = body.trim(),
            maxRejectRounds = parseRejectRounds(frontmatter["maxRejectRounds"]),
            filePath = filePath
        )
    }

    /**
     * 把编辑表单写回 Markdown（frontmatter + 工作流公约），与 [parse] 成对。
     *
     * 省略 [WorkflowTeamRefForm.acceptsReject] 的默认值 true 时不写出该行，文件保持短；
     * 读回时缺省即 true，不会歧义。
     */
    fun serialize(form: TeamWorkflowForm): String = buildString {
        appendLine("---")
        appendLine("name: ${Frontmatter.quote(form.name.trim())}")
        appendLine("description: ${Frontmatter.quote(form.description.trim())}")
        appendLine("maxRejectRounds: ${normalizeRounds(form.maxRejectRounds)}")
        appendLine("teams:")
        form.teams.forEach { ref ->
            appendLine("  - team: ${Frontmatter.quote(ref.team.trim())}")
            if (!ref.acceptsReject) appendLine("    acceptsReject: false")
            val lead = ref.lead.trim()
            if (lead.isNotEmpty()) appendLine("    lead: ${Frontmatter.quote(lead)}")
        }
        appendLine("---")
        if (form.charter.isNotBlank()) appendLine(form.charter.trim())
    }

    /** 表单里的字符串转成合法轮次；解析不出来就用默认值，不因为一个数字拦住保存。 */
    fun normalizeRounds(text: String): Int =
        (text.trim().toIntOrNull() ?: TeamWorkflow.DEFAULT_MAX_REJECT_ROUNDS)
            .coerceIn(1, TeamWorkflow.MAX_REJECT_ROUNDS_CAP)

    private fun parseTeams(raw: Any?, filePath: String): List<WorkflowTeamRef> {
        val seen = HashSet<String>()
        val result = mutableListOf<WorkflowTeamRef>()
        (raw as? List<*>).orEmpty().forEachIndexed { index, item ->
            val ref = parseTeamRef(item, index, filePath) ?: return@forEachIndexed
            if (!seen.add(ref.teamName.lowercase())) {
                FileLogger.w(TAG, "工作流预设第 ${index + 1} 层重复引用团队「${ref.teamName}」，已跳过: $filePath")
                return@forEachIndexed
            }
            result += ref
        }
        return result
    }

    private fun parseTeamRef(raw: Any?, index: Int, filePath: String): WorkflowTeamRef? {
        // 简写形态是纯标量；Map 走完整写法，不能先 toString 再判断，会得到 "{team=...}"。
        if (raw !is Map<*, *>) {
            val simple = raw?.toString()?.trim()
            if (simple.isNullOrBlank()) {
                FileLogger.w(TAG, "工作流预设第 ${index + 1} 层不是团队名，已跳过: $filePath")
                return null
            }
            return WorkflowTeamRef(teamName = simple)
        }
        val teamName = (raw["team"] ?: raw["name"])?.toString()?.trim()
        if (teamName.isNullOrBlank()) {
            FileLogger.w(TAG, "工作流预设第 ${index + 1} 层缺 team 字段，已跳过: $filePath")
            return null
        }
        return WorkflowTeamRef(
            teamName = teamName,
            acceptsReject = parseBoolean(raw["acceptsReject"]),
            lead = raw["lead"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    /** YAML 里 `true`/`yes`/`1` 都会拿到，写错的一律按默认值处理，不让夸张写法卡住整份预设。 */
    private fun parseBoolean(raw: Any?): Boolean = when (raw?.toString()?.trim()?.lowercase()) {
        null, "" -> true
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> true
    }

    private fun parseRejectRounds(raw: Any?): Int {
        val text = raw?.toString()?.trim().orEmpty()
        return (text.toIntOrNull() ?: TeamWorkflow.DEFAULT_MAX_REJECT_ROUNDS)
            .coerceIn(1, TeamWorkflow.MAX_REJECT_ROUNDS_CAP)
    }
}
