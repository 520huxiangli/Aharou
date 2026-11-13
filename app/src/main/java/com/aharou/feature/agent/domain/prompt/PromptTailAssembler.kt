package com.aharou.feature.agent.domain.prompt

/**
 * 片段里可用的运行期变量。集中在这里是为了让 [PromptTailAssembler] 与
 * `SystemPromptProvider` 共用同一份字面量——两边各写一份迟早会对不上。
 */
internal object PromptVars {
    const val SKILLS_VAR = "{{AICODE_SKILLS}}"
    const val MEMORY_VAR = "{{AICODE_MEMORY}}"
    const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
    const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
    const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
    const val DATE_VAR = "{{AICODE_DATE}}"
    const val RULES_VAR = "{{AICODE_RULES}}"
}

/**
 * 系统提示词「动态尾部」各段落的内容快照。
 *
 * 全部给默认值，调用方只填自己算出来的那几段；null / 空白段落会被整段跳过。
 */
internal data class PromptTail(
    /** 人格（SOUL.md），外置文件。 */
    val soul: String? = null,
    /** 核心档案 / 记忆库正文（外置文件）。 */
    val archive: String? = null,
    /** 可用技能清单。 */
    val skills: String? = null,
    /** 可派发的子代理索引。 */
    val subAgents: String? = null,
    /** 团队预设清单。 */
    val teams: String? = null,
    /** 记忆纪律规则正文（清单本身走当轮 user 消息前缀，不进 system）。 */
    val memories: String? = null,
    /** 项目规则（AGENTS.md / CLAUDE.md / rules.md + rules.d）。 */
    val projectRules: String? = null,
    /** 当前工作区信息。 */
    val workspace: String? = null,
    /** 按需规则清单（rules 仓库，供 loadRule 取正文）。 */
    val rules: String? = null,
    /** 工作区类型。 */
    val workspaceType: String? = null,
    /** 当前工作在远程 SSH 容器时的环境说明（本地模式下为 null）。 */
    val remoteMode: String? = null,
    /** 项目上下文索引（仅代码仓库工作区）。 */
    val projectIndex: String? = null,
    /** 模型身份（切模型会变）。 */
    val modelIdentity: String? = null,
    /** 共享信箱（有新消息才出现）。 */
    val inbox: String? = null,
    /** 当前时间。 */
    val time: String? = null,
    /** 工作区变更提醒（平时不出现）。 */
    val workspaceChange: String? = null
)

/**
 * 把 [PromptTail] 按**固定顺序**追加在静态前缀之后。
 *
 * 存在的理由有三条：
 * 1. 内置路径（`build`）与 `.no-builtin` 路径（`buildCustomOnly`）共用同一段拼装逻辑，
 *    否则两条路线的段落顺序迟早漂移——custom-only 之前就是漏了 9 个段落。
 * 2. 顺序刻意固定为「静态 → 慢变 → 快变」，让前缀尽量落进可复用的 KV Cache。
 * 3. `SystemPromptProvider` 有 1000 行硬上限（`scripts/check_architecture.py`），这段逻辑留在这里更合适。
 */
internal object PromptTailAssembler {

    /**
     * @param rawText 静态片段的**原文**（还没做 `{{AICODE_*}}` 替换）。
     *   某个占位符已经出现在里面，说明调用方已就近取回该段，此处不再重复追加。
     */
    fun render(rawText: String, tail: PromptTail): String = buildString {
        fun add(text: String?) {
            if (!text.isNullOrBlank()) {
                append("\n\n")
                append(text)
            }
        }

        // 人格与核心档案是用户外置文件，不属于「内置提示词片段」，也不受占位符控制。
        add(tail.soul)
        add(tail.archive)
        if (PromptVars.SKILLS_VAR !in rawText) add(tail.skills)
        if (PromptVars.SUBAGENTS_VAR !in rawText) add(tail.subAgents)
        add(tail.teams)
        if (PromptVars.MEMORY_VAR !in rawText) add(tail.memories)
        if (PromptVars.PROJECT_RULES_VAR !in rawText) add(tail.projectRules)
        if (PromptVars.WORKSPACE_VAR !in rawText) add(tail.workspace)
        if (PromptVars.RULES_VAR !in rawText) add(tail.rules)
        // 远程模式说明压在 workspaceType 之前：先讲清「命令落在服务器上」，再讲工程规约，与阅读顺序一致。
        add(tail.remoteMode)
        add(tail.workspaceType)
        add(tail.projectIndex)
        // 模型身份在会话内稳定，但切模型会变，压在变化区靠后。
        add(tail.modelIdentity)
        add(tail.inbox)
        if (PromptVars.DATE_VAR !in rawText) add(tail.time)
        add(tail.workspaceChange)
    }
}
