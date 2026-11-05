package com.aharou.feature.agent.domain.command

import com.aharou.R
import javax.inject.Inject

/**
 * /btw —— 旁问：就当前对话问一句，答案单独展示、不进主线上下文，也不打断正在跑的一轮。
 *
 * 与技能命令的区别：它不注入提示词、不发起 agent 回合，只做一次不带工具的补全。
 */
class SideQuestionCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "btw"
    override val descriptionRes = R.string.slash_command_btw_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.askSideQuestion(args)
    }
}
