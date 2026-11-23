/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/viewmodel/WorkflowViewModel.kt
 * 改动：上游没有保存前校验；这里按 Aharou 的产品红线新增「只诊断不阻断」的校验层——
 * 问题以结构化条目返回，由 UI 翻成文案展示，保存流程不因它失败。
 */
package com.aharou.feature.plugin.workfloweditor

import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.ParameterValue
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.Workflow
import com.aharou.feature.plugin.workflow.WorkflowNode

/** 校验问题种类；文案在 UI 层映射成 `R.string.*`，这里不出现任何用户可见字符串。 */
enum class WorkflowIssueCode {
    NO_TRIGGER,
    MULTIPLE_TRIGGERS,
    DUPLICATE_NODE_ID,
    EMPTY_ACTION,
    DANGLING_CONNECTION,
    DANGLING_REFERENCE,
    CYCLE,
}

/**
 * 一条校验提示。
 *
 * [subject] 是出问题的节点名 / 连线 id，供文案插值；为空表示整体性问题。
 */
data class WorkflowIssue(
    val code: WorkflowIssueCode,
    val subject: String = "",
)

/**
 * 保存前的基础校验。
 *
 * 之所以不返回 Boolean：界面要能一条条列出问题（用户点「保存」后仍可继续），
 * 所以这里只产出诊断，不做拦截。
 */
fun validateWorkflow(workflow: Workflow): List<WorkflowIssue> {
    val issues = mutableListOf<WorkflowIssue>()
    val nodes = workflow.nodes
    val nodeIds = nodes.map { it.id }
    val nodeIdSet = nodeIds.toSet()
    val triggerCount = nodes.count { it is TriggerNode }

    if (triggerCount == 0) {
        issues += WorkflowIssue(WorkflowIssueCode.NO_TRIGGER)
    } else if (triggerCount > 1) {
        issues += WorkflowIssue(WorkflowIssueCode.MULTIPLE_TRIGGERS)
    }

    nodeIds.groupingBy { it }.eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach { issues += WorkflowIssue(WorkflowIssueCode.DUPLICATE_NODE_ID, it) }

    nodes.forEach { node ->
        if (node is ExecuteNode && node.actionType.isBlank()) {
            issues += WorkflowIssue(WorkflowIssueCode.EMPTY_ACTION, node.name)
        }
    }

    workflow.connections.forEach { connection ->
        if (connection.sourceNodeId !in nodeIdSet || connection.targetNodeId !in nodeIdSet) {
            issues += WorkflowIssue(WorkflowIssueCode.DANGLING_CONNECTION, connection.id)
        }
    }

    nodes.forEach { node ->
        referencedNodeIds(node)
            .filter { it.isNotBlank() && it !in nodeIdSet }
            .forEach { issues += WorkflowIssue(WorkflowIssueCode.DANGLING_REFERENCE, node.name) }
    }

    if (hasCycle(nodes, workflow)) {
        issues += WorkflowIssue(WorkflowIssueCode.CYCLE)
    }
    return issues
}

/** 节点里所有「引用上游」的参数指向，用于检测悬空引用。 */
private fun referencedNodeIds(node: WorkflowNode): List<String> = when (node) {
    is ExecuteNode -> node.actionConfig.values.mapNotNull { (it as? ParameterValue.NodeReference)?.nodeId }
    is ExtractNode -> buildList {
        (node.source as? ParameterValue.NodeReference)?.let { add(it.nodeId) }
        node.others.forEach { value -> (value as? ParameterValue.NodeReference)?.let { add(it.nodeId) } }
    }
    else -> emptyList()
}

/** Kahn 拓扑：剩下的节点就是环上的节点。连线两端不存在的已在别处单独提示，这里跳过。 */
private fun hasCycle(nodes: List<WorkflowNode>, workflow: Workflow): Boolean {
    val nodeIds = nodes.map { it.id }.toSet()
    val inDegree = nodeIds.associateWith { 0 }.toMutableMap()
    val outgoing = nodeIds.associateWith { mutableListOf<String>() }
    workflow.connections.forEach { connection ->
        val source = connection.sourceNodeId
        val target = connection.targetNodeId
        if (source !in nodeIds || target !in nodeIds) return@forEach
        outgoing[source]?.add(target)
        inDegree[target] = (inDegree[target] ?: 0) + 1
    }
    val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
    var visited = 0
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        visited++
        outgoing[current].orEmpty().forEach { next ->
            val degree = (inDegree[next] ?: 0) - 1
            inDegree[next] = degree
            if (degree == 0) queue.addLast(next)
        }
    }
    return visited < nodeIds.size
}
