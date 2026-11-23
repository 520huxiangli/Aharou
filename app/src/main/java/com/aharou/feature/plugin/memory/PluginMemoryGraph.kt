package com.aharou.feature.plugin.memory

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.domain.PluginHostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件记忆图谱的落盘格式。独立于 [com.aharou.core.memory.AharouMemoryStore]：
 * 那套是「Agent 对话记忆」（Markdown + 索引 + 注入上下文），这套是插件自己建的知识图谱，
 * 两套互不读写，避免插件把对话记忆写乱、也避免对话记忆的整理动作删掉插件数据。
 */
@Serializable
internal data class PluginMemoryNode(
    val id: String,
    val title: String,
    val content: String,
    val contentType: String = "text/plain",
    val source: String = "ai_created",
    val folderPath: String = "",
    val tags: List<String> = emptyList(),
    val credibility: Float = 0.5f,
    val importance: Float = 0.5f,
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String = ""
)

@Serializable
internal data class PluginMemoryLink(
    val id: String,
    val from: String,
    val to: String,
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String = ""
)

@Serializable
internal data class PluginMemoryFile(
    val schema: Int = MEMORY_SCHEMA,
    val nodes: List<PluginMemoryNode> = emptyList(),
    val links: List<PluginMemoryLink> = emptyList()
)

internal const val MEMORY_SCHEMA = 1

/**
 * 插件记忆图谱：条目（title/content/folder/tags）+ 有向链接（from/to/relation）。
 *
 * 存储：应用私有目录下单个 JSON（`filesDir/plugin-memory/graph.json`），原子写（临时文件 +
 * fsync + ATOMIC_MOVE）。全部插件共用同一张图——Operit 的插件记忆也是全应用共享的一份，
 * 每个条目/链接记下 `createdBy` 便于追溯是哪个插件写的。
 *
 * 并发：文件读写走 [mutex] 串行化；所有方法都在 IO 线程上跑。
 */
