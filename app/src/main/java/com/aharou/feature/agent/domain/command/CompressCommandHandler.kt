package com.aharou.feature.agent.domain.command

import com.aharou.R
import javax.inject.Inject

/**
 * /compress —— 手动触发当前会话的上下文压缩，
 * 复用 ContextCompactor 的压缩逻辑。
 */
class CompressCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "compress"
    override val descriptionRes = R.string.slash_command_compress_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.compactCurrentSession()
    }
}
