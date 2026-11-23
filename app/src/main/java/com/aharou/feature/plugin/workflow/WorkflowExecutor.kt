package com.aharou.feature.plugin.workflow

import com.aharou.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 节点执行状态；与 Operit 的 `NodeExecutionState` 一一对应。 */
sealed class NodeExecutionState {
    data object Pending : NodeExecutionState()
    data object Running : NodeExecutionState()
    data class Success(val result: String) : NodeExecutionState()
    data class Skipped(val reason: String = "") : NodeExecutionState()
    data class Failed(val error: String) : NodeExecutionState()
}

/** 依赖图：邻接表 + 入度。 */
internal data class DependencyGraph(
    val adjacencyList: Map<String, List<String>>,
    val inDegree: Map<String, Int>,
)

data class WorkflowExecutionResult(
    val workflowId: String,
    val success: Boolean,
    val nodeResults: Map<String, NodeExecutionState>,
    val message: String,
    val executionTime: Long = System.currentTimeMillis(),
    val shouldRetry: Boolean = false,
)

/**
 * 工作流执行器：拓扑推进、节点结果留存、条件分支与失败传播。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`core/workflow/WorkflowExecutor.kt` 主干。
 * 改动：
 * - 去掉 `Context`、`R.string` 文案、UI 回调默认实现与执行统计/日志条目模型，错误文案改中文常量；
 * - 工具调用改由 [WorkflowToolInvoker] 走 Aharou 的工具注册表；
 * - 不再有「预备运行时」步骤（Operit 那步是为了先注册包工具），Aharou 的工具在插件启用时已进注册表；
 * - 引用节点未完成时给出带节点名的中文错误，便于在运行记录里定位。
 */
