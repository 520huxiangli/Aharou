package com.aharou.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentDefinitionTest {

    private val allTools = listOf(
        "readFile", "writeFile", "editFile", "Bash", "terminal", "search",
        "task", "mcp__ctx7__query", "mcp__ctx7__resolve", "mcp__mt__open"
    )

    private fun def(
        allowed: List<String> = emptyList(),
        disallowed: List<String> = emptyList()
    ) = AgentDefinition(
        name = "a",
        description = "",
        allowedTools = allowed,
        disallowedTools = disallowed,
        prompt = "p"
    )

    /** 省略两个名单即继承全量工具，但 task 永远剔除（子代理不能嵌套派发）。 */
    @Test
    fun filter_emptyListsInheritAllExceptTask() {
        assertEquals(allTools - "task", def().filterToolNames(allTools))
    }

    @Test
    fun filter_allowlistKeepsOnlyListed() {
        assertEquals(
            listOf("readFile", "search"),
            def(allowed = listOf("readFile", "search")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_denylistRemovesListed() {
        val result = def(disallowed = listOf("Bash", "terminal")).filterToolNames(allTools)

        assertEquals(allTools - "task" - "Bash" - "terminal", result)
    }

    /** 黑名单先生效，两名单都命中的工具最终被移除。 */
    @Test
    fun filter_denyWinsOverAllow() {
        val result = def(
            allowed = listOf("readFile", "Bash"),
            disallowed = listOf("Bash")
        ).filterToolNames(allTools)

        assertEquals(listOf("readFile"), result)
    }

    @Test
    fun filter_wildcardMatchesMcpServerPrefix() {
        val result = def(disallowed = listOf("mcp__ctx7__*")).filterToolNames(allTools)

        assertEquals(
            listOf("readFile", "writeFile", "editFile", "Bash", "terminal", "search", "mcp__mt__open"),
            result
        )
    }

    @Test
    fun filter_allowlistCannotReintroduceTask() {
        assertEquals(
            listOf("readFile"),
            def(allowed = listOf("readFile", "task")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_toolNameMatchIsCaseInsensitive() {
        assertEquals(listOf("Bash"), def(allowed = listOf("bash")).filterToolNames(allTools))
    }

    /** 同名定义项目级覆盖全局，结果按名称排序。 */
    @Test
    fun mergeAll_projectOverridesGlobal() {
        val global = listOf(
            AgentDefinition(name = "researcher", description = "global", prompt = "g"),
            AgentDefinition(name = "coder", description = "global", prompt = "g")
        )
        val project = listOf(
            AgentDefinition(name = "researcher", description = "project", prompt = "p")
        )

        val merged = AgentDefinitionRepository.mergeAll(global, project)

        assertEquals(listOf("coder", "researcher"), merged.map { it.definition.name })
        val researcher = merged.first { it.definition.name == "researcher" }
        assertEquals(AgentDefinitionScope.PROJECT, researcher.scope)
        assertEquals("project", researcher.definition.description)
        assertEquals(
            AgentDefinitionScope.GLOBAL,
            merged.first { it.definition.name == "coder" }.scope
        )
    }
}
