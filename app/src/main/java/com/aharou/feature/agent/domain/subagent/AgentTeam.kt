package com.aharou.feature.agent.domain.subagent

/**
 * 团队预设里的一个任务：交给某个子代理去做。
 *
 * 选角与 `task(create)` 一致，支持两种写法：[agent] 精确点名，或 [department] + [agentQuery] 交给
 * [SubAgentProfileMatcher] 选角；两者都没有的任务在解析阶段就被丢弃。
 */
data class TeamTask(
    val agent: String? = null,
    val department: String? = null,
    val agentQuery: String? = null,
    val prompt: String,
    val writePaths: List<String> = emptyList()
)

/** 团队预设的一个阶段：阶段内任务并行，阶段之间串行（上一阶段全部完成后才开始下一阶段）。 */
data class TeamStage(
    val title: String,
    val tasks: List<TeamTask>
)

/**
 * 团队预设，来自 `teams/<name>.md`：frontmatter 的 `stages` 是编排，正文是团队公约。
 *
 * [charter] 会拼在每个任务指令前面，用来放全队共用的约束（项目规则、交付标准、提问方式），
 * 避免在十几条任务里重复写同一段话。
 */
data class AgentTeam(
    val name: String,
    val description: String,
    val stages: List<TeamStage>,
    val charter: String,
    val filePath: String? = null
) {
    val taskCount: Int get() = stages.sumOf { it.tasks.size }
}

/** 一个生效的团队预设：定义本体 + 来源作用域，供设置页标注「全局/项目」。 */
data class AgentTeamEntry(
    val team: AgentTeam,
    val scope: AgentDefinitionScope
)

/** 编辑器里的任务表单；[writePaths] 保留用户输入的逗号分隔形式，保存时才切分。 */
data class AgentTeamTaskForm(
    val agent: String = "",
    val department: String = "",
    val agentQuery: String = "",
    val prompt: String = "",
    val writePaths: String = ""
)

/** 编辑器里的阶段表单：阶段内任务并行，阶段之间串行。 */
data class AgentTeamStageForm(
    val title: String = "",
    val tasks: List<AgentTeamTaskForm> = listOf(AgentTeamTaskForm())
)

/** 团队预设的编辑表单；[charter] 是正文里的团队公约。 */
data class AgentTeamForm(
    val name: String = "",
    val description: String = "",
    val scope: AgentDefinitionScope = AgentDefinitionScope.GLOBAL,
    val charter: String = "",
    val stages: List<AgentTeamStageForm> = listOf(AgentTeamStageForm())
)

/** 保存失败的原因；界面按枚举映射成对应文案。 */
enum class AgentTeamSaveError {
    /** 名称为空、过长或含不能做文件名的字符。 */
    INVALID_NAME,

    /** 一个有效阶段都没有。 */
    EMPTY_STAGES,

    /** 有任务缺任务说明，或者没写角色名也没写部门/关键词。 */
    INCOMPLETE_TASK,

    /** 同作用域已有同名团队。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}

/** 把已保存的预设回填成编辑表单。 */
fun AgentTeam.toForm(scope: AgentDefinitionScope): AgentTeamForm = AgentTeamForm(
    name = name,
    description = description,
    scope = scope,
    charter = charter,
    stages = stages.map { stage ->
        AgentTeamStageForm(
            title = stage.title,
            tasks = stage.tasks.map { task ->
                AgentTeamTaskForm(
                    agent = task.agent.orEmpty(),
                    department = task.department.orEmpty(),
                    agentQuery = task.agentQuery.orEmpty(),
                    prompt = task.prompt,
                    writePaths = task.writePaths.joinToString(", ")
                )
            }
        )
    }
)
