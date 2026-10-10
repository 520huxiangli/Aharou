package com.aharou.feature.agent.domain.tool.knowledge

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.knowledge.KnowledgeRepository
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 在已同步的共享知识库里检索资料。
 *
 * 知识库是 App 启动时从源仓库同步到本地的一份 Markdown 副本（见 [KnowledgeRepository]），
 * 与容器内的工作区无关——所以这是「查资料」，不是「读文件」，用 [ToolCapability.READ_AGENT_CONFIG]。
 */
class KnowledgeSearchTool @Inject constructor(
    private val repository: KnowledgeRepository
) : AgentTool() {
    private companion object {
        const val TAG = "KnowledgeSearchTool"
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 20
    }

    override val name = "knowledge_search"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "在共享知识库里按关键词检索资料，返回命中的文档标题、路径与上下文片段。" +
            "回答「怎么用/是什么/有没有讲过」这类可能已沉淀成文档的问题前先用它查一遍。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "检索关键词，越具体越好（工具会同时匹配标题与正文）。",
            required = true
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "最多返回几条，默认 $DEFAULT_LIMIT，上限 $MAX_LIMIT。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: query", "MISSING_QUERY")
        }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        val hits = repository.search(query, limit)
        if (hits.isEmpty()) {
            val total = repository.documents().size
            FileLogger.d(TAG, "知识库未命中: $query（本地共 $total 篇）")
            return ToolResult.Success(
                JsonPrimitive(
                    if (total == 0) {
                        "本地还没有同步任何知识库内容（启动时会自动同步一次，稍后重试即可）。"
                    } else {
                        "知识库里没有找到与「$query」相关的内容（本地共 $total 篇）。"
                    }
                )
            )
        }

        val array = buildJsonArray {
            hits.forEach { hit ->
                add(
                    buildJsonObject {
                        put("title", hit.title)
                        put("source", hit.sourceName)
                        put("path", hit.path)
                        put("snippet", hit.snippet)
                    }
                )
            }
        }
        FileLogger.d(TAG, "知识库命中 ${hits.size} 条: $query")
        return ToolResult.Success(array)
    }
}
