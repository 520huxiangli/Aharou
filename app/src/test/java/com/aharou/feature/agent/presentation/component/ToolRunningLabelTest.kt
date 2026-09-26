package com.aharou.feature.agent.presentation.component

import com.aharou.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具名 → 运行状态文案的归类。
 *
 * 这张表决定了聊天里"正在做什么"说的是不是人话：键必须与 ToolRegistry 注册名一致（忽略大小写），
 * 未归类的（MCP 动态工具、自定义工具、工具名缺失）必须落到通用兜底，不能猜错场景。
 */
class ToolRunningLabelTest {

    @Test
    fun fileEditingTools_mapToEditingFile() {
        assertEquals(R.string.chat_status_editing_file, toolRunningLabelRes("editFile"))
        assertEquals(R.string.chat_status_editing_file, toolRunningLabelRes("writeFile"))
    }

    @Test
    fun fileReadingTools_mapToReadingFile() {
        listOf("readFile", "list", "sendFile", "viewImage").forEach { name ->
            assertEquals(name, R.string.chat_status_reading_file, toolRunningLabelRes(name))
        }
    }

    @Test
    fun networkTools_mapToSearchingWeb() {
        listOf("search", "websearch", "webFetch").forEach { name ->
            assertEquals(name, R.string.chat_status_searching_web, toolRunningLabelRes(name))
        }
    }

    @Test
    fun shellTools_mapToRunningCommand() {
        assertEquals(R.string.chat_status_running_command, toolRunningLabelRes("bash"))
        assertEquals(R.string.chat_status_running_command, toolRunningLabelRes("terminal"))
    }

    @Test
    fun miscTools_mapToTheirOwnScenario() {
        assertEquals(R.string.chat_status_generating_image, toolRunningLabelRes("generateImage"))
        assertEquals(R.string.chat_status_updating_todo, toolRunningLabelRes("todo"))
        assertEquals(R.string.chat_status_starting_subagent, toolRunningLabelRes("task"))
        assertEquals(R.string.chat_status_reading_memory, toolRunningLabelRes("memory"))
    }

    @Test
    fun unknownOrMissingTool_fallsBackToGenericLabel() {
        // MCP 动态工具、自定义工具、工具名缺失：一律「正在调用工具」，不猜具体场景
        listOf("mcp__filesystem__read", "custom_tool", "", null).forEach { name ->
            assertEquals("$name", R.string.chat_status_calling_tool, toolRunningLabelRes(name))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(toolRunningLabelRes("editfile"), toolRunningLabelRes("EditFile"))
        assertEquals(toolRunningLabelRes("bash"), toolRunningLabelRes("BASH"))
    }
}
