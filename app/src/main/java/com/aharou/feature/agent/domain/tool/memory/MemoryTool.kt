package com.aharou.feature.agent.domain.tool.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.memory.MemoryEdit
import com.aharou.feature.agent.domain.memory.MemoryEditResult
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.GhostModeStore
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val aharouMemory: com.aharou.core.memory.AharouMemoryStore,
    private val memoryTrace: MemoryTraceLog,
    private val chatSessionDao: ChatSessionDao,
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list", "search" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "管理 AI 的长期记忆（read/save/edit/delete/list；log=每日日志；fact=事实库；mindstream=心流；core=读/写核心档案）。发现新的用户偏好、项目约定或架构决策时主动记录。"

    /** edits 数组单个元素的结构，供 function-calling 的 items schema，语义与 editFile 一致。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与记忆当前正文精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：read=读取记忆正文；save=保存（创建或全量覆盖）；edit=局部编辑已有正文；delete=删除；list=列出所有记忆摘要",
            enum = listOf("read", "save", "edit", "delete", "list", "search", "log", "fact", "mindstream", "core"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "记忆的短名称（作为文件名，如 conventions）。list 操作可省略。",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "一句话摘要（save 必填，将出现在系统提示词的记忆清单中）。",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "记忆的详细正文（Markdown 格式，save 必填）。",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "edit 操作要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "作用域：project=当前项目专属；global=跨项目通用。默认为 project。",
            enum = listOf("project", "global"),
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val scope = if (scopeStr == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT

        // 隐身会话不写任何记忆（read/list/search 等只读操作照常放行）。
        val ghostSessionId = context.sessionId
        if (ghostSessionId != null && writesMemory(action, args) &&
            GhostModeStore.isGhost(chatSessionDao, ghostSessionId)
        ) {
            return ToolResult.Error("当前会话处于隐身模式，本会话的任何内容都不会写入记忆。", "GHOST_MODE")
        }

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot)
                "search" -> handleSearch(args, context.projectRoot)
                "read" -> handleRead(memoryName, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot, context.sessionId)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot, context.sessionId)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot, context.sessionId)
                "log" -> handleDailyLog(args)
                "fact" -> handleFact(args)
                "mindstream" -> handleMindstream(args)
                "core" -> handleCore(args)
                else -> ToolResult.Error("不支持的操作: $action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory 工具执行失败: ${e.message}", e)
            ToolResult.Error("记忆操作失败: ${e.message}")
        }
    }

    /** 该动作是否会写记忆（core 带 content 才写，不带 content 是读）。 */
    private fun writesMemory(action: String, args: Map<String, JsonElement>): Boolean = when (action) {
        "save", "edit", "delete", "log", "fact", "mindstream" -> true
        "core" -> !args["content"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
        else -> false
    }

    private fun handleList(projectRoot: String?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))

        // 列出全部（含事件类）：清单里见不到的条目得能在这里找到名字，否则无从 read 取回。
        // 标注 type，便于区分「跨会话仍成立的知识」与「某次事件的留档」。
        val list = memories.joinToString("\n") {
            "- ${it.name} (${it.scope.name.lowercase()}/${it.type.key}): ${it.description}"
        }
        return ToolResult.Success(JsonPrimitive("当前记忆列表：\n$list"))
    }

    /**
     * 按关键词检索记忆。
     *
     * 打分优先级：keywords（专为此抽取，噪音最低）> 名称/描述 > 正文。
     * 只返回摘要行，正文靠 read 取——检索结果不该把上下文塞满。
     */
    private fun handleSearch(args: Map<String, JsonElement>, projectRoot: String?): ToolResult {
        val query = (args["query"] ?: args["name"])?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrEmpty()) return ToolResult.Error("search 操作需要 query 参数", "MISSING_QUERY")

        val terms = query.lowercase().split(' ', ',', '，', '、', '+').filter { it.isNotBlank() }
        val scored = memoryRepository.listMemories(projectRoot).mapNotNull { m ->
            val kw = m.keywords.joinToString(" ").lowercase()
            val head = "${m.name} ${m.description}".lowercase()
            val body = m.content.lowercase()
            var score = 0
            terms.forEach { t ->
                if (t in kw) score += 3
                if (t in head) score += 2
                if (t in body) score += 1
            }
            if (score > 0) m to score else null
        }.sortedByDescending { it.second }.take(8)

        if (scored.isEmpty()) {
            return ToolResult.Success(JsonPrimitive("没有匹配「$query」的记忆。可先用 action=list 看全部名称。"))
        }
        val text = scored.joinToString("\n") { (m, _) ->
            val kws = if (m.keywords.isEmpty()) "" else " [关键词: ${m.keywords.joinToString(", ")}]"
            "- ${m.name} (${m.scope.name.lowercase()}/${m.type.key}): ${m.description}$kws"
        }
        return ToolResult.Success(
            JsonPrimitive("匹配「$query」的记忆（最多 8 条）：\n$text\n\n用 action=read + name=<名称> 取完整正文。")
        )
    }

    private fun handleRead(name: String?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("read 操作需要 name 参数", "MISSING_NAME")
        val content = memoryRepository.loadContent(name, projectRoot)
            ?: return ToolResult.Error("未找到记忆「$name」", "MEMORY_NOT_FOUND")
        return ToolResult.Success(JsonPrimitive(content))
    }

    private fun handleSave(
        args: Map<String, JsonElement>,
        name: String?,
        scope: MemoryScope,
        projectRoot: String?,
        sessionId: String?
    ): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("save 操作需要 name 参数", "MISSING_NAME")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 description 参数", "MISSING_DESCRIPTION")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 content 参数", "MISSING_CONTENT")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法保存项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        val existed = memoryRepository.loadContent(name, projectRoot) != null
        val success = memoryRepository.saveMemory(name, description, content, scope, projectRoot)
        if (!success) return ToolResult.Error("保存记忆失败，请查看日志。", "SAVE_FAILED")

        // 重名（意同）是记忆库最大的噪声来源：同一条事实被不同名字反复记下，检索时互相抢位。
        // 只提醒不阻断——写进去的内容本身没毛病，模型拿到提示后可以立刻用 edit 合并。
        val similar = memoryRepository.findSimilarMemories(name, description, projectRoot)
        val detail = buildList {
            if (existed) add("覆盖已有记忆（旧版已归档）")
            if (similar.isNotEmpty()) add("命中相近记忆：" + similar.joinToString("、") { it.name })
        }.joinToString("；").takeIf { it.isNotEmpty() }
        memoryTrace.record(
            source = MemoryTraceLog.SOURCE_TOOL,
            action = if (existed) MemoryTraceLog.ACTION_SAVE else MemoryTraceLog.ACTION_NEW,
            name = name,
            scope = scope.name.lowercase(),
            detail = detail,
            sessionId = sessionId
        )

        val hint = if (similar.isEmpty()) {
            ""
        } else {
            " 注意：库中已有相近记忆 " +
                similar.joinToString("、") { "「${it.name}」（${it.description}）" } +
                "。若这次写的是同一条事实，请改用 action=edit 就地修改，别再新建条目。"
        }
        return ToolResult.Success(
            JsonPrimitive(
                "已成功保存记忆「$name」到 ${scope.name.lowercase()} 作用域。它将在下一次会话启动时自动注入摘要。" +
                    "当前会话若需立即使用，请通过 read 操作读取。$hint"
            )
        )
    }

    private fun handleEdit(
        args: Map<String, JsonElement>,
        name: String?,
        scope: MemoryScope,
        projectRoot: String?,
        sessionId: String?
    ): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("edit 操作需要 name 参数", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("edit 操作需要 edits 参数：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法编辑项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot)) {
            is MemoryEditResult.Success -> {
                memoryTrace.record(
                    source = MemoryTraceLog.SOURCE_TOOL,
                    action = MemoryTraceLog.ACTION_EDIT,
                    name = name,
                    scope = scope.name.lowercase(),
                    detail = "局部编辑 ${edits.size} 处",
                    sessionId = sessionId
                )
                ToolResult.Success(JsonPrimitive("已成功编辑记忆「$name」的正文（${scope.name.lowercase()} 作用域）。"))
            }
            is MemoryEditResult.NotFound ->
                ToolResult.Error("未找到记忆「${result.name}」，请先通过 save 创建，或确认 name 与作用域是否正确。", "MEMORY_NOT_FOUND")
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?, sessionId: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("delete 操作需要 name 参数", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            memoryTrace.record(
                source = MemoryTraceLog.SOURCE_TOOL,
                action = MemoryTraceLog.ACTION_DELETE,
                name = name,
                scope = scope.name.lowercase(),
                sessionId = sessionId
            )
            ToolResult.Success(JsonPrimitive("已成功删除 ${scope.name.lowercase()} 作用域的记忆「$name」。"))
        } else {
            ToolResult.Error("删除失败，记忆「$name」可能不存在于该作用域。", "DELETE_FAILED")
        }
    }

    /** 追加每日日志（Aharou 记忆：按天归档的流水笔记）。 */
    private fun handleDailyLog(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("log 操作需要 content 参数", "MISSING_CONTENT")
        val file = aharouMemory.appendDailyLog(content)
        return ToolResult.Success(JsonPrimitive("已写入每日日志：${file.name}"))
    }

    /** 追加一条事实（Aharou 记忆：结构化事实库）。 */
    private fun handleFact(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("fact 操作需要 content 参数", "MISSING_CONTENT")
        aharouMemory.appendFact(content, source = "agent")
        return ToolResult.Success(JsonPrimitive("已写入事实库。"))
    }

    /** 追加心流（顺滑记录当下的想法/情绪）。 */
    private fun handleMindstream(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("mindstream 操作需要 content 参数", "MISSING_CONTENT")
        aharouMemory.appendMindstream(content)
        return ToolResult.Success(JsonPrimitive("已写入心流。"))
    }

    /** 读/写核心档案（CORE.md）：带 content = 覆盖写入；不带 = 读取。 */
    private fun handleCore(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull
        if (content.isNullOrEmpty()) {
            val current = aharouMemory.readCore().orEmpty()
            return ToolResult.Success(JsonPrimitive(if (current.isEmpty()) "（核心档案为空）" else current))
        }
        aharouMemory.writeCore(content)
        return ToolResult.Success(JsonPrimitive("已更新核心档案。"))
    }
}