@Singleton
class PluginMemoryGraph internal constructor(private val graphFile: File) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, MEMORY_DIR))

    private val mutex = Mutex()

    /** snapshotId → 已返回过的条目 id，用于多次 query 之间去重（Operit 的 snapshot 语义）。 */
    private val snapshots = object : LinkedHashMap<String, MutableSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?): Boolean =
            size > MAX_SNAPSHOTS
    }
    private val snapshotLock = Any()

    internal data class QueryRequest(
        val query: String,
        val folderPath: String?,
        val startTimeMs: Long?,
        val endTimeMs: Long?,
        val limit: Int?,
        val threshold: Double?,
        val snapshotId: String?
    )

    internal data class QueryHit(
        val node: PluginMemoryNode,
        val score: Double
    )

    internal data class QueryOutcome(
        val hits: List<QueryHit>,
        val snapshotId: String,
        val snapshotCreated: Boolean,
        val excludedBySnapshot: Int
    )

    internal data class CreateInput(
        val title: String,
        val content: String,
        val contentType: String,
        val source: String,
        val folderPath: String,
        val tags: List<String>
    )

    internal data class UpdatePatch(
        val newTitle: String?,
        val content: String?,
        val contentType: String?,
        val source: String?,
        val credibility: Double?,
        val importance: Double?,
        val folderPath: String?,
        val tags: List<String>?
    )

    internal data class UpdateOutcome(
        val node: PluginMemoryNode,
        val previousTitle: String
    )

    internal data class DeleteOutcome(
        val title: String,
        val removedLinks: Int
    )

    internal data class MoveOutcome(
        val titles: List<String>,
        val targetFolder: String
    )

    internal data class LinkOutcome(
        val link: PluginMemoryLink,
        val created: Boolean
    )

    internal data class LinkView(
        val link: PluginMemoryLink,
        val sourceTitle: String,
        val targetTitle: String
    )

    internal suspend fun query(request: QueryRequest): QueryOutcome = readGraph { graph ->
        val queryText = request.query.trim()
        val terms = queryText.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wildcard = queryText == "*"
        val folderFilter = request.folderPath?.let { normalizeFolder(it) }

        val scored = graph.nodes.asSequence()
            .filter { node ->
                folderFilter == null || inFolder(node.folderPath, folderFilter)
            }
            .filter { node ->
                (request.startTimeMs == null || node.createdAt >= request.startTimeMs) &&
                    (request.endTimeMs == null || node.createdAt <= request.endTimeMs)
            }
            .map { node -> QueryHit(node, if (wildcard) 1.0 else scoreOf(node, terms)) }
            // 没显式给 threshold 时只留真正命中的条目（score > 0）：Operit 的默认阈值是 0.0，
            // 直接照搬会把全库都当成结果返回给「搜某个词」的调用
            .filter { hit ->
                when {
                    wildcard -> true
                    request.threshold != null -> hit.score >= request.threshold
                    else -> hit.score > 0.0
                }
            }
            .sortedWith(compareByDescending<QueryHit> { it.score }.thenByDescending { it.node.updatedAt })
            .toList()

        val (snapshotId, created, excluded, selected) = applySnapshot(request.snapshotId, scored, request.limit)
        QueryOutcome(selected, snapshotId, created, excluded)
    }

    internal suspend fun getByTitle(title: String): PluginMemoryNode? =
        readGraph { graph -> graph.nodes.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) } }

    internal suspend fun create(input: CreateInput, caller: String): PluginMemoryNode = writeGraph { graph ->
        val title = input.title.trim()
        if (graph.nodes.any { it.title.equals(title, ignoreCase = true) }) {
            throw PluginHostException(
                "TITLE_EXISTS",
                "已经有同名记忆：$title（图谱按标题定位，同名会让 update/delete 无法确定目标；先改标题或更新已有那条）"
            )
        }
        val now = System.currentTimeMillis()
        val node = PluginMemoryNode(
            id = UUID.randomUUID().toString(),
            title = title,
            content = input.content,
            contentType = input.contentType.ifBlank { "text/plain" },
            source = input.source.ifBlank { "ai_created" },
            folderPath = normalizeFolder(input.folderPath),
            tags = normalizeTags(input.tags),
            createdAt = now,
            updatedAt = now,
            createdBy = caller
        )
        graph.nodes.add(node)
        node
    }

    internal suspend fun update(oldTitle: String, patch: UpdatePatch): UpdateOutcome = writeGraph { graph ->
        val index = graph.nodes.indexOfFirst { it.title.equals(oldTitle.trim(), ignoreCase = true) }
        if (index < 0) throw PluginHostException("MEMORY_NOT_FOUND", "记忆不存在：$oldTitle")
        val current = graph.nodes[index]
        val newTitle = patch.newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: current.title
        if (!newTitle.equals(current.title, ignoreCase = true) &&
            graph.nodes.any { it.id != current.id && it.title.equals(newTitle, ignoreCase = true) }
        ) {
            throw PluginHostException("TITLE_EXISTS", "已经有同名记忆：$newTitle")
        }
        val updated = current.copy(
            title = newTitle,
            content = patch.content ?: current.content,
            contentType = patch.contentType?.ifBlank { current.contentType } ?: current.contentType,
            source = patch.source?.ifBlank { current.source } ?: current.source,
            credibility = (patch.credibility ?: current.credibility.toDouble()).toFloat().coerceIn(0f, 1f),
            importance = (patch.importance ?: current.importance.toDouble()).toFloat().coerceIn(0f, 1f),
            folderPath = patch.folderPath?.let { normalizeFolder(it) } ?: current.folderPath,
            tags = patch.tags?.let { normalizeTags(it) } ?: current.tags,
            updatedAt = System.currentTimeMillis()
        )
        graph.nodes[index] = updated
        UpdateOutcome(updated, current.title)
    }

    internal suspend fun delete(title: String): DeleteOutcome = writeGraph { graph ->
        val node = graph.nodes.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "记忆不存在：$title")
        graph.nodes.removeAll { it.id == node.id }
        val before = graph.links.size
        graph.links.removeAll { it.from == node.id || it.to == node.id }
        DeleteOutcome(node.title, before - graph.links.size)
    }

    /**
     * 移动：`titles` 与 `sourceFolder` 都给了就取交集（Operit 的语义）；
     * 只给一个就按那个筛。目标文件夹可以为空串（=未分类）。
     */
    internal suspend fun move(targetFolder: String, titles: List<String>, sourceFolder: String?): MoveOutcome =
        writeGraph { graph ->
            val byTitle = if (titles.isEmpty()) {
                emptyList()
            } else {
                titles.map { raw ->
                    graph.nodes.firstOrNull { it.title.equals(raw.trim(), ignoreCase = true) }
                }.filterNotNull()
            }
            val byFolder = if (sourceFolder == null) {
                emptyList()
            } else {
                val folder = normalizeFolder(sourceFolder)
                // 与 Operit 一致：选一个文件夹时连同它的子文件夹一起选
                graph.nodes.filter { inFolder(it.folderPath, folder) }
            }
            val selected = when {
                titles.isNotEmpty() && sourceFolder != null -> {
                    val folderIds = byFolder.map { it.id }.toSet()
                    byTitle.filter { it.id in folderIds }
                }
                titles.isNotEmpty() -> byTitle
                else -> byFolder
            }.distinctBy { it.id }

            if (selected.isEmpty()) {
                throw PluginHostException("MEMORY_NOT_FOUND", "没有匹配到要移动的记忆")
            }
            val target = normalizeFolder(targetFolder)
            val now = System.currentTimeMillis()
            val ids = selected.map { it.id }.toSet()
            graph.nodes.replaceAll { node ->
                if (node.id in ids && node.folderPath != target) node.copy(folderPath = target, updatedAt = now)
                else node
            }
            MoveOutcome(selected.map { it.title }, target)
        }

    internal suspend fun link(
        sourceTitle: String,
        targetTitle: String,
        type: String,
        weight: Double,
        description: String,
        caller: String
    ): LinkOutcome = writeGraph { graph ->
        val source = graph.nodes.firstOrNull { it.title.equals(sourceTitle.trim(), ignoreCase = true) }
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "源记忆不存在：$sourceTitle")
        val target = graph.nodes.firstOrNull { it.title.equals(targetTitle.trim(), ignoreCase = true) }
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "目标记忆不存在：$targetTitle")
        val linkType = type.trim().ifEmpty { "related" }
        val existing = graph.links.firstOrNull {
            it.from == source.id && it.to == target.id && it.type == linkType
        }
        if (existing != null) return@writeGraph LinkOutcome(existing, false)
        val now = System.currentTimeMillis()
        val link = PluginMemoryLink(
            id = UUID.randomUUID().toString(),
            from = source.id,
            to = target.id,
            type = linkType,
            weight = weight.toFloat().coerceIn(0f, 1f),
            description = description,
            createdAt = now,
            updatedAt = now,
            createdBy = caller
        )
        graph.links.add(link)
        LinkOutcome(link, true)
    }

    /**
     * 链接查询：给了 link_id 就按 id；否则 source_title 查出边、target_title 查入边，
     * 两个都给就是「A → B」的定向查询，都不给返回全部。每条结果带源/目标标题，双向都看得见。
     */
    internal suspend fun queryLinks(
        linkId: String?,
        sourceTitle: String?,
        targetTitle: String?,
        type: String?,
        limit: Int
    ): List<LinkView> = readGraph { graph ->
        val sourceId = sourceTitle?.let { title ->
            graph.nodes.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }?.id
                ?: throw PluginHostException("MEMORY_NOT_FOUND", "源记忆不存在：$title")
        }
        val targetId = targetTitle?.let { title ->
            graph.nodes.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }?.id
                ?: throw PluginHostException("MEMORY_NOT_FOUND", "目标记忆不存在：$title")
        }
        val typeFilter = type?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = if (linkId != null) {
            graph.links.filter { it.id == linkId }
        } else {
            graph.links.asSequence()
                .filter { sourceId == null || it.from == sourceId }
                .filter { targetId == null || it.to == targetId }
                .filter { typeFilter == null || it.type == typeFilter }
                .toList()
        }
        val titles = graph.nodes.associate { it.id to it.title }
        candidates.sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, MAX_LINK_LIMIT))
            .mapNotNull { link ->
                val from = titles[link.from] ?: return@mapNotNull null
                val to = titles[link.to] ?: return@mapNotNull null
                LinkView(link, from, to)
            }
    }

    internal suspend fun updateLink(
        linkId: String?,
        sourceTitle: String?,
        targetTitle: String?,
        locatorType: String?,
        newType: String?,
        weight: Double?,
        description: String?
    ): LinkView = writeGraph { graph ->
        if (newType.isNullOrBlank() && weight == null && description == null) {
            throw PluginHostException("PARAM_INVALID", "updateLink 至少需要 new_link_type / weight / description 之一")
        }
        val link = locateLink(graph, linkId, sourceTitle, targetTitle, locatorType)
        val index = graph.links.indexOfFirst { it.id == link.id }
        val updated = link.copy(
            type = newType?.trim()?.takeIf { it.isNotEmpty() } ?: link.type,
            weight = (weight ?: link.weight.toDouble()).toFloat().coerceIn(0f, 1f),
            description = description ?: link.description,
            updatedAt = System.currentTimeMillis()
        )
        graph.links[index] = updated
        LinkView(updated, titleOf(graph, updated.from), titleOf(graph, updated.to))
    }

    internal suspend fun deleteLink(
        linkId: String?,
        sourceTitle: String?,
        targetTitle: String?,
        type: String?
    ): LinkView = writeGraph { graph ->
        val link = locateLink(graph, linkId, sourceTitle, targetTitle, type)
        graph.links.removeAll { it.id == link.id }
        LinkView(link, titleOf(graph, link.from), titleOf(graph, link.to))
    }

    private fun locateLink(
        graph: MutableGraph,
        linkId: String?,
        sourceTitle: String?,
        targetTitle: String?,
        locatorType: String?
    ): PluginMemoryLink {
        if (linkId != null) {
            return graph.links.firstOrNull { it.id == linkId }
                ?: throw PluginHostException("LINK_NOT_FOUND", "链接不存在：$linkId")
        }
        if (sourceTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
            throw PluginHostException("PARAM_INVALID", "需要 link_id，或同时给 source_title 与 target_title")
        }
        val source = graph.nodes.firstOrNull { it.title.equals(sourceTitle.trim(), ignoreCase = true) }
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "源记忆不存在：$sourceTitle")
        val target = graph.nodes.firstOrNull { it.title.equals(targetTitle.trim(), ignoreCase = true) }
            ?: throw PluginHostException("MEMORY_NOT_FOUND", "目标记忆不存在：$targetTitle")
        val candidates = graph.links.filter {
            it.from == source.id && it.to == target.id &&
                (locatorType.isNullOrBlank() || it.type == locatorType)
        }
        return when {
            candidates.isEmpty() -> throw PluginHostException("LINK_NOT_FOUND", "没有匹配的链接")
            candidates.size > 1 -> throw PluginHostException(
                "LINK_AMBIGUOUS",
                "匹配到多条链接，请给 link_id 或更具体的 link_type"
            )
            else -> candidates.first()
        }
    }

    private fun titleOf(graph: MutableGraph, id: String): String =
        graph.nodes.firstOrNull { it.id == id }?.title.orEmpty()

    // ---------- 存储 ----------

    private class MutableGraph(
        val nodes: MutableList<PluginMemoryNode>,
        val links: MutableList<PluginMemoryLink>
    )

    private suspend fun <T> readGraph(block: (MutableGraph) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(load()) }
    }

    private suspend fun <T> writeGraph(block: (MutableGraph) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val graph = load()
            val result = block(graph)
            save(graph)
            result
        }
    }

    private fun load(): MutableGraph {
        if (!graphFile.isFile) return MutableGraph(mutableListOf(), mutableListOf())
        val parsed = try {
            JSON.decodeFromString<PluginMemoryFile>(graphFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 解析失败按空图处理会让下一次写把整份图谱覆盖掉，所以这里直接报错，保留现场
            FileLogger.e(TAG, "插件记忆图谱无法解析: ${graphFile.name}", e)
            throw PluginHostException("STORAGE_CORRUPTED", "插件记忆图谱文件损坏，未做任何写入：${graphFile.name}")
        }
        if (parsed.schema != MEMORY_SCHEMA) {
            throw PluginHostException(
                "STORAGE_SCHEMA_UNSUPPORTED",
                "插件记忆图谱版本不支持：${parsed.schema}（当前 $MEMORY_SCHEMA）"
            )
        }
        val ids = parsed.nodes.map { it.id }.toHashSet()
        // 悬空链接（目标条目已不在）直接丢掉，避免 queryLinks 永远看不到的幽灵边
        val links = parsed.links.filter { it.from in ids && it.to in ids }
        return MutableGraph(parsed.nodes.toMutableList(), links.toMutableList())
    }

    private fun save(graph: MutableGraph) {
        val dir = graphFile.parentFile
            ?: throw PluginHostException("STORAGE_FAILED", "插件记忆目录不可用")
        try {
            dir.mkdirs()
            val tmp = File(dir, "${graphFile.name}.tmp")
            val payload = PluginMemoryFile(nodes = graph.nodes, links = graph.links)
            tmp.outputStream().use { out ->
                out.write(JSON.encodeToString(payload).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), graphFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), graphFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "写入插件记忆图谱失败", e)
            throw PluginHostException("STORAGE_FAILED", "无法写入插件记忆图谱")
        }
    }

    // ---------- 查询辅助 ----------

    private fun scoreOf(node: PluginMemoryNode, terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val title = node.title.lowercase(Locale.ROOT)
        val content = node.content.lowercase(Locale.ROOT)
        val tags = node.tags.joinToString(" ").lowercase(Locale.ROOT)
        val folder = node.folderPath.lowercase(Locale.ROOT)
        fun ratio(text: String) = terms.count { text.contains(it) }.toDouble() / terms.size
        return (0.55 * ratio(title) + 0.30 * ratio(content) + 0.10 * ratio(tags) + 0.05 * ratio(folder))
            .coerceIn(0.0, 1.0)
    }

    /** 返回 (snapshotId, 是否新建, 被剔除数量, 选中条目)。 */
    private fun applySnapshot(
        requestedId: String?,
        scored: List<QueryHit>,
        limit: Int?
    ): SnapshotSelection {
        synchronized(snapshotLock) {
            val id = requestedId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            val created = id !in snapshots
            val seen = snapshots.getOrPut(id) { mutableSetOf() }
            val unseen = scored.filterNot { it.node.id in seen }
            val selected = if (limit == null || limit <= 0) unseen else unseen.take(limit)
            selected.forEach { seen.add(it.node.id) }
            return SnapshotSelection(id, created, scored.size - unseen.size, selected)
        }
    }

    private data class SnapshotSelection(
        val snapshotId: String,
        val created: Boolean,
        val excluded: Int,
        val hits: List<QueryHit>
    )

    companion object {
        private const val TAG = "PluginMemoryGraph"
        private const val MEMORY_DIR = "plugin-memory"
        private const val MAX_SNAPSHOTS = 32
        private const val MAX_LINK_LIMIT = 200

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        /** 文件夹统一成 `a/b` 形式；空串表示未分类。 */
        internal fun normalizeFolder(raw: String?): String =
            raw?.trim()?.replace('\\', '/')?.split('/')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("/")
                .orEmpty()

        /** 选定文件夹是否覆盖该路径：自身或子文件夹（`work` 命中 `work/audio`）。 */
        internal fun inFolder(path: String, target: String): Boolean =
            if (target.isEmpty()) path.isEmpty()
            else path.equals(target, ignoreCase = true) || path.startsWith("$target/", ignoreCase = true)

        internal fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        internal fun formatTime(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
