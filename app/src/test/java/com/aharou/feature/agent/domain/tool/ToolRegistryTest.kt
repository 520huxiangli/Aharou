package com.aharou.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    private class FakeAgentTool(
        override val name: String,
        override val description: String = "Test tool description",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            return ToolResult.Success(JsonPrimitive("Fake tool executed"))
        }
    }

    @Before
    fun setUp() {
        registry = ToolRegistry()
    }

    @Test
    fun registerAndGetTool_success() {
        val tool = FakeAgentTool(name = "test_tool")
        registry.register("test_tool", tool)

        assertTrue(registry.hasTool("test_tool"))
        assertEquals(tool, registry.getTool("test_tool"))
        assertEquals(setOf("test_tool"), registry.getToolNames())
    }

    @Test
    fun unregisterTool_success() {
        val tool = FakeAgentTool(name = "temp_tool")
        registry.register("temp_tool", tool)
        assertTrue(registry.hasTool("temp_tool"))

        registry.unregister("temp_tool")

        assertFalse(registry.hasTool("temp_tool"))
        assertNull(registry.getTool("temp_tool"))
        assertTrue(registry.getToolNames().isEmpty())
    }

    @Test
    fun getAvailableTools_returnsAllRegisteredTools() {
        val tool1 = FakeAgentTool(name = "tool_1")
        val tool2 = FakeAgentTool(name = "tool_2")

        registry.register("tool_1", tool1)
        registry.register("tool_2", tool2)

        val available = registry.getAvailableTools()
        assertEquals(2, available.size)
        assertTrue(available.contains(tool1))
        assertTrue(available.contains(tool2))
    }
}
