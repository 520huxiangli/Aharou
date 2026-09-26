package com.aharou.feature.agent.domain.command

import com.aharou.R
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * /usage 命令：菜单元数据与执行行为。
 */
class UsageCommandHandlerTest {

    private val handler = UsageCommandHandler()

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("usage", handler.name)
        assertEquals(R.string.slash_command_usage_desc, handler.descriptionRes)
        assertFalse("默认不接受参数", handler.acceptsArgs)
    }

    @Test
    fun execute_showsUsage() {
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "")

        verify { context.showUsage() }
    }
}
