/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/viewmodel/WorkflowViewModel.kt
 * 改动：
 * - 数据落点从 Operit 的 Room + Repository 换成 Aharou 的 [WorkflowStore]，调度与执行复用
 *   [WorkflowScheduler]，不再自己管 Worker 与统计表；
 * - 去掉模板工厂（Aharou 的模板导入在 WorkflowTemplateImporter 里），只保留编辑所需的状态机；
 * - 保存前跑 [validateWorkflow]，但结果只作为提示展示，**不阻断保存**（项目红线）。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.plugin.workflow.ConditionNode
import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.LogicNode
import com.aharou.feature.plugin.workflow.PlaceholderNode
import com.aharou.feature.plugin.workflow.NodeExecutionState
import com.aharou.feature.plugin.workflow.NodePosition
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.Workflow
import com.aharou.feature.plugin.workflow.WorkflowNode
import com.aharou.feature.plugin.workflow.WorkflowNodeConnection
import com.aharou.feature.plugin.workflow.WorkflowRunRecord
import com.aharou.feature.plugin.workflow.WorkflowScheduler
import com.aharou.feature.plugin.workflow.WorkflowStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 可新建的节点种类；文案由 UI 层给，避免 ViewModel 里出现用户可见字符串。 */
enum class WorkflowNodeKind(val typeValue: String) {
    TRIGGER("trigger"),
    EXECUTE("execute"),
    CONDITION("condition"),
    LOGIC("logic"),
    EXTRACT("extract"),
}

/** 一次操作的结果提示；文案在 UI 层拼，避免这里硬编码中文。 */
sealed interface WorkflowEditorMessage {
    data class Saved(val issueCount: Int) : WorkflowEditorMessage

    data class RunFinished(val success: Boolean, val detail: String) : WorkflowEditorMessage

    data class Failed(val detail: String) : WorkflowEditorMessage
}

data class WorkflowEditorState(
    val workflows: List<Workflow> = emptyList(),
    val loadingList: Boolean = true,
    val current: Workflow? = null,
    val loadingCurrent: Boolean = true,
    val selectedNodeId: String? = null,
    val selectedConnectionId: String? = null,
    val issues: List<WorkflowIssue> = emptyList(),
    /** 上次运行的节点结果；画布据此给节点上色。 */
    val nodeStates: Map<String, NodeExecutionState> = emptyMap(),
    val runningIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val message: WorkflowEditorMessage? = null,
    /** 当前工作流的最近运行记录，打开「运行记录」时现拉。 */
    val runs: List<WorkflowRunRecord> = emptyList(),
)

