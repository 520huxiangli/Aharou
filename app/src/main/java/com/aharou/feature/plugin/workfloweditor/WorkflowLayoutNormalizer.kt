package com.aharou.feature.plugin.workfloweditor

import com.aharou.feature.plugin.workflow.ConditionNode
import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.LogicNode
import com.aharou.feature.plugin.workflow.NodePosition
import com.aharou.feature.plugin.workflow.PlaceholderNode
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.Workflow
import com.aharou.feature.plugin.workflow.WorkflowNode

/**
 * 把工作流节点的坐标换算成编辑器能放得下的尺度。
 *
 * 为什么要这一步：Operit 的模板里坐标是它编辑器的逻辑单位，12 个节点常常挤在 300×400 的范围里；
 * 而我们的节点固定 138dp 宽、84dp 高，直接把这个坐标当像素用，节点就会叠在一起看不清连线。
 * 这里按「最小间距不得小于一个节点 + 间隙」算出统一的放大倍数，等比放大，保持原有拓扑形状。
 *
 * 只放大不缩小：间距本来就够的（比如用户自己拖过的布局）原样返回 null，不动用户的数据。
 */
/**
 * 换坐标。
 *
 * [WorkflowNode] 是 sealed class 而非 data class，父类没有 `copy`；
 * 逐个分支走子类自己的 copy，避免改成可变属性后别处误改。
 */
private fun WorkflowNode.movedTo(position: NodePosition): WorkflowNode = when (this) {
    is TriggerNode -> copy(position = position)
    is ExecuteNode -> copy(position = position)
    is ConditionNode -> copy(position = position)
    is LogicNode -> copy(position = position)
    is ExtractNode -> copy(position = position)
    is PlaceholderNode -> copy(position = position)
}

internal object WorkflowLayoutNormalizer {

    /** 与编辑器里的节点尺寸保持一致（[WorkflowCanvas] 的 NodeWidth / NodeHeight）。 */
    private const val NODE_WIDTH_DP = 138f
    private const val NODE_HEIGHT_DP = 84f

    /** 节点之间至少留出的空隙，按 dp 算。 */
    private const val GAP_DP = 28f

    /** 放大倍数的上限：原始坐标退化（全挤在一个点）时不要把画布撑到几万像素。 */
    private const val MAX_SCALE = 24f

    /**
     * @return 需要替换的工作流；布局已足够宽松时返回 null，调用方保持原样。
     */
    fun normalize(workflow: Workflow): Workflow? {
        val nodes = workflow.nodes
        if (nodes.size < 2) return null

        val minGapX = minDistinctGap(nodes.map { it.position.x })
        val minGapY = minDistinctGap(nodes.map { it.position.y })

        val needX = if (minGapX != null) (NODE_WIDTH_DP + GAP_DP) / minGapX else 1f
        val needY = if (minGapY != null) (NODE_HEIGHT_DP + GAP_DP) / minGapY else 1f
        // 两个方向取同一个倍数：分别放大会让图形变形，连线角度也就跟着变了。
        val scale = maxOf(needX, needY, 1f)
        if (scale <= 1.05f) return null
        if (!scale.isFinite()) return null
        val applied = scale.coerceAtMost(MAX_SCALE)

        val rescaled = nodes.map { node ->
            // 坐标都乘同一个数，原点位置与节点间相对关系都不变。
            node.movedTo(
                NodePosition(
                    x = node.position.x * applied,
                    y = node.position.y * applied
                )
            )
        }
        return workflow.copy(nodes = rescaled)
    }

    /** 相邻不同坐标之间的最小差值；坐标全相同或只有一个值时返回 null。 */
    private fun minDistinctGap(values: List<Float>): Float? {
        val sorted = values.distinct().sorted()
        if (sorted.size < 2) return null
        var min = Float.MAX_VALUE
        for (index in 1 until sorted.size) {
            val gap = sorted[index] - sorted[index - 1]
            if (gap > 0f && gap < min) min = gap
        }
        return if (min == Float.MAX_VALUE) null else min
    }
}
