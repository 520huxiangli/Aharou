package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.presentation.AgentUIState
import com.aharou.feature.agent.presentation.WorkflowStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus

/** 每层缩进宽度，同时是子分支肘线的长度。 */
private val ConnectorIndent = Spacing.xl

/** 连接线粗细（发丝线）。 */
private val ConnectorStroke = 1.dp

/** 肘线距分支顶部的距离：对齐卡片标题首行中心（卡片上内边距 + bodyLarge 半行高）。 */
private val ConnectorStubOffset = Spacing.xl

/** 状态圆点直径。 */
private val StatusDotSize = Spacing.md

/** 状态徽标底色的透明度，避免纯色块压过正文。 */
private const val StatusContainerAlpha = 0.12f

/**
 * 工作流分支的状态。
 */
enum class WorkflowNodeStatus {
    /** 待办，尚未开始。 */
    Pending,

    /** 运行中。 */
    Running,

    /** 已完成。 */
    Done,

    /** 执行失败。 */
    Failed
}

/**
 * 树状工作流的一个分支（节点），纯数据模型，不持有任何 ViewModel 或会话对象。
 *
 * 「专家」是选角后的角色名，模型是覆盖后的模型名，二者都由编排层决定，本组件只负责展示。
 *
 * @param id 分支唯一标识，用于后续接线时定位。
 * @param title 分支标题或需求/问题文本。
 * @param expert 该分支派给的专家名。
 * @param model 该分支使用的模型名。
 * @param status 运行状态。
 * @param children 子分支列表。
 */
@Immutable
data class WorkflowTreeNode(
    val id: String,
    val title: String,
    val expert: String,
    val model: String,
    val status: WorkflowNodeStatus,
    val children: List<WorkflowTreeNode> = emptyList()
)

/**
 * 树状工作流画布的最小可用版本：用纵向缩进 + 连线把分支层级画出来。
 *
 * 本组件只做渲染，不接导航、不读真实数据：点击与新增均由调用方通过回调注入。
 * 没有拖拽、动画与真正的 2D 画布，层级完全由缩进与连线表达。
 *
 * @param root 树的根分支。
 * @param onNodeClick 点击某个分支卡片时触发。
 * @param onAddChild 点击分支卡片上的「新增子分支」按钮时触发。
 * @param modifier 外层修饰符。
 */
