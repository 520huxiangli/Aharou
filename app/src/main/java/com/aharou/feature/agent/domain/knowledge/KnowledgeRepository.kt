package com.aharou.feature.agent.domain.knowledge

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 共享知识库的本地副本：把源仓库里的 Markdown 拉到应用私有目录，供 [KnowledgeSearchTool] 检索。
 *
 * 只做「整棵目录树 → 本地镜像」这一件事，不起向量索引：知识库是纯文本、体量小，
 * 关键词检索足够，也省掉一个模型依赖。同步是全量对账式——远端删掉的文件本地也删掉，
 * 保证本地副本与仓库一致；内容逐字节比对，没变就不重写，避免无谓的磁盘写与 mtime 抖动。
 */
@Singleton
class KnowledgeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: KnowledgeCatalog
) {
    // 与 RepoDataFetcher / SkillMarketRepository 保持一致的代理与超时配置：
    // 用户配了上游代理时，裸 client 会连不通（缺 proxyAuthenticator）。
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    fun rootDir(): File = File(context.filesDir, KNOWLEDGE_DIR)

    fun sourceDir(sourceId: String): File = File(rootDir(), sourceId)

    /** 同步全部已配置的源；任一源失败只记日志，不影响其他源。 */
    suspend fun syncAll() {
        catalog.load().sources.keys.forEach { id ->
            runCatching { sync(id) }.onFailure {
                FileLogger.w(TAG, "同步知识库源失败：$id", it)
            }
        }
    }

    /**
     * 同步单个源。返回 null 表示这次没同步成（源不存在、列不到文件树或网络不通），
     * 此时保留上一次的本地副本不动。
     */
    suspend fun sync(sourceId: String): SyncResult? = withContext(Dispatchers.IO) {
        val source = catalog.load().sources[sourceId] ?: return@withContext null
        if (source.repo.isBlank()) return@withContext null

        val coord = SkillRepoAccess.Coord(source.host, source.repo, source.branch)
        val tree = httpGetFirst(SkillRepoAccess.treeUrls(coord))
            ?.let { SkillRepoAccess.parseTree(source.host, it) }
            ?: return@withContext null

        val prefix = source.path.trim('/')
        val remoteFiles = tree
            .filter { it.endsWith(".md", ignoreCase = true) }
            .filter { prefix.isEmpty() || it.startsWith("$prefix/") }

        val dir = sourceDir(sourceId)
        val kept = HashSet<String>()
        var written = 0
        for (relative in remoteFiles) {
            val local = if (prefix.isEmpty()) relative else relative.removePrefix("$prefix/")
            val body = httpGetFirst(SkillRepoAccess.fileUrls(coord, relative)) ?: continue
            val target = File(dir, local)
            kept.add(target.absolutePath)
            val existing = if (target.isFile) {
                runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
            } else {
                null
            }
            if (existing == body) continue
            target.parentFile?.mkdirs()
            target.writeTextSafely(body, TAG)
            written++
        }

        var removed = 0
        if (dir.isDirectory) {
            dir.walkBottomUp()
                .filter { it.isFile && it.absolutePath !in kept }
                .forEach { if (it.delete()) removed++ }
        }

        FileLogger.d(TAG, "知识库 $sourceId 同步完成：写入 $written，移除 $removed，共 ${remoteFiles.size} 篇")
        SyncResult(docs = remoteFiles.size, written = written, removed = removed)
    }

    /** 本地已同步的文档清单；[sourceId] 为空时列全部源。 */
    fun documents(sourceId: String? = null): List<KnowledgeDoc> {
        val names = catalog.load().sources.mapValues { it.value.displayName(appLanguage) }
        val sources = if (sourceId != null) listOf(sourceId) else names.keys.toList()
        val out = mutableListOf<KnowledgeDoc>()
        for (id in sources) {
            val dir = sourceDir(id)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
                .forEach { file ->
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                    out.add(
                        KnowledgeDoc(
                            sourceId = id,
                            sourceName = names[id] ?: id,
                            path = file.relativeTo(dir).path,
                            title = titleOf(text, file.nameWithoutExtension),
                            size = file.length()
                        )
                    )
                }
        }
        return out.sortedWith(compareBy({ it.sourceId }, { it.path }))
    }

    /**
     * 关键词检索：逐篇统计命中次数（次数越多排越前），返回命中处的上下文片段。
     * 知识库是纯 Markdown、体量小，全扫一遍比维护索引简单且不会漂。
     */
    fun search(query: String, limit: Int = 5): List<KnowledgeHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val names = catalog.load().sources.mapValues { it.value.displayName(appLanguage) }
        val hits = mutableListOf<KnowledgeHit>()

        rootDir().listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val sourceId = dir.name
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
                .forEach { file ->
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                    val lower = text.lowercase()
                    val first = lower.indexOf(needle)
                    if (first < 0) return@forEach
                    var count = 0
                    var cursor = first
                    while (cursor >= 0) {
                        count++
                        cursor = lower.indexOf(needle, cursor + needle.length)
                    }
                    val start = (first - SNIPPET_LEAD).coerceAtLeast(0)
                    val end = (first + needle.length + SNIPPET_TAIL).coerceAtMost(text.length)
                    hits.add(
                        KnowledgeHit(
                            sourceId = sourceId,
                            sourceName = names[sourceId] ?: sourceId,
                            path = file.relativeTo(dir).path,
                            title = titleOf(text, file.nameWithoutExtension),
                            snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim(),
                            score = count
                        )
                    )
                }
        }
        return hits.sortedWith(compareByDescending<KnowledgeHit> { it.score }.thenBy { it.path }).take(limit)
    }

    /** 当前界面语言；per-app 语言下取配置里的语言，别用进程 Locale。 */
    private val appLanguage: String
        get() = context.resources.configuration.locales[0].language

    /** 标题优先取 frontmatter 的 `title:`，其次正文首个 `#` 级标题，都没有用文件名。 */
    private fun titleOf(text: String, fallback: String): String {
        val front = Regex("(?m)^title\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        if (!front.isNullOrEmpty()) return front.trim('"', '\'')
        val heading = Regex("(?m)^#\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
        if (!heading.isNullOrEmpty()) return heading
        return fallback
    }

    /** 依次尝试候选节点，返回第一个成功的结果；全失败返回 null。 */
    private suspend fun httpGetFirst(urls: List<String>): String? {
        urls.forEach { url -> httpGet(url)?.let { return it } }
        return null
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrElse { error ->
            FileLogger.d(TAG, "请求失败 $url：${error.message}")
            null
        }
    }

    /** 一次同步的结果。[docs] 是远端篇数，[written] 是实际落盘的篇数。 */
    data class SyncResult(val docs: Int, val written: Int, val removed: Int)

    private companion object {
        const val TAG = "KnowledgeRepository"
        const val USER_AGENT = "aharou-android"

        /** 与容器内的 `~/.aharou` 对应：filesDir/aharou/knowledge/<源 id>/。 */
        const val KNOWLEDGE_DIR = "aharou/knowledge"

        const val SNIPPET_LEAD = 80
        const val SNIPPET_TAIL = 160
    }
}
