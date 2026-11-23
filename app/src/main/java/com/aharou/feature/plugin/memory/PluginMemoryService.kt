package com.aharou.feature.plugin.memory

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `memory.*` 宿主方法（对应 Operit 的 `Tools.Memory`，10 个方法）。
 *
 * 与 Agent 侧的 `memory` 工具完全隔离：
 * - `com.aharou.core.memory.AharouMemoryStore` 是给对话注入用的 Markdown 记忆；
 * - 这里是插件自己建的知识图谱（`filesDir/plugin-memory/graph.json`）。
 * 两套互不读写：插件不会污染对话记忆，`memory` 工具的整理/压缩也不会动图谱数据。
 *
 * 参数名与返回字段按 Operit 的形状给（snake_case 入参、`memories`/`links` 结构），
 * 少一个字段就少一类能直接搬的插件。
 */
@Singleton
class PluginMemoryService @Inject constructor(
    private val graph: PluginMemoryGraph,
    private val pluginManager: PluginManager
) {

    suspend fun query(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val query = params.text("query")
        if (query.isNullOrBlank()) throw PluginHostException("PARAM_INVALID", "query 不能为空")
        val start = params.text("start_time")?.let { parseTimeBoundary(it, isEnd = false) }
        val end = params.text("end_time")?.let { parseTimeBoundary(it, isEnd = true) }
        if (start != null && end != null && start > end) {
            throw PluginHostException("PARAM_INVALID", "时间窗无效：start_time 必须不晚于 end_time")
        }
        // Operit 的口径：`*` 且没给 limit 时返回全部；普通关键词默认只给 20 条
        val limit = params.int("limit")?.takeIf { it > 0 }
            ?: if (query.trim() == "*") null else DEFAULT_QUERY_LIMIT
        val threshold = params.text("threshold")?.let {
            it.toDoubleOrNull()?.takeIf { value -> value >= 0 }
                ?: throw PluginHostException("PARAM_INVALID", "threshold 必须是非负数")
        }
        val outcome = graph.query(
            PluginMemoryGraph.QueryRequest(
                query = query,
                folderPath = params.text("folder_path"),
                startTimeMs = start,
                endTimeMs = end,
                limit = limit,
                threshold = threshold,
                snapshotId = params.text("snapshot_id")
            )
        )
        buildJsonObject {
            put("query", query)
            put("totalCount", outcome.hits.size)
            put("memories", buildJsonArray { outcome.hits.forEach { add(memoryInfo(it.node, it.score)) } })
            put("snapshotId", outcome.snapshotId)
            put("snapshotCreated", outcome.snapshotCreated)
            put("excludedBySnapshotCount", outcome.excludedBySnapshot)
        }
    }

    suspend fun getByTitle(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val title = params.text("title") ?: throw PluginHostException("PARAM_INVALID", "title 不能为空")
        // 图谱里没有「文档节点/分块」概念：要分块就该明确报错，而不是把整篇当第一块返回
        if (params.text("chunk_index") != null || params.text("chunk_range") != null ||
            params.text("query") != null
        ) {
            throw PluginHostException(
                "CHUNK_UNSUPPORTED",
                "记忆图谱不支持按分块读取（没有文档节点）；去掉 chunk_index / chunk_range / query 取整条"
            )
        }
        val node = graph.getByTitle(title)
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "记忆不存在：$title")
        buildJsonObject {
            put("memory", memoryInfo(node, null))
            put("memories", buildJsonArray { add(memoryInfo(node, null)) })
            put("totalCount", 1)
        }
    }

    suspend fun create(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val title = params.text("title") ?: throw PluginHostException("PARAM_INVALID", "title 不能为空")
        val content = params.text("content") ?: throw PluginHostException("PARAM_INVALID", "content 不能为空")
        val node = graph.create(
            PluginMemoryGraph.CreateInput(
                title = title,
                content = content,
                contentType = params.text("content_type") ?: "text/plain",
                source = params.text("source") ?: "ai_created",
                folderPath = params.text("folder_path").orEmpty(),
                tags = params.tags("tags")
            ),
            caller = pluginId
        )
        buildJsonObject {
            put("memory", memoryInfo(node, null))
            put("message", "已创建记忆：${node.title}")
        }
    }

    suspend fun update(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val oldTitle = params.text("old_title")
            ?: throw PluginHostException("PARAM_INVALID", "old_title 不能为空（用来定位记忆）")
        val outcome = graph.update(
            oldTitle,
            PluginMemoryGraph.UpdatePatch(
                newTitle = params.text("new_title"),
                content = params.text("content"),
                contentType = params.text("content_type"),
                source = params.text("source"),
                credibility = params.double("credibility"),
                importance = params.double("importance"),
                folderPath = params.textOrBlank("folder_path"),
                tags = params.tagsOrNull("tags")
            )
        )
        buildJsonObject {
            put("memory", memoryInfo(outcome.node, null))
            put("previousTitle", outcome.previousTitle)
            put("message", "已更新记忆：${outcome.previousTitle} → ${outcome.node.title}")
        }
    }

    suspend fun deleteMemory(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val title = params.text("title") ?: throw PluginHostException("PARAM_INVALID", "title 不能为空")
        val outcome = graph.delete(title)
        buildJsonObject {
            put("deletedTitle", outcome.title)
            put("removedLinks", outcome.removedLinks)
            put("message", "已删除记忆：${outcome.title}（连带移除 ${outcome.removedLinks} 条链接）")
        }
    }

    suspend fun move(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        if (params["target_folder_path"] == null) {
            throw PluginHostException(
                "PARAM_INVALID",
                "target_folder_path 必填；要移到「未分类」就传空串"
            )
        }
        val targetFolder = params.textOrBlank("target_folder_path").orEmpty()
        val titles = params.titles("titles")
        val sourceFolder = params.textOrBlank("source_folder_path")
        if (titles.isEmpty() && sourceFolder == null) {
            throw PluginHostException("PARAM_INVALID", "至少给 titles 或 source_folder_path 之一来选中要移动的记忆")
        }
        val outcome = graph.move(targetFolder, titles, sourceFolder)
        buildJsonObject {
            put("movedCount", outcome.titles.size)
            put("titles", buildJsonArray { outcome.titles.forEach { add(JsonPrimitive(it)) } })
            put("targetFolderPath", outcome.targetFolder)
            put(
                "message",
                "已移动 ${outcome.titles.size} 条记忆到 "
                    + (outcome.targetFolder.ifEmpty { "未分类" })
            )
        }
    }

    suspend fun link(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val source = params.text("source_title")
            ?: throw PluginHostException("PARAM_INVALID", "source_title 不能为空")
        val target = params.text("target_title")
            ?: throw PluginHostException("PARAM_INVALID", "target_title 不能为空")
        val outcome = graph.link(
            sourceTitle = source,
            targetTitle = target,
            type = params.text("link_type") ?: "related",
            weight = params.double("weight") ?: 0.7,
            description = params.text("description").orEmpty(),
            caller = pluginId
        )
        buildJsonObject {
            put("link", linkInfo(outcome.link, source, target))
            put("created", outcome.created)
            put(
                "message",
                if (outcome.created) "已建立链接：$source → $target" else "链接已存在，未重复创建：$source → $target"
            )
        }
    }

    suspend fun queryLinks(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val limit = params.int("limit")?.coerceIn(1, 200) ?: 20
        val links = graph.queryLinks(
            linkId = params.text("link_id"),
            sourceTitle = params.text("source_title"),
            targetTitle = params.text("target_title"),
            type = params.text("link_type"),
            limit = limit
        )
        buildJsonObject {
            put("totalCount", links.size)
            put(
                "links",
                buildJsonArray {
                    links.forEach { add(linkInfo(it.link, it.sourceTitle, it.targetTitle)) }
                }
            )
        }
    }

    suspend fun updateLink(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val view = graph.updateLink(
            linkId = params.text("link_id"),
            sourceTitle = params.text("source_title"),
            targetTitle = params.text("target_title"),
            locatorType = params.text("link_type"),
            newType = params.text("new_link_type"),
            weight = params.double("weight"),
            description = params.textOrBlank("description")
        )
        buildJsonObject {
            put("link", linkInfo(view.link, view.sourceTitle, view.targetTitle))
            put("message", "已更新链接：${view.sourceTitle} → ${view.targetTitle}")
        }
    }

    suspend fun deleteLink(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val view = graph.deleteLink(
            linkId = params.text("link_id"),
            sourceTitle = params.text("source_title"),
            targetTitle = params.text("target_title"),
            type = params.text("link_type")
        )
        buildJsonObject {
            put("deletedLinkId", view.link.id)
            put("sourceTitle", view.sourceTitle)
            put("targetTitle", view.targetTitle)
            put("message", "已删除链接：${view.sourceTitle} → ${view.targetTitle}")
        }
    }

    // ---------- 序列化与参数 ----------

    private fun memoryInfo(node: PluginMemoryNode, score: Double?): JsonObject = buildJsonObject {
        put("id", node.id)
        put("title", node.title)
        put("content", node.content)
        put("contentType", node.contentType)
        put("source", node.source)
        put("folderPath", node.folderPath)
        put("tags", buildJsonArray { node.tags.forEach { add(JsonPrimitive(it)) } })
        put("credibility", node.credibility.toDouble())
        put("importance", node.importance.toDouble())
        // Operit 的 createdAt 是格式化文本，同时给毫秒值，插件两种用法都能写
        put("createdAt", PluginMemoryGraph.formatTime(node.createdAt))
        put("createdAtMs", node.createdAt)
        put("updatedAt", PluginMemoryGraph.formatTime(node.updatedAt))
        put("updatedAtMs", node.updatedAt)
        put("createdBy", node.createdBy)
        score?.let { put("score", it) }
    }

    private fun linkInfo(
        link: PluginMemoryLink,
        sourceTitle: String,
        targetTitle: String
    ): JsonObject = buildJsonObject {
        put("linkId", link.id)
        put("sourceTitle", sourceTitle)
        put("targetTitle", targetTitle)
        put("linkType", link.type)
        put("weight", link.weight.toDouble())
        put("description", link.description)
        put("createdAt", PluginMemoryGraph.formatTime(link.createdAt))
        put("createdAtMs", link.createdAt)
        put("updatedAtMs", link.updatedAt)
        put("createdBy", link.createdBy)
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** 与 [text] 不同：空串也是有效值（`folder_path=""` 表示移到未分类）。 */
    private fun JsonObject.textOrBlank(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

    /** tags 既可能是数组，也可能是 Operit 那种逗号串。 */
    private fun JsonObject.tags(key: String): List<String> = tagsOrNull(key).orEmpty()

    private fun JsonObject.tagsOrNull(key: String): List<String>? {
        val raw = this[key] ?: return null
        val values = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull.orEmpty().split(',')
            else -> return null
        }
        return PluginMemoryGraph.normalizeTags(values)
    }

    /** titles 是 Operit 的 batch 筛选：数组或逗号/换行/竖线分隔的串。 */
    private fun JsonObject.titles(key: String): List<String> {
        val raw = this[key] ?: return emptyList()
        val values = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull.orEmpty().split(',', '\n', '|')
            else -> emptyList()
        }
        return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 时间窗接受毫秒整数、`yyyy-MM-dd`、`yyyy-MM-dd HH:mm`、`yyyy-MM-dd HH:mm:ss`。 */
    private fun parseTimeBoundary(raw: String, isEnd: Boolean): Long {
        val text = raw.trim()
        if (text.isEmpty()) throw PluginHostException("PARAM_INVALID", "时间不能为空")
        text.toLongOrNull()?.let { return it }
        for (pattern in TIME_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { isLenient = false }
                    .parse(text)
                    ?.time
            }.getOrNull()
            if (parsed != null) {
                // 只给到日时，end 要覆盖整天，否则「查 2026-11-23」会漏掉当天下午写入的记忆
                return if (isEnd && !text.contains(':')) parsed + DAY_MS - 1 else parsed
            }
        }
        throw PluginHostException(
            "PARAM_INVALID",
            "时间格式不支持：$text（可用 yyyy-MM-dd / yyyy-MM-dd HH:mm / 毫秒时间戳）"
        )
    }

    private companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_QUERY_LIMIT = 20
        private val TIME_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
    }
}
