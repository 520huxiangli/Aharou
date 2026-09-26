package com.aharou.feature.agent.domain.command

import com.aharou.R
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject

/**
 * /init —— 触发一次 agent 回合，分析当前工作区代码库并生成/改进项目规则文件
 * （`AGENTS.md`，仅有 `CLAUDE.md` 时改进它）。
 *
 * 指令正文存放在 `prompts/agent/init.md`，可被 `~/.aicode/prompts.custom/agent/init.md` 覆盖。
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
