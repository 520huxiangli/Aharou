package com.aharou.feature.agent.domain.mention

import com.aharou.feature.workspace.domain.WorkspaceSearchEngine
import javax.inject.Inject
import javax.inject.Singleton

/** 工作区文件枚举结果。 */
sealed interface LoadResult {
    data class Success(val files: List<String>) : LoadResult
    /** 枚举失败（工作区路径为空 / 容器未就绪且回退遍历也失败）。 */
    data object Unavailable : LoadResult
}

/**
 * `@` 引用的文件数据源：枚举复用 [WorkspaceSearchEngine.listWorkspaceFiles]（rg --files 主路 + 遍历回退），
 * 匹配是纯函数，便于单测。
 */
@Singleton
class FileMentionRepository @Inject constructor(
    private val searchEngine: WorkspaceSearchEngine
) {
    private companion object {
        /** 候选上限：菜单里够翻即可，避免把上千条路径灌进 Compose。 */
        const val DEFAULT_LIMIT = 30
    }

    /**
     * 上一次过滤用的文件清单与它的小写副本。
     *
     * `@` 菜单是「输入即出候选」，[match] 跑在调用方线程上；仓库上限 4000 条，每敲一个键都把
     * 4000 个路径各 `lowercase()` 一遍就是 4000 次新分配。控制器每次传的是同一个 List 实例，
     * 按引用就能判定要不要重算。
     */
    private var cachedFiles: List<String>? = null
    private var cachedLower: List<String> = emptyList()

    suspend fun loadFiles(projectPath: String): LoadResult {
        if (projectPath.isBlank()) return LoadResult.Unavailable
        val files = searchEngine.listWorkspaceFiles() ?: return LoadResult.Unavailable
        // 枚举成功时顺手把小写副本备好：一次扫描换掉之后每个键的全量大小写转换。
        primeLowerCache(files)
        return LoadResult.Success(files)
    }

    @Synchronized
    private fun lowerOf(files: List<String>): List<String> {
        if (cachedFiles === files) return cachedLower
        primeLowerCache(files)
        return cachedLower
    }

    @Synchronized
    private fun primeLowerCache(files: List<String>) {
        val lower = ArrayList<String>(files.size)
        for (path in files) lower.add(path.lowercase())
        cachedFiles = files
        cachedLower = lower
    }

    /**
     * 子序列模糊匹配 + 排序，取前 [limit] 条。
     * 排序档位：相对路径以查询开头 > 文件名以查询开头 > 路径含查询子串 > 仅子序列命中；
     * 同档按命中位置更靠前、路径更短优先，使同名文件与浅层文件排在前面。查询为空时原序返回。
     */
    fun match(files: List<String>, query: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return files.take(limit)
        // 拿缓存的小写串直接排序，逐键不再重复 lowercase
        val lower = lowerOf(files)
        val hits = ArrayList<Pair<String, Rank>>()
        for (i in files.indices) {
            val rank = rankOf(lower[i], q) ?: continue
            hits.add(files[i] to rank)
        }
        hits.sortWith(compareBy({ it.second.rank }, { it.second.position }, { it.first.length }, { it.first }))
        return hits.take(limit).map { it.first }
    }

    /** 命中档位 + 命中起始位置；未命中返回 null。 */
    private data class Rank(val rank: Int, val position: Int)

    /** [lower] 必须是已转小写的路径（见 [lowerOf]）。 */
    private fun rankOf(lower: String, query: String): Rank? {
        if (lower.startsWith(query)) return Rank(0, 0)
        val name = lower.substringAfterLast('/')
        if (name.startsWith(query)) return Rank(1, lower.indexOf(query).coerceAtLeast(0))
        val substringAt = lower.indexOf(query)
        if (substringAt >= 0) return Rank(2, substringAt)
        val subsequenceAt = subsequenceStart(lower, query) ?: return null
        return Rank(3, subsequenceAt)
    }

    /** 查询字符按序出现则返回首个命中的下标（用于稳定排序），否则 null。 */
    private fun subsequenceStart(path: String, query: String): Int? {
        var qi = 0
        var firstAt = -1
        for ((index, c) in path.withIndex()) {
            if (qi < query.length && c == query[qi]) {
                if (qi == 0) firstAt = index
                qi++
            }
        }
        return if (qi == query.length) firstAt else null
    }
}
