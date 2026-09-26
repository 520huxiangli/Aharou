package com.aharou.feature.agent.domain.command

import com.aharou.R
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /init 命令：菜单元数据、指令正文来源、参数替换与执行行为。
 */
class InitCommandHandlerTest {

    private val promptProvider = mockk<SystemPromptProvider>()
    private val handler = InitCommandHandler(promptProvider)

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("init", handler.name)
        assertEquals(R.string.slash_command_init_desc, handler.descriptionRes)
        assertTrue("接受参数以承载用户关注点", handler.acceptsArgs)
    }

    @Test
    fun promptName_pointsToAgentFragment() {
        assertEquals("agent/init.md", InitCommandHandler.PROMPT_NAME)
    }

    @Test
    fun execute_substitutesArguments() {
        every { promptProvider.resolvePrompt(InitCommandHandler.PROMPT_NAME) } returns "focus: \$ARGUMENTS"
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "测试")

        verify { context.initProject("focus: 测试") }
    }

    @Test
    fun execute_withoutArgs_replacesPlaceholderWithEmpty() {
        every { promptProvider.resolvePrompt(InitCommandHandler.PROMPT_NAME) } returns "focus: \$ARGUMENTS"
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "")

        verify { context.initProject("focus: ") }
    }
}