@Composable
fun WorkflowTreeBoard(
    root: WorkflowTreeNode,
    onNodeClick: (WorkflowTreeNode) -> Unit,
    onAddChild: ((WorkflowTreeNode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        WorkflowTreeBranch(
            node = root,
            isRoot = true,
            onNodeClick = onNodeClick,
            onAddChild = onAddChild
        )
    }
}

/**
 * 侧边栏「工作流」标签页：把当前会话所在的那棵树画出来。
 *
 * 根取当前会话沿 `parentId` 上溯到的顶层会话——只看自己这一条线，不把别的会话的树摊进来。
 * 当前会话不在 [sessions] 里（刚建/刚删）时给一句空态。
 */
@Composable
fun WorkflowTab(
    sessions: List<ChatSession>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
    currentSessionId: String?,
    onSelect: (ChatSession) -> Unit,
    modifier: Modifier = Modifier
) {
    // 点分支即切到那个会话：看详情、派子代理都在会话里做，抽屉不当第二个操作面板。
    // `?.let` 会返回可空，显式收尾成 Unit，否则推断出的是 (WorkflowTreeNode) -> Unit?。
    val pick: (WorkflowTreeNode) -> Unit = { node ->
        sessions.firstOrNull { it.id == node.id }?.let(onSelect)
        Unit
    }
    val root = remember(sessions, currentSessionId) {
        var cursor = sessions.firstOrNull { it.id == currentSessionId }
        while (cursor?.parentId != null) {
            val parentId = cursor.parentId
            cursor = sessions.firstOrNull { it.id == parentId }
        }
        cursor
    }
    if (root == null) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.workflow_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val tree = remember(root.id, sessions, subSessionsByParent, agentStates) {
        buildWorkflowTree(root, subSessionsByParent, agentStates)
    }
    WorkflowTreeBoard(
        root = tree,
        onNodeClick = pick,
        modifier = modifier,
    )
}

/**
 * 把一个会话及其子会话递归拼成工作流树。
 *
 * 子会话集合由调用方按父 id 分好组传入（侧边栏已在维护）。
 */
fun buildWorkflowTree(
    root: ChatSession,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
): WorkflowTreeNode {
    fun nodeOf(session: ChatSession): WorkflowTreeNode = WorkflowTreeNode(
        id = session.id,
        title = session.title,
        expert = session.subagentType.orEmpty(),
        model = session.model.orEmpty(),
        status = agentStates[session.id].toNodeStatus(),
        children = subSessionsByParent[session.id].orEmpty().map(::nodeOf),
    )
    return nodeOf(root)
}

/** 会话状态 → 分支状态；没跑过的一律算待办。 */
private fun AgentUIState?.toNodeStatus(): WorkflowNodeStatus = when (this) {
    AgentUIState.Loading, AgentUIState.Streaming -> WorkflowNodeStatus.Running
    is AgentUIState.Error -> WorkflowNodeStatus.Failed
    is AgentUIState.Result -> when (status) {
        WorkflowStatus.SUCCESS, WorkflowStatus.PARTIAL_SUCCESS -> WorkflowNodeStatus.Done
        WorkflowStatus.FAILED, WorkflowStatus.CANCELLED -> WorkflowNodeStatus.Failed
    }
    AgentUIState.Idle, null -> WorkflowNodeStatus.Pending
}

/**
 * 递归渲染一个分支及其子树。
 *
 * @param isRoot 根分支不画左侧肘线（它没有父分支可连）。
 */
@Composable
private fun WorkflowTreeBranch(
    node: WorkflowTreeNode,
    isRoot: Boolean,
    onNodeClick: (WorkflowTreeNode) -> Unit,
    onAddChild: ((WorkflowTreeNode) -> Unit)?
) {
    val railColor = MaterialTheme.semanticColors.subtleBorder
    val density = LocalDensity.current
    val indentPx = with(density) { ConnectorIndent.toPx() }
    val strokePx = with(density) { ConnectorStroke.toPx() }
    val stubY = with(density) { ConnectorStubOffset.toPx() }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 卡片区：非根分支在其左上画一条肘线，从本分支左缘连到卡片左缘
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm)
                .drawBehind {
                    if (!isRoot) {
                        drawLine(
                            color = railColor,
                            start = Offset(0f, stubY),
                            end = Offset(indentPx, stubY),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round
                        )
                    }
                }
        ) {
            WorkflowNodeCard(
                node = node,
                onClick = { onNodeClick(node) },
                onAddChild = onAddChild?.let { act -> { act(node) } },
                modifier = if (isRoot) Modifier else Modifier.padding(start = ConnectorIndent)
            )
        }

        // 子树区：先右移一层让竖直导轨对齐本卡片左缘，再在导轨左侧统一画竖线
        if (node.children.isNotEmpty()) {
            val indentModifier = if (isRoot) Modifier else Modifier.padding(start = ConnectorIndent)
            Box(modifier = indentModifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val x = strokePx / 2f
                            drawLine(
                                color = railColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }
                ) {
                    node.children.forEach { child ->
                        WorkflowTreeBranch(
                            node = child,
                            isRoot = false,
                            onNodeClick = onNodeClick,
                            onAddChild = onAddChild
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个分支卡片：状态圆点 + 标题 + 状态徽标与专家/模型 + 新增子分支按钮。
 */
@Composable
private fun WorkflowNodeCard(
    node: WorkflowTreeNode,
    onClick: () -> Unit,
    onAddChild: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.semanticColors
    val shape = RoundedCornerShape(Radius.mdLarge)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.cardSurface)
            .border(ConnectorStroke, colors.subtleBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = Spacing.sm)
                .size(StatusDotSize)
                .clip(CircleShape)
                .background(workflowStatusColor(node.status))
        )
        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkflowStatusBadge(node.status)
                Spacer(modifier = Modifier.width(Spacing.sm))
                // 主会话没有专家（subagentType 为空），此时不能把「专家 ·」两个字挂空。
                val detail = when {
                    node.expert.isNotBlank() && node.model.isNotBlank() ->
                        stringResource(R.string.workflow_node_expert_model, node.expert, node.model)
                    node.expert.isNotBlank() -> stringResource(R.string.workflow_node_expert_only, node.expert)
                    node.model.isNotBlank() -> stringResource(R.string.workflow_node_model_only, node.model)
                    else -> null
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (onAddChild != null) {
            Spacer(modifier = Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = stringResource(R.string.workflow_add_child),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(Spacing.xl)
                    .clip(CircleShape)
                    .clickable(onClick = onAddChild)
                    .padding(Spacing.xs)
            )
        }
    }
}

/**
 * 状态徽标：文字用状态色，底色取同色低透明度，避免引入新的硬编码色值。
 */
@Composable
private fun WorkflowStatusBadge(status: WorkflowNodeStatus) {
    val color = workflowStatusColor(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(color.copy(alpha = StatusContainerAlpha))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Text(
            text = workflowStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/** 状态到语义色的映射，全部取主题 token，不硬编码色值。 */
@Composable
private fun workflowStatusColor(status: WorkflowNodeStatus): Color {
    val colors = MaterialTheme.semanticColors
    return when (status) {
        WorkflowNodeStatus.Pending -> colors.subtleText
        WorkflowNodeStatus.Running -> colors.info
        WorkflowNodeStatus.Done -> colors.success
        WorkflowNodeStatus.Failed -> MaterialTheme.colorScheme.error
    }
}

/** 状态到文案的映射，走双语 strings.xml。 */
@Composable
private fun workflowStatusLabel(status: WorkflowNodeStatus): String = stringResource(
    when (status) {
        WorkflowNodeStatus.Pending -> R.string.workflow_status_pending
        WorkflowNodeStatus.Running -> R.string.workflow_status_running
        WorkflowNodeStatus.Done -> R.string.workflow_status_done
        WorkflowNodeStatus.Failed -> R.string.workflow_status_failed
    }
)
