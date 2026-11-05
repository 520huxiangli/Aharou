package com.aharou.feature.agent.domain.command

import com.aharou.R
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject

/**
 * /init —— 触发一次 agent 回合，分析当前工作区代码库并生成/改进项目规则。
 *
 * 落点跟仓库既有结构走：有 `rules.d/` 就按主题拆成片段、根文件只留索引；
 * 没有拆分则写进根文件（已有 `CLAUDE.md` 时继续用它，不新建 `AGENTS.md` 抢优先级）。
 *
 * 指令正文存放在 `prompts/agent/init.md`，可被 `~/.aharou/prompts.custom/agent/init.md` 覆盖。
 */
class InitCommandHandler @Inject constructor(
    private val systemPromptProvider: SystemPromptProvider
) : ActionCommandHandler {
    override val name = "init"
    override val descriptionRes = R.string.slash_command_init_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.initProject(substituteArguments(systemPromptProvider.resolvePrompt(PROMPT_NAME), args))
    }

    companion object {
        /** 指令正文片段名，与 `prompts/` 下路径一致。 */
        const val PROMPT_NAME = "agent/init.md"
    }
}
