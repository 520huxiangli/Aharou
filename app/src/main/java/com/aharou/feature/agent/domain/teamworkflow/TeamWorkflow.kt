package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope

/**
 * 工作流里的一层团队：引用一个已有的团队预设，并声明它在流转中的行为。
 *
 * [lead] 是这一层的协调者角色：读完上游交付后给出「自己处理」还是「转交下一层」的判定。留空时由
 * 团队首个任务的角色兼任，省得为了跑一条工作流被迫多造一个角色。
 *
 * [acceptsReject] 为 true 时，这一层的专家可以就上游的某份交付发起驳回。驳回是专家对专家、
 * 交付对交付的，不整层打回：某个专家觉得上游给的接口契约不对，只退那一段，不推翻整条链。
 */
data class WorkflowTeamRef(
    val teamName: String,
    val acceptsReject: Boolean = true,
    val lead: String? = null
)

/**
 * 多团队工作流，来自 `workflows/<name>.md`：frontmatter 的 `teams` 是按流转方向排好的团队链，
 * 正文是工作流公约（会拼在每个团队的任务指令前面）。
 *
 * 只有两个以上团队才需要工作流：单团队直接 `team_run`，不必建这份文件。
 *
 * [maxRejectRounds] 限制同一对专家之间的往返轮次，到顶就升级给主代理裁决——驳回没有上限时，
 * 上下游互不服气会把 token 烧光。
 */
data class TeamWorkflow(
    val name: String,
    val description: String,
    val teams: List<WorkflowTeamRef>,
    val charter: String,
    val maxRejectRounds: Int = DEFAULT_MAX_REJECT_ROUNDS,
    val filePath: String? = null
) {
    /** 层数，等于团队链长度。 */
    val layerCount: Int get() = teams.size

    companion object {
        /** 同一对专家之间默认最多往返两轮。 */
        const val DEFAULT_MAX_REJECT_ROUNDS = 2

        /** 表单与解析器共用的上界；再大就不是打算收手了。 */
        const val MAX_REJECT_ROUNDS_CAP = 9
    }
}

/** 一个生效的工作流预设：定义本体 + 来源作用域，供设置页标注「全局/项目」。 */
data class TeamWorkflowEntry(
    val workflow: TeamWorkflow,
    val scope: AgentDefinitionScope
)

/** 编辑器里的一层团队；[lead] 留空表示由该团队首个任务的角色兼任协调者。 */
data class WorkflowTeamRefForm(
    val team: String = "",
    val acceptsReject: Boolean = true,
    val lead: String = ""
)

/**
 * 工作流的编辑表单。
 *
 * [maxRejectRounds] 保留用户输入的字符串，保存时才转数字并夹到合法区间：与团队表单里 writePaths
 * 的处理方式一致，表单层不做半吊子校验，免得输入到一半就被判非法。
 */
data class TeamWorkflowForm(
    val name: String = "",
    val description: String = "",
    val scope: AgentDefinitionScope = AgentDefinitionScope.GLOBAL,
    val charter: String = "",
    val maxRejectRounds: String = TeamWorkflow.DEFAULT_MAX_REJECT_ROUNDS.toString(),
    val teams: List<WorkflowTeamRefForm> = listOf(WorkflowTeamRefForm(), WorkflowTeamRefForm())
)

/** 保存失败的原因；界面按枚举映射成对应文案。 */
enum class TeamWorkflowSaveError {
    /** 名称为空、过长或含不能做文件名的字符。 */
    INVALID_NAME,

    /** 团队链不足两层。 */
    TOO_FEW_TEAMS,

    /** 有一层没填团队名。 */
    EMPTY_TEAM_REF,

    /** 同一个团队在工作流里出现了两次。 */
    DUPLICATE_TEAM,

    /** 引用的团队不存在或已禁用。 */
    UNKNOWN_TEAM,

    /** 同作用域已有同名工作流。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}

/** 把已保存的工作流回填成编辑表单。 */
fun TeamWorkflow.toForm(scope: AgentDefinitionScope): TeamWorkflowForm = TeamWorkflowForm(
    name = name,
    description = description,
    scope = scope,
    charter = charter,
    maxRejectRounds = maxRejectRounds.toString(),
    teams = teams.map { ref ->
        WorkflowTeamRefForm(
            team = ref.teamName,
            acceptsReject = ref.acceptsReject,
            lead = ref.lead.orEmpty()
        )
    }
)
