package com.aharou.feature.agent.domain.command

import com.aharou.R
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * /compress 命令：菜单元数据与执行行为。
 */
class CompressCommandHandlerTest {

    private val handler = CompressCommandHandler()

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("compress", handler.name)
        assertEquals(R.string.slash_command_compress_desc, handler.descriptionRes)
        assertFalse("默认不接受参数", handler.acceptsArgs)
    }

    @Test
    fun execute_compactsCurrentSession() {
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "")

        verify { context.compactCurrentSession() }
    }
}
