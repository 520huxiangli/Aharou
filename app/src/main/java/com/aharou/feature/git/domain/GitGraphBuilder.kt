package com.aharou.feature.git.domain

import com.aharou.feature.git.domain.model.GitGraph
import com.aharou.feature.git.domain.model.GitGraphRef
import com.aharou.feature.git.domain.model.GraphCommit
import com.aharou.feature.git.domain.model.GraphEdge

/**
 * 提交拓扑图的纯 Kotlin 解析与泳道布局（IDE 风格）。无状态、无 Android 依赖：
 * 输入 `git log`（带 `%P` 父哈希）与 refs 映射，输出 [GitGraph] 供 UI 用 Canvas 绘制。
 */
internal object GitGraphBuilder {

    /** 解析 `git log --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b` 输出为 [GraphCommit] 列表。 */
    fun parseGraphCommits(raw: String): List<GraphCommit> =
        raw.split('\n').mapNotNull { line ->
            // 字段用 0x1f 分隔：提交信息/作者名可能含 '|'（实测 7aba60f 提交信息含 '|' 曾导致
            // split('|') 把 parents 解析成假父，提交被误判为 merge 并产生幽灵泳道）。
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 6) null
            else {
                val parents = parts[5].split(' ').filter { it.isNotBlank() }
                GraphCommit(parts[0], parts[1], parts[2], parts[3], parts[4], parents = parents, body = parts.getOrNull(6) ?: "")
            }
        }

    /**
     * 对完整提交列表重算泳道布局并组装 [GitGraph]。[refs] 为外部传入的全量 refs-by-commit 映射，
     * 过滤为只含已加载提交 hash 的条目，减少传给 UI 的数据量（全量 3000+ → 仅当前视图几十条）。
     */
    fun buildGraph(
        commits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>>,
        hasMore: Boolean
    ): GitGraph {
        if (commits.isEmpty()) return GitGraph.EMPTY
        val commitHashes = commits.mapTo(HashSet()) { it.hash }
        val filteredRefs = refs.filterKeys { it in commitHashes }
        val layout = computeLanes(commits)
        return GitGraph(
            commits,
            filteredRefs,
            layout.lanes,
            layout.edges,
            layout.activeTopLanes,
            layout.activeBottomLanes,
            layout.activeLanes,
            layout.maxLane,
            hasMore
        )
    }

    /**
     * 纯 Kotlin 泳道分配算法（IDE 风格拓扑布局）。
     *
     * 维护「活跃泳道」数组 `active: Array<String?>`，每个槽位记录当前占据该列的提交哈希。
     * 按提交从新到旧顺序处理（与 git log 输出一致）：
     * - 找当前提交是否已占据某泳道（其哈希已在 active 中，即被某子提交的父引用占位）→ 复用该泳道为当前列。
     * - 未占据则取最左侧空闲槽位为新泳道（该提交是某分支的最新提交，未被任何子提交引用）。
     * - 记录当前提交的 lane。
     * - 快照此刻 [active] 中所有非空槽位的列号为该提交的 [GitGraph.activeLanes]：这些泳道
     *   从上一行延续下来并穿过本行，UI 需为它们画贯穿竖线，避免分支支线在中间行断裂。
     * - 处理父提交：第一个父若未占位则复用当前提交的泳道（主线延续），若已在其他活跃泳道则
     *   生成跨列出边并释放当前泳道（主线跳列）；其余父（合并的第二个及以后）各自分配泳道——
     *   若该父已在某活跃泳道则复用，否则取最左空闲槽位占位。
     * - 当前提交无父（根提交）则释放其泳道。
     * - 同时为每个父生成一条 [GraphEdge]：第一父跨列时为出边（fromLane=当前列, toLane=父列,
     *   lane=当前列）；第一父同列时为竖直边。其余父为合并入边（fromLane=当前列, toLane=父列,
     *   lane=父列），跨列即合并折线。
     *
     * 父提交可能不在本次 limit 范围内（哈希在 active 中查不到对应提交记录），此时仍保留泳道占位
     * 以维持连线连续性，直至其被后续提交释放。
     */
    private fun computeLanes(commits: List<GraphCommit>): GraphLayout {
        val commitMap = commits.associateBy { it.hash }
        val lanes = mutableMapOf<String, Int>()
        val edges = mutableListOf<GraphEdge>()
        val activeTopLanes = mutableMapOf<String, List<Int>>()
        val activeBottomLanes = mutableMapOf<String, List<Int>>()
        val activeLanes = mutableMapOf<String, List<Int>>()
        // 活跃泳道：槽位索引即列号，值为占据该列的提交哈希（或 null=空闲）。
        val active = mutableListOf<String?>()
        var maxLane = 0

        for (commit in commits) {
            // 当前提交是否已被某子提交的父引用占位。
            var lane = active.indexOf(commit.hash)
            if (lane < 0) {
                // 未占位：取最左空闲槽位。
                lane = active.indexOf(null)
                if (lane < 0) {
                    lane = active.size
                    active.add(commit.hash)
                } else {
                    active[lane] = commit.hash
                }
            }
            if (lane > maxLane) maxLane = lane
            lanes[commit.hash] = lane

            // 快照上半段（0 -> centerY）活跃泳道：此时 active 反应进入本节点前上方的分支通道。
            val topSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeTopLanes[commit.hash] = topSnapshot

            if (commit.parents.isEmpty()) {
                // 根提交：释放当前泳道后快照下半段。
                active[lane] = null
                val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
                activeBottomLanes[commit.hash] = botSnapshot
                activeLanes[commit.hash] = botSnapshot
                continue
            }

            // 第一父复用当前泳道（主线延续）。
            val parents = commit.parents
            val firstParent = parents[0]
            val firstParentLane = active.indexOf(firstParent)
            if (firstParentLane >= 0 && firstParentLane != lane) {
                edges.add(GraphEdge(lane, firstParentLane, lane, isMergeIn = false))
                active[lane] = null
            } else {
                edges.add(GraphEdge(lane, lane, lane, isMergeIn = false))
                active[lane] = firstParent
            }

            // 其余父（合并的第二个及以后）：优先复用活跃列表中后续能继承该父的泳道，否则分配新泳道。
            for (i in 1 until parents.size) {
                val p = parents[i]
                val existing = active.indexOf(p)
                val pLane = if (existing >= 0) {
                    existing
                } else {
                    // 查找 active 中是否有占位提交 X（非当前列），X 的父引用直接包含 p。
                    // 若有，说明 p 是 X 的父，会在 X 处理完后自然继承该列，入边直接指向该列而不抢占 active。
                    val reuse = active.indexOfFirst { aHash ->
                        aHash != null && active.indexOf(aHash) != lane && commitMap[aHash]?.parents?.contains(p) == true
                    }
                    if (reuse >= 0) {
                        reuse
                    } else {
                        val free = active.indexOf(null)
                        if (free < 0) {
                            active.add(p)
                            active.size - 1
                        } else {
                            active[free] = p
                            free
                        }
                    }
                }
                if (pLane > maxLane) maxLane = pLane
                edges.add(GraphEdge(lane, pLane, pLane, isMergeIn = true))
            }

            // 快照下半段（centerY -> height）活跃泳道：在所有父分配完毕后反应离开本节点向下的分支通道。
            val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeBottomLanes[commit.hash] = botSnapshot
            activeLanes[commit.hash] = botSnapshot
        }
        return GraphLayout(lanes, edges, activeTopLanes, activeBottomLanes, activeLanes, maxLane)
    }

    /** [computeLanes] 的输出：泳道映射 + 边列表 + 每行活跃泳道快照 + 最大列号。 */
    private class GraphLayout(
        val lanes: Map<String, Int>,
        val edges: List<GraphEdge>,
        val activeTopLanes: Map<String, List<Int>>,
        val activeBottomLanes: Map<String, List<Int>>,
        val activeLanes: Map<String, List<Int>>,
        val maxLane: Int
    )
}
