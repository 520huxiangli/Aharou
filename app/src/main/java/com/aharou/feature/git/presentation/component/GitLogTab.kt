package com.aharou.feature.git.presentation.component

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.GitLanePalette
import com.aharou.core.theme.semanticColors
import com.aharou.feature.git.domain.model.GitFileChange
import com.aharou.feature.git.domain.model.GitGraph
import com.aharou.feature.git.domain.model.GitGraphRef
import com.aharou.feature.git.domain.model.GraphCommit
import com.aharou.feature.git.domain.model.GraphEdge
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Copy
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Tag
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.X

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import kotlinx.coroutines.launch

@Composable
internal fun LogTab(
    graph: GitGraph,
    commitFiles: Map<String, List<GitFileChange>>,
    loadingCommit: String?,
    graphLoadingMore: Boolean,
    listState: LazyListState,
    detailHash: String?,
    onOpenCommit: (String) -> Unit,
    onCloseCommit: () -> Unit,
    onFileDiff: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onCreateBranchAtCommit: (String, String) -> Unit = { _, _ -> },
    onCreateTagAtCommit: (String, String) -> Unit = { _, _ -> },
    onResetToCommit: (String, String) -> Unit = { _, _ -> }
) {
    val allCommits = graph.commits
    if (allCommits.isEmpty()) {
        EmptyState(stringResource(R.string.git_no_commits))
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredCommits = remember(allCommits, searchQuery) {
        if (searchQuery.isBlank()) allCommits
        else {
            val q = searchQuery.trim().lowercase()
            allCommits.filter {
                it.message.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.shortHash.lowercase().contains(q)
            }
        }
    }
    val commits = filteredCommits

    // 泳道调色板：按列号循环取色，分支越多颜色越丰富。
    val laneColors = rememberLaneColors(graph.maxLane + 1)
    // 每个提交到其父提交的边列表（按提交索引分组），供 Canvas 绘制连线。
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // Canvas 宽度：每个泳道一列，加左右内边距。线性历史（无分叉/合并）时收紧泳道宽度，
    // 让出更多空间给提交信息；有分叉时每泳道 16dp。节点在泳道内自动居中，无需改绘制。
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + Spacing.xs * 2
    // 每行高度，用于计算连线纵向跨度（节点居中）。
    val rowHeight = 72.dp
    // 预拉取下一页：列表滑动至接近底部（剩余 20 项）且还有更多提交时提前发起后台拉取，消除滑到底部等待停顿。
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && graph.hasMore && !graphLoadingMore && searchQuery.isBlank()) onLoadMore()
    }
    val cardColor = MaterialTheme.semanticColors.cardSurface
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = stringResource(R.string.git_search_commits_hint),
                leadingIcon = {
                    Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(FeatherIcons.X, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SectionHeader(stringResource(R.string.git_commit_count, commits.size))
        // 滚动容器占满整屏（与状态/分支页一致）：白色卡片是滚动内容的一部分——
        // 内容少时卡片收缩在顶部；滚动时整卡可滚过 tab 被渐变蒙版渐隐，
        // 滚到底卡片底部停在 tab 上方（底部 70dp 留白）。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg),
            state = listState,
            contentPadding = PaddingValues(bottom = 70.dp)
        ) {
            itemsIndexed(commits, key = { _, c -> c.hash }) { index, c ->
                val shape = when {
                    index == 0 && commits.size == 1 && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(Radius.lg)
                    index == 0 -> RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
                    index == commits.lastIndex && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg)
                    else -> RectangleShape
                }
                Surface(
                    color = cardColor,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val origIndex = allCommits.indexOfFirst { it.hash == c.hash }
                    GraphCommitRow(
                        commit = c,
                        lane = graph.lanes[c.hash] ?: 0,
                        edges = if (searchQuery.isBlank()) edgesByCommit[origIndex].orEmpty() else emptyList(),
                        activeTopLanes = if (searchQuery.isBlank()) graph.activeTopLanes[c.hash].orEmpty() else emptyList(),
                        activeBottomLanes = if (searchQuery.isBlank()) graph.activeBottomLanes[c.hash].orEmpty() else emptyList(),
                        laneColors = laneColors,
                        canvasWidth = if (searchQuery.isBlank()) canvasWidth else 0.dp,
                        laneWidth = if (searchQuery.isBlank()) laneWidth else 0.dp,
                        rowHeight = rowHeight,
                        refs = graph.refs[c.hash].orEmpty(),
                        isTopTerminal = origIndex == 0,
                        onOpen = { onOpenCommit(c.hash) }
                    )
                }
            }

            if (searchQuery.isBlank()) {
                item(key = "footer") {
                    Surface(
                        color = cardColor,
                        shape = RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            if (graph.hasMore) {
                                if (graphLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        stringResource(R.string.git_load_more_commits),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(R.string.git_no_more_commits),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 提交详情弹层：不打断列表布局，泳道保持完整。
    detailHash?.let { hash ->
        val commit = graph.commits.find { it.hash == hash }
        if (commit != null) {
            CommitDetailSheet(
                commit = commit,
                files = commitFiles[hash],
                loading = loadingCommit == hash && commitFiles[hash] == null,
                onDismiss = onCloseCommit,
                onFileDiff = onFileDiff,
                onCreateBranch = { name -> onCreateBranchAtCommit(name, commit.hash) },
                onCreateTag = { name -> onCreateTagAtCommit(name, commit.hash) },
                onReset = { mode -> onResetToCommit(commit.hash, mode) }
            )
        }
    }
}

@Composable
private fun GraphCommitRow(
    commit: GraphCommit,
    lane: Int,
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    rowHeight: Dp,
    refs: List<GitGraphRef>,
    /** 列表第一行（最新提交）：上方无更多提交，不画向上延伸的竖线。 */
    isTopTerminal: Boolean,
    onOpen: () -> Unit
) {
    val isMerge = commit.isMerge
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧拓扑图区域：Canvas 绘制节点 + 上下连线。
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                suppressTopLane = isTopTerminal,
                modifier = Modifier
                    .width(canvasWidth)
                    .fillMaxHeight()
            )
            // 右侧提交信息。上下留白只作用于内容，Canvas 撑满整行保证泳道跨行连续。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.sm)
                    .padding(end = Spacing.lg)
            ) {
                // 引用标签行（分支/标签 pill）。
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(Spacing.xs))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = FeatherIcons.ChevronRight,
                        contentDescription = stringResource(R.string.common_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = commit.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = nodeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(Radius.pill)
                            ) {
                                Text(
                                    text = commit.shortHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = nodeColor,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = commit.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = commit.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.semanticColors.subtleBorder,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = canvasWidth + Spacing.md)
        )
    }
}

/**
 * 拓扑图 Canvas：绘制当前提交的节点圆点 + 分支连线。
 *
 * 绘制顺序（后绘制覆盖先绘制，保证节点压在线上）：
 * 1. 分段竖线：结合 [activeTopLanes] 与 [activeBottomLanes]，对每个泳道按需画全长（0→height）、
 *    上半段（0→centerY）或下半段（centerY→height）竖线，避免在分叉/合并节点处竖线悬空延伸。
 * 2. 跨列连线：画从节点中心到下半段目标列的贝塞尔曲线。
 * 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
 */
@Composable
private fun GraphCanvas(
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    /** 第一行（最新提交）：不画上半段竖线，避免节点上方悬空延伸。 */
    suppressTopLane: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = Spacing.xs.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        // 所有相关泳道（包含 top 和 bottom 的并集）
        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()

        // 跨列边（出边与入边）均在下半段绘制贝塞尔曲线。
        // curveBotLanes 记录被曲线接替下半段竖线的列：仅当该列上半段无竖线（非贯穿通道）时才排除，
        // 避免孤立半段；若该列上半段已有竖线（主干穿过），则保留下半段竖线使其贯穿，曲线叠画在上方。
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        // 1. 分段竖线绘制：精细控制 0->centerY 与 centerY->height
        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx

            if (inTop && inBot) {
                // 贯穿整行；第一行不画上半段（上方无提交）。
                if (suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, centerY),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            } else if (inTop) {
                // 仅上半段（到达节点终止，或下半段转为斜曲线）；第一行不画。
                if (!suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            } else if (inBot) {
                // 仅下半段（从该节点新分出/延伸）
                drawLine(
                    color = color,
                    start = Offset(x, centerY),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }

        // 2. 跨列连线（均在下半段）：出边从节点中心→目标列底部，入边从目标列底部→节点中心
        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = Path().apply {
                if (edge.isMergeIn) {
                    // 合并入边：父支线从目标列底部弯入本节点中心
                    moveTo(toX, size.height)
                    cubicTo(
                        toX, midY,
                        fromX, midY,
                        fromX, centerY
                    )
                } else {
                    // 出边：本节点从中心向目标列底部分叉
                    moveTo(fromX, centerY)
                    cubicTo(
                        fromX, midY,
                        toX, midY,
                        toX, size.height
                    )
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = nodeColor,
                radius = nodeRadius / 2f,
                center = Offset(centerX, centerY)
            )
        } else {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

/** 提交引用标签行：当前分支高亮 primary，其余用各分支色。 */
@Composable
private fun RefPills(refs: List<GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        refs.forEach { ref ->
            val isCurrent = ref.isCurrent
            val bg = if (isCurrent) MaterialTheme.colorScheme.primary
                else if (ref.isBranch) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                else if (ref.isBranch) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Surface(
                color = bg,
                shape = RoundedCornerShape(Radius.xs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ref.isRemote) FeatherIcons.Cloud else if (ref.isBranch) FeatherIcons.GitBranch else FeatherIcons.Tag,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = ref.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 泳道调色板：按列数生成一组区分度高的颜色，循环复用。
 * 颜色取自常见 IDE 分支配色，深浅适中以适配明暗主题。
 */
@Composable
private fun rememberLaneColors(count: Int): List<Color> {
    val palette = GitLanePalette.colors
    return remember(count) {
        (0 until count).map { palette[it % palette.size] }
    }
}

/**
 * 把 [GitGraph.edges] 按来源提交索引分组。[GitRepository.computeLanes] 按提交顺序为每个提交
 * 生成 `parents.size` 条边（根提交 0 条），故 edges 是扁平有序的，按父数累积分组即可重建对应。
 * 返回 `Map<提交索引, List<GraphEdge>>`，供 [GraphCommitRow] 绘制本行连线。
 */
private fun groupEdgesByCommit(graph: GitGraph): Map<Int, List<GraphEdge>> {
    val result = mutableMapOf<Int, List<GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        // 根提交无边；非根提交有 parents.size 条边。
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) {
                list.add(graph.edges[edgeIdx++])
            }
        }
        result[commitIdx] = list
    }
    return result
}

@Composable
private fun CommitFilesSummary(count: Int, indent: Dp) {
    Text(
        text = stringResource(R.string.git_files_changed, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun EmptyCommitFilesRow(indent: Dp) {
    Text(
        text = stringResource(R.string.git_no_files_changed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm)
    )
}

@Composable
private fun LoadingFilesRow(indent: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.git_loading_files),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommitFileRow(file: GitFileChange, indent: Dp, onClick: () -> Unit = {}) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = indent, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(file.statusCode)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.semanticColors.subtleBorder,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = indent + 44.dp)
        )
    }
}

/** 提交详情底部弹层：完整信息 + 复制哈希 + 改动文件清单（点击文件进 diff）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitDetailSheet(
    commit: GraphCommit,
    files: List<GitFileChange>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFileDiff: (String, String) -> Unit,
    onCreateBranch: (String) -> Unit = {},
    onCreateTag: (String) -> Unit = {},
    onReset: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var newTagName by remember { mutableStateOf("") }
    var resetMode by remember { mutableStateOf("mixed") }

    if (showCreateBranchDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBranchDialog = false },
            title = { Text(stringResource(R.string.git_create_branch_from_commit)) },
            text = {
                AppTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = stringResource(R.string.git_branch_name),
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newBranchName.trim()
                        if (name.isNotBlank()) {
                            showCreateBranchDialog = false
                            newBranchName = ""
                            onCreateBranch(name)
                            onDismiss()
                        }
                    },
                    enabled = newBranchName.isNotBlank()
                ) { Text(stringResource(R.string.git_action_create_branch)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBranchDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showCreateTagDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTagDialog = false },
            title = { Text(stringResource(R.string.git_create_tag_at_commit)) },
            text = {
                AppTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = stringResource(R.string.git_tag_name),
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newTagName.trim()
                        if (name.isNotBlank()) {
                            showCreateTagDialog = false
                            newTagName = ""
                            onCreateTag(name)
                        }
                    },
                    enabled = newTagName.isNotBlank()
                ) { Text(stringResource(R.string.git_action_create_tag)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTagDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.git_reset_to_commit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.git_reset_confirm, commit.shortHash))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resetMode = "soft" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = resetMode == "soft",
                            onClick = { resetMode = "soft" }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.git_reset_soft), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resetMode = "mixed" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = resetMode == "mixed",
                            onClick = { resetMode = "mixed" }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.git_reset_mixed), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onReset(resetMode)
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.git_reset_to_commit), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = commit.message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (commit.body.isNotBlank()) {
                Text(
                    text = commit.body.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${commit.author} · ${commit.date}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = commit.hash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("commit-short", commit.shortHash)))
                    }
                }) {
                    Text(stringResource(R.string.git_copy_short_hash), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("commit-full", commit.hash)))
                    }
                }) {
                    Text(stringResource(R.string.git_copy_full_hash), style = MaterialTheme.typography.labelMedium)
                }
            }

            // 深度操作按钮行：基于此提交建分支 / 打标签 / 重置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                FilledTonalButton(
                    onClick = { showCreateBranchDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.GitBranch, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.git_tab_branches), style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { showCreateTagDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.Tag, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.git_tags), style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (commit.isMerge) {
                Text(
                    text = stringResource(R.string.git_merge_commit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            when {
                loading -> LoadingFilesRow(0.dp)
                files == null -> Unit
                files.isEmpty() -> EmptyCommitFilesRow(0.dp)
                else -> {
                    CommitFilesSummary(files.size, 0.dp)
                    files.forEach { file ->
                        CommitFileRow(file, indent = 0.dp, onClick = { onFileDiff(commit.hash, file.path) })
                    }
                }
            }
        }
    }
}