@HiltViewModel
class WorkflowEditorViewModel @Inject constructor(
    private val store: WorkflowStore,
    private val scheduler: WorkflowScheduler,
    toolRegistry: ToolRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkflowEditorState())
    val state: StateFlow<WorkflowEditorState> = _state.asStateFlow()

    /** 执行节点的工具名候选 + 参数 schema，来自真实工具注册表而不是另维护一份清单。 */
    private val tools: Map<String, AgentTool> =
        toolRegistry.getAvailableTools().associateBy { it.name }

    val toolNames: List<String> = tools.keys.sorted()

    fun toolOf(name: String): AgentTool? = tools[name.trim()]

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loadingList = true) }
            val list = runCatching { store.list() }
                .onFailure { FileLogger.w(TAG, "读取工作流列表失败", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(workflows = list, loadingList = false) }
        }
    }

    fun open(workflowId: String) {
        _state.update { it.copy(loadingCurrent = true) }
        viewModelScope.launch {
            val loaded = runCatching { store.load(workflowId) }.getOrNull()
            if (loaded == null) {
                // 页面正文已经展示「工作流不存在」，不再弹一条重复的失败提示。
                _state.update { it.copy(loadingCurrent = false, current = null, message = null) }
                return@launch
            }
            // 导入的模板坐标是 Operit 的逻辑单位，直接当像素用会把节点叠在一起；
            // 间距不够时等比放大一次并落盘，之后用户怎么拖都不会再变。
            val workflow = WorkflowLayoutNormalizer.normalize(loaded)?.let { fixed ->
                runCatching { store.save(fixed) }.getOrDefault(fixed)
            } ?: loaded
            _state.update {
                it.copy(
                    current = workflow,
                    loadingCurrent = false,
                    selectedNodeId = null,
                    selectedConnectionId = null,
                    issues = validateWorkflow(workflow),
                    nodeStates = emptyMap(),
                )
            }
        }
    }

    fun close() {
        _state.update { it.copy(current = null, selectedNodeId = null, selectedConnectionId = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 新建时顺手放一个手动触发节点：没有任何节点的画布既没法连线也没法跑，等于给个死局。 */
    fun createWorkflow(
        name: String,
        description: String,
        triggerName: String,
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val workflow = store.save(
                Workflow(
                    name = name,
                    description = description,
                    enabled = true,
                    nodes = listOf(
                        TriggerNode(
                            id = newNodeId("trigger"),
                            name = triggerName,
                            triggerType = "manual",
                            position = NodePosition(160f, 160f),
                        )
                    ),
                )
            )
            runCatching { scheduler.reschedule() }
            refresh()
            onCreated(workflow.id)
        }
    }

    fun deleteWorkflow(workflowId: String) {
        viewModelScope.launch {
            runCatching { store.delete(workflowId) }
                .onFailure { FileLogger.w(TAG, "删除工作流失败: $workflowId", it) }
            runCatching { scheduler.reschedule() }
            refresh()
        }
    }

    /**
     * 启停工作流。启用才排调度，禁用立刻撤掉唤醒；
     * 结果失败时给提示而不是静默——用户点开关必须有反馈。
     */
    fun setEnabled(workflowId: String, enabled: Boolean) {
        viewModelScope.launch {
            val outcome = runCatching {
                val workflow = store.load(workflowId) ?: return@runCatching null
                store.save(
                    workflow.copy(
                        enabled = enabled,
                        // 重新启用时清掉「只跑一次已跑完」的标记，否则启用后永远不会再跑。
                        scheduleFinished = if (enabled) false else workflow.scheduleFinished,
                        nextRunAt = if (enabled) workflow.nextRunAt else null,
                    )
                )
                scheduler.reschedule()
                store.load(workflowId)
            }
            outcome.onFailure { error ->
                FileLogger.w(TAG, "切换工作流启用状态失败: $workflowId", error)
                _state.update { it.copy(message = WorkflowEditorMessage.Failed(error.message.orEmpty())) }
            }
            val updated = outcome.getOrNull()
            _state.update { current ->
                current.copy(
                    workflows = current.workflows.map { if (it.id == workflowId) updated ?: it else it },
                    current = if (current.current?.id == workflowId) updated ?: current.current else current.current,
                )
            }
            refresh()
        }
    }

    /** 手动跑一次；同一工作流在跑的时候直接拒绝，防止连点堆出多个实例。 */
    fun trigger(workflowId: String) {
        val running = _state.value.runningIds
        if (workflowId in running) return
        _state.update { it.copy(runningIds = it.runningIds + workflowId) }
        viewModelScope.launch {
            // 先落盘再跑：执行器读的是磁盘上的版本，不先存的话刚连的线白连了。
            _state.value.current?.takeIf { it.id == workflowId }?.let { editing ->
                runCatching { store.save(editing) }.onSuccess { saved ->
                    _state.update { it.copy(current = saved) }
                }
            }
            val result = runCatching { scheduler.triggerNow(workflowId) }
            _state.update { current ->
                current.copy(
                    runningIds = current.runningIds - workflowId,
                    nodeStates = if (current.current?.id == workflowId) {
                        result.getOrNull()?.nodeResults ?: current.nodeStates
                    } else {
                        current.nodeStates
                    },
                    message = result.fold(
                        onSuccess = { WorkflowEditorMessage.RunFinished(it.success, it.message) },
                        onFailure = { WorkflowEditorMessage.Failed(it.message.orEmpty()) },
                    ),
                )
            }
            refresh()
            val currentId = _state.value.current?.id
            if (currentId == workflowId) {
                // 跑完的统计字段由调度器写盘，编辑态同步一份最新的。
                val latest = runCatching { store.load(workflowId) }.getOrNull()
                if (latest != null) {
                    _state.update { it.copy(current = it.current?.let { _ -> latest }) }
                }
            }
        }
    }

    fun selectNode(nodeId: String?) {
        _state.update { it.copy(selectedNodeId = nodeId, selectedConnectionId = null) }
    }

    fun selectConnection(connectionId: String?) {
        _state.update { it.copy(selectedConnectionId = connectionId, selectedNodeId = null) }
    }

    fun addNode(kind: WorkflowNodeKind, name: String) {
        mutate { workflow ->
            val node = newNode(kind, name, freePosition(workflow))
            workflow.copy(nodes = workflow.nodes + node)
        }
        selectNode(_state.value.current?.nodes?.lastOrNull()?.id)
    }

    fun updateNode(node: WorkflowNode) {
        mutate { workflow ->
            workflow.copy(nodes = workflow.nodes.map { if (it.id == node.id) node else it })
        }
    }

    fun deleteNode(nodeId: String) {
        mutate { workflow ->
            workflow.copy(
                nodes = workflow.nodes.filterNot { it.id == nodeId },
                connections = workflow.connections.filterNot {
                    it.sourceNodeId == nodeId || it.targetNodeId == nodeId
                },
            )
        }
        _state.update { it.copy(selectedNodeId = null) }
    }

    fun moveNode(nodeId: String, x: Float, y: Float) {
        mutate { workflow ->
            workflow.copy(nodes = workflow.nodes.map { if (it.id == nodeId) it.withPosition(x, y) else it })
        }
    }

    /** 从画布拖出来的连线；自连与重复连线直接忽略，不做提示打扰。 */
    fun addConnection(sourceNodeId: String, targetNodeId: String) {
        if (sourceNodeId == targetNodeId) return
        val workflow = _state.value.current ?: return
        val exists = workflow.connections.any {
            it.sourceNodeId == sourceNodeId && it.targetNodeId == targetNodeId
        }
        if (exists) return
        val connection = WorkflowNodeConnection(
            id = "c_" + UUID.randomUUID().toString().replace("-", "").take(12),
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            condition = null,
        )
        mutate { it.copy(connections = it.connections + connection) }
        _state.update { it.copy(selectedConnectionId = connection.id, selectedNodeId = null) }
    }

    fun deleteConnection(connectionId: String) {
        mutate { workflow -> workflow.copy(connections = workflow.connections.filterNot { it.id == connectionId }) }
        _state.update { it.copy(selectedConnectionId = null) }
    }

    fun updateConnectionCondition(connectionId: String, condition: String?) {
        mutate { workflow ->
            workflow.copy(
                connections = workflow.connections.map {
                    if (it.id == connectionId) it.copy(condition = condition) else it
                }
            )
        }
    }

    fun renameWorkflow(name: String, description: String) {
        mutate { it.copy(name = name, description = description) }
    }

    fun loadRuns(workflowId: String) {
        viewModelScope.launch {
            val runs = runCatching { store.recentRuns(workflowId, RUN_HISTORY_LIMIT) }
                .getOrDefault(emptyList())
            _state.update { it.copy(runs = runs) }
        }
    }

    /** 保存并重新排调度；返回提示条数，供界面告诉用户「存了，但还有 N 条需要注意」。 */
    fun save(onSaved: (Int) -> Unit = {}) {
        val workflow = _state.value.current ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val issues = validateWorkflow(workflow)
            val saved = runCatching { store.save(workflow) }
            runCatching { scheduler.reschedule() }
            saved.onFailure { FileLogger.w(TAG, "保存工作流失败: ${workflow.id}", it) }
            _state.update {
                it.copy(
                    saving = false,
                    current = saved.getOrNull() ?: it.current,
                    issues = issues,
                    message = saved.fold(
                        onSuccess = { WorkflowEditorMessage.Saved(issues.size) },
                        onFailure = { error -> WorkflowEditorMessage.Failed(error.message.orEmpty()) },
                    ),
                )
            }
            refresh()
            onSaved(issues.size)
        }
    }

    private fun mutate(transform: (Workflow) -> Workflow) {
        _state.update { current ->
            val workflow = current.current ?: return@update current
            val updated = transform(workflow)
            current.copy(current = updated, issues = validateWorkflow(updated))
        }
    }

    private fun newNode(kind: WorkflowNodeKind, name: String, position: NodePosition): WorkflowNode {
        val id = newNodeId(kind.typeValue)
        return when (kind) {
            WorkflowNodeKind.TRIGGER -> TriggerNode(
                id = id,
                name = name,
                triggerType = "manual",
                position = position,
            )
            WorkflowNodeKind.EXECUTE -> ExecuteNode(id = id, name = name, position = position)
            WorkflowNodeKind.CONDITION -> ConditionNode(id = id, name = name, position = position)
            WorkflowNodeKind.LOGIC -> LogicNode(id = id, name = name, position = position)
            WorkflowNodeKind.EXTRACT -> ExtractNode(id = id, name = name, position = position)
        }
    }

    /** 在 4 列网格里找第一个空位，避免新节点全叠在同一处。 */
    private fun freePosition(workflow: Workflow): NodePosition {
        val taken = workflow.nodes.map { it.position.x.round() to it.position.y.round() }.toSet()
        for (column in 0 until 4) {
            for (row in 0 until 20) {
                val candidate = NodePosition(140f + column * 190f, 140f + row * 130f)
                if ((candidate.x.round() to candidate.y.round()) !in taken) return candidate
            }
        }
        return NodePosition(140f, 140f)
    }

    private fun newNodeId(prefix: String): String =
        prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun Float.round(): Int = kotlin.math.round(this).toInt()

    private companion object {
        const val TAG = "WorkflowEditor"
        const val RUN_HISTORY_LIMIT = 20
    }
}

/** 画布拖动后回写坐标：每个节点都是独立的 data class，只能逐个 copy。 */
private fun WorkflowNode.withPosition(x: Float, y: Float): WorkflowNode = when (this) {
    is TriggerNode -> copy(position = NodePosition(x, y))
    is ExecuteNode -> copy(position = NodePosition(x, y))
    is ExtractNode -> copy(position = NodePosition(x, y))
    is ConditionNode -> copy(position = NodePosition(x, y))
    is LogicNode -> copy(position = NodePosition(x, y))
    is PlaceholderNode -> copy(position = NodePosition(x, y))
}
