package com.aharou.feature.agent.domain.command

import com.aharou.R
import javax.inject.Inject

/**
 * /usage —— 查看今日与累计的调用次数、token 用量与预估费用（全局统计，跨会话）。
 * 结果以 Markdown 表格作为 AI 气泡输出。
 */
class UsageCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "usage"
    override val descriptionRes = R.string.slash_command_usage_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showUsage()
    }
}