@Singleton
class WorkflowExecutor @Inject constructor(
    private val invoker: WorkflowToolInvoker,
) {

    suspend fun execute(
        workflow: Workflow,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap(),
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit = { _, _ -> },
    ): WorkflowExecutionResult = withContext(Dispatchers.IO) {
        val nodeResults = mutableMapOf<String, NodeExecutionState>()

        val allTriggerNodes = workflow.nodes.filterIsInstance<TriggerNode>()
        if (allTriggerNodes.isEmpty()) {
            return@withContext failure(workflow, nodeResults, "工作流没有触发节点，无法确定入口")
        }
        val triggerNodes = if (triggerNodeId != null) {
            val specific = allTriggerNodes.firstOrNull { it.id == triggerNodeId }
                ?: return@withContext failure(workflow, nodeResults, "指定的触发节点不存在: $triggerNodeId")
            listOf(specific)
        } else {
            val manual = allTriggerNodes.filter { it.triggerType == "manual" }
            if (manual.isEmpty()) {
                return@withContext failure(workflow, nodeResults, "没有手动触发节点；定时工作流请由调度器指定触发节点")
            }
            manual
        }

        val graph = buildDependencyGraph(workflow)
        if (detectCycle(graph.adjacencyList, workflow.nodes)) {
            return@withContext failure(workflow, nodeResults, "工作流存在循环依赖，拒绝执行")
        }

        // 触发节点本身不执行，直接标成功；下游可以引用它拿触发时附带的参数。
        val triggerPayload = JsonObject(triggerExtras.mapValues { (_, v) -> JsonPrimitive(v) }).toString()
        for (trigger in triggerNodes) {
            nodeResults[trigger.id] = NodeExecutionState.Success(triggerPayload)
            onNodeStateChange(trigger.id, NodeExecutionState.Success(triggerPayload))
        }

        val executed = executeTopologicalOrder(
            startNodeIds = triggerNodes.map { it.id },
            workflow = workflow,
            graph = graph,
            nodeResults = nodeResults,
            triggerExtras = triggerExtras,
            onNodeStateChange = onNodeStateChange,
        )
        val failures = nodeResults.filterValues { it is NodeExecutionState.Failed }
            .mapValues { (_, state) -> (state as NodeExecutionState.Failed).error }
        if (!executed) {
            val detail = failures.entries.joinToString("；") { (id, error) -> "$id: $error" }
            return@withContext WorkflowExecutionResult(
                workflowId = workflow.id,
                success = false,
                nodeResults = nodeResults.toMap(),
                message = "工作流执行失败（$detail）",
            )
        }
        WorkflowExecutionResult(
            workflowId = workflow.id,
            success = true,
            nodeResults = nodeResults.toMap(),
            message = "工作流执行成功",
        )
    }

    private fun failure(
        workflow: Workflow,
        nodeResults: Map<String, NodeExecutionState>,
        message: String,
    ): WorkflowExecutionResult {
        FileLogger.w(TAG, "工作流执行未开始: ${workflow.id} - $message")
        return WorkflowExecutionResult(
            workflowId = workflow.id,
            success = false,
            nodeResults = nodeResults.toMap(),
            message = message,
        )
    }

    private fun isSkippedState(state: NodeExecutionState?): Boolean = state is NodeExecutionState.Skipped

    private fun resolveParameterValue(
        value: ParameterValue,
        nodeResults: Map<String, NodeExecutionState>,
        triggerExtras: Map<String, String>,
        nodeNames: Map<String, String>,
    ): String = when (value) {
        is ParameterValue.StaticValue -> value.value
        is ParameterValue.NodeReference -> {
            val referenceId = value.nodeId
            if (referenceId.isBlank() && triggerExtras.isNotEmpty()) {
                // 少数模板用空 nodeId 表示「取触发参数」，Operit 侧没有这条语义，这里也不猜。
                throw IllegalStateException("节点引用缺少 nodeId")
            }
            when (val state = nodeResults[referenceId]) {
                is NodeExecutionState.Success -> state.result
                is NodeExecutionState.Skipped -> state.reason
                is NodeExecutionState.Failed -> throw IllegalStateException(
                    "引用的节点 ${nodeNames[referenceId] ?: referenceId} 执行失败: ${state.error}"
                )
                else -> throw IllegalStateException(
                    "引用的节点 ${nodeNames[referenceId] ?: referenceId} 尚未执行完成"
                )
            }
        }
        is ParameterValue.Unsupported -> throw IllegalStateException(
            "不支持的参数值类型: ${value.raw}"
        )
    }

    private fun compareValues(left: String, right: String, operator: ConditionOperator): Boolean {
        fun asNumber(value: String): Double? = value.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val leftNum = asNumber(left)
        val rightNum = asNumber(right)

        /** 数值可比较时比数值；两侧都不是数字时按字符串比；一边是数字一边不是就是模板错。 */
        fun ordered(compareNumbers: (Double, Double) -> Boolean, compareStrings: (String, String) -> Boolean): Boolean =
            when {
                leftNum != null && rightNum != null -> compareNumbers(leftNum, rightNum)
                leftNum != null || rightNum != null ->
                    throw IllegalArgumentException("条件比较两侧类型不一致: $left vs $right")
                else -> compareStrings(left, right)
            }

        return when (operator) {
            ConditionOperator.EQ -> if (leftNum != null && rightNum != null) leftNum == rightNum else left == right
            ConditionOperator.NE -> if (leftNum != null && rightNum != null) leftNum != rightNum else left != right
            ConditionOperator.GT -> ordered({ a, b -> a > b }, { a, b -> a > b })
            ConditionOperator.GTE -> ordered({ a, b -> a >= b }, { a, b -> a >= b })
            ConditionOperator.LT -> ordered({ a, b -> a < b }, { a, b -> a < b })
            ConditionOperator.LTE -> ordered({ a, b -> a <= b }, { a, b -> a <= b })
            ConditionOperator.CONTAINS -> left.contains(right)
            ConditionOperator.NOT_CONTAINS -> !left.contains(right)
            ConditionOperator.IN, ConditionOperator.NOT_IN -> {
                val items = parseList(right)
                if (items.isEmpty()) {
                    operator == ConditionOperator.NOT_IN
                } else {
                    val leftNumber = leftNum
                    val itemNumbers = items.map { asNumber(it) }
                    val allNumbers = itemNumbers.all { it != null }
                    val allStrings = itemNumbers.all { it == null }
                    if (!allNumbers && !allStrings) {
                        throw IllegalArgumentException("IN 列表元素类型不一致: $right")
                    }
                    val contains = if (allNumbers) {
                        val target = leftNumber ?: throw IllegalArgumentException(
                            "条件比较两侧类型不一致: $left vs $right"
                        )
                        itemNumbers.filterNotNull().any { it == target }
                    } else {
                        if (leftNumber != null) {
                            throw IllegalArgumentException("条件比较两侧类型不一致: $left vs $right")
                        }
                        items.contains(left)
                    }
                    if (operator == ConditionOperator.IN) contains else !contains
                }
            }
        }
    }

    private fun parseList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            if (parsed is JsonArray) {
                return parsed.mapNotNull {
                    when (it) {
                        is JsonPrimitive -> it.contentOrNull
                        else -> it.toString()
                    }
                }
            }
        }
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractByRegex(source: String, pattern: String, group: Int, defaultValue: String): String {
        if (pattern.isBlank()) return defaultValue
        return runCatching {
            Regex(pattern).find(source)?.groups?.get(group)?.value
        }.getOrNull() ?: defaultValue
    }

    private fun extractByJsonPath(source: String, path: String, defaultValue: String): String {
        if (path.isBlank()) return defaultValue
        val root = runCatching { Json.parseToJsonElement(source.trim()) }.getOrNull()
            ?: return defaultValue
        var current: JsonElement = root
        val segments = path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        for (segment in segments) {
            val name = segment.substringBefore('[')
            val indexes = INDEX_TOKEN.findAll(segment).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (name.isNotBlank()) {
                current = (current as? JsonObject)?.get(name) ?: return defaultValue
            }
            for (index in indexes) {
                current = (current as? JsonArray)?.getOrNull(index) ?: return defaultValue
            }
        }
        return when (current) {
            is JsonObject, is JsonArray -> current.toString()
            is JsonPrimitive -> current.content
        }
    }

    private fun substringByIndex(source: String, startIndex: Int, length: Int, defaultValue: String): String {
        if (source.isEmpty()) return defaultValue
        if (startIndex < 0 || startIndex > source.length) return defaultValue
        val endExclusive = if (length < 0) source.length else (startIndex + length).coerceAtMost(source.length)
        if (endExclusive < startIndex) return defaultValue
        return source.substring(startIndex, endExclusive)
    }

    private fun randomInt(minValue: Int, maxValue: Int): Int {
        val low = min(minValue, maxValue)
        val high = max(minValue, maxValue)
        if (low == high) return low
        return Random.nextLong(low.toLong(), high.toLong() + 1L).toInt()
    }

    private fun randomString(length: Int, charset: String): String {
        val safeLength = length.coerceAtLeast(0)
        if (safeLength == 0) return ""
        val safeCharset = charset.ifEmpty { DEFAULT_RANDOM_CHARSET }
        val builder = StringBuilder(safeLength)
        repeat(safeLength) { builder.append(safeCharset[Random.nextInt(safeCharset.length)]) }
        return builder.toString()
    }

    private fun buildReferenceDependencies(workflow: Workflow): List<Pair<String, String>> {
        val nodeIds = workflow.nodes.map { it.id }.toSet()
        val dependencies = LinkedHashSet<Pair<String, String>>()
        fun add(sourceId: String, targetId: String) {
            if (sourceId == targetId) return
            if (sourceId !in nodeIds || targetId !in nodeIds) return
            dependencies.add(sourceId to targetId)
        }
        workflow.nodes.forEach { node ->
            when (node) {
                is ExecuteNode -> node.actionConfig.values.forEach { value ->
                    if (value is ParameterValue.NodeReference) add(value.nodeId, node.id)
                }
                is ConditionNode -> {
                    (node.left as? ParameterValue.NodeReference)?.let { add(it.nodeId, node.id) }
                    (node.right as? ParameterValue.NodeReference)?.let { add(it.nodeId, node.id) }
                }
                is ExtractNode -> {
                    (node.source as? ParameterValue.NodeReference)?.let { add(it.nodeId, node.id) }
                    node.others.forEach { other ->
                        if (other is ParameterValue.NodeReference) add(other.nodeId, node.id)
                    }
                }
                else -> Unit
            }
        }
        return dependencies.toList()
    }

    private fun buildDependencyGraph(workflow: Workflow): DependencyGraph {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        for (node in workflow.nodes) {
            inDegree[node.id] = 0
            adjacency[node.id] = mutableListOf()
        }
        fun addEdge(sourceId: String, targetId: String) {
            if (sourceId == targetId) return
            val targets = adjacency.getOrPut(sourceId) { mutableListOf() }
            if (targets.contains(targetId)) return
            targets.add(targetId)
            inDegree[targetId] = (inDegree[targetId] ?: 0) + 1
        }
        workflow.connections.forEach { addEdge(it.sourceNodeId, it.targetNodeId) }
        buildReferenceDependencies(workflow).forEach { (source, target) -> addEdge(source, target) }
        return DependencyGraph(adjacency, inDegree)
    }

    private fun detectCycle(adjacency: Map<String, List<String>>, nodes: List<WorkflowNode>): Boolean {
        val visitState = mutableMapOf<String, Int>()
        nodes.forEach { visitState[it.id] = 0 }
        fun dfs(nodeId: String): Boolean {
            visitState[nodeId] = 1
            for (next in adjacency[nodeId].orEmpty()) {
                when (visitState[next]) {
                    1 -> return true
                    0 -> if (dfs(next)) return true
                }
            }
            visitState[nodeId] = 2
            return false
        }
        return nodes.any { visitState[it.id] == 0 && dfs(it.id) }
    }

    private fun getReachableNodeIds(
        startNodeIds: List<String>,
        adjacency: Map<String, List<String>>,
    ): Set<String> {
        val forwardVisited = mutableSetOf<String>()
        val forwardQueue = ArrayDeque<String>()
        startNodeIds.forEach { if (forwardVisited.add(it)) forwardQueue.addLast(it) }
        while (forwardQueue.isNotEmpty()) {
            val current = forwardQueue.removeFirst()
            adjacency[current].orEmpty().forEach { next ->
                if (forwardVisited.add(next)) forwardQueue.addLast(next)
            }
        }
        val reverse = mutableMapOf<String, MutableList<String>>()
        adjacency.forEach { (source, targets) ->
            targets.forEach { target -> reverse.getOrPut(target) { mutableListOf() }.add(source) }
        }
        val visited = forwardVisited.toMutableSet()
        val queue = ArrayDeque<String>().apply { addAll(forwardVisited) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            reverse[current].orEmpty().forEach { previous ->
                if (visited.add(previous)) queue.addLast(previous)
            }
        }
        return visited
    }

    private suspend fun executeTopologicalOrder(
        startNodeIds: List<String>,
        workflow: Workflow,
        graph: DependencyGraph,
        nodeResults: MutableMap<String, NodeExecutionState>,
        triggerExtras: Map<String, String>,
        onNodeStateChange: (String, NodeExecutionState) -> Unit,
    ): Boolean {
        val reachable = getReachableNodeIds(startNodeIds, graph.adjacencyList)
        val nodeById = workflow.nodes.associateBy { it.id }
        val nodeNames = workflow.nodes.associate { it.id to it.name.ifBlank { it.id } }
        val incomingByTarget = workflow.connections.groupBy { it.targetNodeId }
        val triggerIds = workflow.nodes.filterIsInstance<TriggerNode>().map { it.id }.toSet()
        val startedTriggerIds = startNodeIds.toSet()
        val queue = ArrayDeque<String>()
        val currentInDegree = mutableMapOf<String, Int>()
        var hasFailure = false

        reachable.filter { it !in triggerIds }.forEach { currentInDegree[it] = 0 }
        graph.adjacencyList.forEach { (sourceId, targets) ->
            if (sourceId !in reachable || sourceId in triggerIds) return@forEach
            targets.forEach { targetId ->
                if (targetId !in reachable || targetId in triggerIds) return@forEach
                currentInDegree[targetId] = (currentInDegree[targetId] ?: 0) + 1
            }
        }
        currentInDegree.filterValues { it == 0 }.keys.forEach { queue.addLast(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val currentId = queue.removeFirstOrNull() ?: break
            if (nodeResults.containsKey(currentId)) continue
            val node = nodeById[currentId]
            if (node == null) {
                FileLogger.w(TAG, "工作流引用了不存在的节点: $currentId")
                continue
            }
            val incoming = incomingByTarget[currentId].orEmpty().filter { connection ->
                if (connection.sourceNodeId !in reachable) return@filter false
                if (connection.sourceNodeId in triggerIds && connection.sourceNodeId !in startedTriggerIds) {
                    return@filter false
                }
                true
            }

            val shouldExecute = if (incoming.isEmpty()) {
                true
            } else {
                incoming.any { connection ->
                    val sourceNode = nodeById[connection.sourceNodeId]
                    val sourceState = nodeResults[connection.sourceNodeId]
                    if (isSkippedState(sourceState)) return@any false
                    val rawCondition = connection.condition?.trim().orEmpty()
                    val effectiveCondition = if (rawCondition.isBlank() &&
                        (sourceNode is ConditionNode || sourceNode is LogicNode)
                    ) {
                        "true"
                    } else {
                        rawCondition
                    }
                    when (effectiveCondition.lowercase()) {
                        "error", "failed", "on_error" -> return@any sourceState is NodeExecutionState.Failed
                        "success", "ok", "on_success" -> return@any sourceState is NodeExecutionState.Success
                    }
                    if (effectiveCondition.isBlank()) {
                        return@any sourceState is NodeExecutionState.Success
                    }
                    val desired = when (effectiveCondition.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                    val sourceResult = (sourceState as? NodeExecutionState.Success)?.result
                    if (sourceResult == null) return@any false
                    if (desired != null) {
                        return@any (parseBooleanLike(sourceResult) ?: false) == desired
                    }
                    return@any runCatching { Regex(effectiveCondition).containsMatchIn(sourceResult) }
                        .getOrDefault(false)
                }
            }

            if (!shouldExecute) {
                val reason = "连接条件不满足，节点未执行"
                nodeResults[node.id] = NodeExecutionState.Skipped(reason)
                onNodeStateChange(node.id, NodeExecutionState.Skipped(reason))
                releaseNextNodes(node.id, graph, currentInDegree, queue)
                continue
            }

            val success = executeNode(
                node = node,
                incoming = incoming,
                nodeById = nodeById,
                nodeNames = nodeNames,
                nodeResults = nodeResults,
                triggerExtras = triggerExtras,
                onNodeStateChange = onNodeStateChange,
            )
            if (!success) {
                hasFailure = true
                FileLogger.w(TAG, "工作流节点失败: ${node.name} (${node.id})")
            }
            releaseNextNodes(node.id, graph, currentInDegree, queue)
        }

        if (!hasFailure) return true

        // Operit 语义：失败只要有 `on_error` 分支接住并执行成功，整条工作流仍算成功。
        val outgoingBySource = workflow.connections.groupBy { it.sourceNodeId }
        val hasUnhandledFailure = nodeResults.any { (nodeId, state) ->
            if (state !is NodeExecutionState.Failed) return@any false
            outgoingBySource[nodeId].orEmpty().none { connection ->
                isErrorCondition(connection.condition) &&
                    nodeResults[connection.targetNodeId] is NodeExecutionState.Success
            }
        }
        return !hasUnhandledFailure
    }

    private fun releaseNextNodes(
        nodeId: String,
        graph: DependencyGraph,
        currentInDegree: MutableMap<String, Int>,
        queue: ArrayDeque<String>,
    ) {
        graph.adjacencyList[nodeId].orEmpty().forEach { nextId ->
            if (!currentInDegree.containsKey(nextId)) return@forEach
            val remaining = (currentInDegree[nextId] ?: 0) - 1
            currentInDegree[nextId] = remaining
            if (remaining == 0) queue.addLast(nextId)
        }
    }

    private fun isErrorCondition(condition: String?): Boolean = condition?.trim()?.lowercase() in
        setOf("error", "failed", "on_error")

    private suspend fun executeNode(
        node: WorkflowNode,
        incoming: List<WorkflowNodeConnection>,
        nodeById: Map<String, WorkflowNode>,
        nodeNames: Map<String, String>,
        nodeResults: MutableMap<String, NodeExecutionState>,
        triggerExtras: Map<String, String>,
        onNodeStateChange: (String, NodeExecutionState) -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()

        fun complete(state: NodeExecutionState) {
            nodeResults[node.id] = state
            onNodeStateChange(node.id, state)
        }

        fun fail(reason: String): Boolean {
            complete(NodeExecutionState.Failed(reason))
            return false
        }

        when (node) {
            is TriggerNode -> {
                val payload = JsonObject(triggerExtras.mapValues { (_, v) -> JsonPrimitive(v) }).toString()
                complete(NodeExecutionState.Success(payload))
                return true
            }
            is PlaceholderNode -> return fail(
                "不支持的节点类型 ${node.originalType.ifBlank { "未知" }}（节点 ${node.name.ifBlank { node.id }}）；" +
                    "Aharou 只支持 trigger / execute / extract / condition / logic 五类节点"
            )
            is ConditionNode -> {
                complete(NodeExecutionState.Running)
                return try {
                    val left = resolveParameterValue(node.left, nodeResults, triggerExtras, nodeNames)
                    val right = resolveParameterValue(node.right, nodeResults, triggerExtras, nodeNames)
                    val result = compareValues(left, right, node.operator).toString()
                    complete(NodeExecutionState.Success(result))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("条件节点 ${node.name} 执行异常: ${e.message}")
                }
            }
            is LogicNode -> {
                complete(NodeExecutionState.Running)
                return try {
                    val inputs = incoming.mapNotNull { connection ->
                        val state = nodeResults[connection.sourceNodeId]
                        if (isSkippedState(state)) return@mapNotNull null
                        val result = (state as? NodeExecutionState.Success)?.result ?: return@mapNotNull null
                        parseBooleanLike(result)
                    }
                    val ok = when (node.operator) {
                        LogicOperator.AND -> inputs.isNotEmpty() && inputs.all { it }
                        LogicOperator.OR -> inputs.any { it }
                    }
                    complete(NodeExecutionState.Success(ok.toString()))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("逻辑节点 ${node.name} 执行异常: ${e.message}")
                }
            }
            is ExtractNode -> {
                complete(NodeExecutionState.Running)
                return try {
                    var sourceText = ""
                    if (node.mode != ExtractMode.RANDOM_INT && node.mode != ExtractMode.RANDOM_STRING) {
                        sourceText = resolveParameterValue(node.source, nodeResults, triggerExtras, nodeNames)
                        // Operit 的兜底：source 是空字面量时取第一条上游连接的结果。
                        // 模板里 CONCAT 节点常这么写（source 省略，前置节点由连接表达）。
                        if (sourceText.isBlank() && node.source is ParameterValue.StaticValue) {
                            val fallbackId = incoming.firstOrNull()?.sourceNodeId
                            val fallbackState = fallbackId?.let { nodeResults[it] }
                            if (fallbackState is NodeExecutionState.Success) sourceText = fallbackState.result
                        }
                    }
                    val extracted = when (node.mode) {
                        ExtractMode.REGEX -> extractByRegex(sourceText, node.expression, node.group, node.defaultValue)
                        ExtractMode.JSON -> extractByJsonPath(sourceText, node.expression, node.defaultValue)
                        ExtractMode.SUB -> substringByIndex(sourceText, node.startIndex, node.length, node.defaultValue)
                        ExtractMode.CONCAT -> {
                            val otherText = node.others.joinToString(separator = "") { other ->
                                resolveParameterValue(other, nodeResults, triggerExtras, nodeNames)
                            }
                            sourceText + otherText
                        }
                        ExtractMode.RANDOM_INT -> if (node.useFixed) {
                            (node.fixedValue.trim().toLongOrNull()
                                ?: throw IllegalArgumentException("固定值必须是整数: ${node.fixedValue}")).toString()
                        } else {
                            randomInt(node.randomMin, node.randomMax).toString()
                        }
                        ExtractMode.RANDOM_STRING -> if (node.useFixed) {
                            node.fixedValue
                        } else {
                            randomString(node.randomStringLength, node.randomStringCharset)
                        }
                    }
                    complete(NodeExecutionState.Success(extracted))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("提取节点 ${node.name} 执行异常: ${e.message}")
                }
            }
            is ExecuteNode -> Unit
        }

        val executeNode = node as ExecuteNode
        complete(NodeExecutionState.Running)
        if (executeNode.actionType.isBlank()) {
            return fail(
                if (executeNode.jsCode.isNullOrBlank()) {
                    "执行节点 ${executeNode.name} 没有 actionType"
                } else {
                    "执行节点 ${executeNode.name} 只有 jsCode：Aharou 没有移植 Operit 的 JS 直执行，请改用 actionType 引用工具"
                }
            )
        }
        val args = try {
            executeNode.actionConfig.mapNotNull { (key, value) ->
                val normalizedKey = key.trim()
                if (normalizedKey.isEmpty()) return@mapNotNull null
                normalizedKey to resolveParameterValue(value, nodeResults, triggerExtras, nodeNames)
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("执行节点 ${executeNode.name} 参数解析失败: ${e.message}")
        }

        val outcome = try {
            invoker.invoke(executeNode.actionType, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "工作流节点工具调用异常: ${executeNode.actionType}", e)
            WorkflowNodeOutcome.failure("工具调用异常: ${e.message}")
        }
        return if (outcome.success) {
            complete(NodeExecutionState.Success(outcome.output))
            true
        } else {
            fail(outcome.error ?: "节点 ${executeNode.name} 执行失败")
        }
    }

    private fun parseBooleanLike(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }

    private companion object {
        const val TAG = "WorkflowExecutor"
        const val DEFAULT_RANDOM_CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val INDEX_TOKEN = Regex("\\[(\\d+)]")
    }
}
