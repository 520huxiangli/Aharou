package com.aharou.feature.plugin.runtime

import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把宿主真实存在的工具来源（内置、插件、技能、MCP）如实报给插件。
 *
 * 只报「确实注册过」的，不按名字猜类别：插件拿这份清单做界面展示与去重，
 * 猜出来的分类会让它对不上自己的调用。
 */
@Singleton
class PluginCatalogService @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val pluginManager: PluginManager,
    private val mcpConfigRepository: McpConfigRepository
) {

    suspend fun readToolSources(): JsonElement {
        pluginManager.ensureLoaded()
        return buildJsonObject {
            put("builtinTools", sourceList(toolRegistry.getAvailableTools().map {
                Source(it.name, it.name, it.description)
            }))
            put("packages", sourceList(pluginManager.allTools().map {
                Source(
                    name = it.localId,
                    displayName = it.title.resolve("").ifBlank { it.localId },
                    description = it.description.resolve("")
                )
            }))
            put("skills", sourceList(pluginManager.allSkills().map {
                Source(it.name, it.name, it.description)
            }))
            put("mcpServers", sourceList(
                mcpConfigRepository.getEffectiveServers().map {
                    Source(it.name, it.name, it.command ?: it.url.orEmpty())
                }
            ))
        }
    }

    private data class Source(val name: String, val displayName: String, val description: String)

    private fun sourceList(items: List<Source>): JsonElement = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("name", item.name)
                put("displayName", item.displayName)
                put("description", item.description.take(240))
            })
        }
    }
}
