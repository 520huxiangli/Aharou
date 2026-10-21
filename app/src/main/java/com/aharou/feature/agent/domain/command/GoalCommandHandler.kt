package com.aharou.feature.agent.domain.command

import com.aharou.R
import javax.inject.Inject

/**
 * /goal —— 设定、查看、暂停或清除会话级持续目标（goal）。
 *
 * 参数原样交给 [SlashCommandContext.setSessionGoal]，由它区分
 * 空参（查看当前目标）与 pause / resume / clear / off 子命令。
 */
class GoalCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "goal"
    override val descriptionRes = R.string.slash_command_goal_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.setSessionGoal(args)
    }
}
