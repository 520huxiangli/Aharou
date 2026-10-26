package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import com.aharou.feature.workspace.domain.FileAccessProvider

/**
 * 解析团队预设文件（`teams/<name>.md`）：YAML frontmatter 里的 `stages` 是编排，正文是团队公约。
 *
 * 与 [AgentDefinitionParser] 同一套 frontmatter 约定。阶段的两种写法：
 * ```
 * stages:
 *   - title: 需求拆解            # 单任务：agent/prompt 直接写在阶段上
 *     agent: Senior Project Manager
 *     prompt: 拆成可执行子任务
 *   - title: 并行实现            # 多任务：tasks 列表，阶段内并行
 *     tasks:
 *       - agent: Mobile App Builder
 *         prompt: 实现界面
 * ```
 * 写错的部分按「丢掉这一条并记日志」处理：单个任务缺 prompt 或缺角色只丢这个任务，整阶段没有
 * 有效任务就丢这个阶段；一个阶段都不剩时整份预设视为非法（返回 null），免得空团队跑起来什么都没干。
 */
object AgentTeamParser {
    private const val TAG = "AgentTeamParser"
    private const val MAX_DESC_CHARS = 500
    private const val DEFAULT_STAGE_TITLE = "未命名阶段"

    fun parse(provider: FileAccessProvider, filePath: String): AgentTeam? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "读取团队预设失败: $filePath", it)
            return null
        }

        val (frontmatter, body) = Frontmatter.splitAndParse(text, TAG, "解析团队预设 YAML 失败")
        val stages = parseStages(frontmatter["stages"], filePath)
        if (stages.isEmpty()) {
            FileLogger.w(TAG, "团队预设没有有效阶段，已忽略: $filePath")
            return null
        }

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return AgentTeam(
            name = name,
            description = description,
            stages = stages,
            charter = body.trim(),
            filePath = filePath
        )
    }

    /**
     * 把编辑表单写回 Markdown（frontmatter + 团队公约），与 [parse] 成对。
     *
     * 阶段统一用 `tasks:` 形式输出：解析器两种写法都支持，但写出去只有一种，回读不会歧义。
     */
    fun serialize(form: AgentTeamForm): String = buildString {
        appendLine("---")
        appendLine("name: ${Frontmatter.quote(form.name.trim())}")
        appendLine("description: ${Frontmatter.quote(form.description.trim())}")
        appendLine("stages:")
        form.stages.forEach { stage ->
            appendLine("  - title: ${Frontmatter.quote(stage.title.trim())}")
            appendLine("    tasks:")
            stage.tasks.forEach { task ->
                val agent = task.agent.trim()
                if (agent.isNotEmpty()) {
                    appendLine("      - agent: ${Frontmatter.quote(agent)}")
                } else {
                    appendLine("      - department: ${Frontmatter.quote(task.department.trim())}")
                    val query = task.agentQuery.trim()
                    if (query.isNotEmpty()) appendLine("        agentQuery: ${Frontmatter.quote(query)}")
                }
                appendLine("        prompt: ${Frontmatter.quote(task.prompt.trim())}")
                // 全角逗号也认：手机输入法默认就是它。
                val paths = task.writePaths.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                if (paths.isNotEmpty()) {
                    appendLine("        writePaths: [${paths.joinToString(", ") { Frontmatter.quote(it) }}]")
                }
            }
        }
        appendLine("---")
        if (form.charter.isNotBlank()) appendLine(form.charter.trim())
    }

    private fun parseStages(raw: Any?, filePath: String): List<TeamStage> =
        (raw as? List<*>).orEmpty().mapIndexedNotNull { index, item -> parseStage(item, index, filePath) }

    private fun parseStage(raw: Any?, index: Int, filePath: String): TeamStage? {
        val map = raw as? Map<*, *> ?: run {
            FileLogger.w(TAG, "团队预设第 ${index + 1} 阶段不是对象，已跳过: $filePath")
            return null
        }
        val title = (map["title"] ?: map["name"] ?: map["stage"])?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STAGE_TITLE
        val tasks = if (map.containsKey("tasks")) {
            parseTaskList(map["tasks"], title, filePath)
        } else {
            listOfNotNull(parseTask(map, title, filePath))
        }
        if (tasks.isEmpty()) {
            FileLogger.w(TAG, "团队预设的阶段「$title」没有有效任务，已跳过: $filePath")
            return null
        }
        return TeamStage(title = title, tasks = tasks)
    }

    private fun parseTaskList(raw: Any?, stageTitle: String, filePath: String): List<TeamTask> =
        (raw as? List<*>).orEmpty().mapNotNull { parseTask(it, stageTitle, filePath) }

    private fun parseTask(raw: Any?, stageTitle: String, filePath: String): TeamTask? {
        val map = raw as? Map<*, *> ?: return null
        val prompt = map["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            FileLogger.w(TAG, "阶段「$stageTitle」有任务缺 prompt，已跳过: $filePath")
            return null
        }
        val agent = map["agent"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val department = map["department"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = map["agentQuery"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (agent == null && department == null && agentQuery == null) {
            FileLogger.w(TAG, "阶段「$stageTitle」有任务没写 agent/department，已跳过: $filePath")
            return null
        }
        return TeamTask(
            agent = agent,
            department = department,
            agentQuery = agentQuery,
            prompt = prompt,
            writePaths = AgentDefinitionParser.stringList(map["writePaths"])
        )
    }
}
