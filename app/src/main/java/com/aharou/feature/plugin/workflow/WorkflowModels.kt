package com.aharou.feature.plugin.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 工作流数据模型。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）：
 * `app/src/main/java/com/ai/assistance/operit/data/model/Workflow.kt`。
 * 改动：字段与序列化方式对齐 Aharou（`__type` 判别由 [WorkflowNodeSerializer] /
 * [ParameterValueSerializer] 处理，未知节点降级成 [PlaceholderNode] 而不是抛异常）；
 * 追加 `nextRunAt` / `scheduleFinished` / `sourcePluginId` 三个 Aharou 自用字段。
 */
@Serializable
data class Workflow(
    var id: String = "",
    var name: String = "",
    var description: String = "",
    var nodes: List<WorkflowNode> = emptyList(),
    var connections: List<WorkflowNodeConnection> = emptyList(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var enabled: Boolean = true,
    var lastExecutionTime: Long? = null,
    var lastExecutionStatus: ExecutionStatus? = null,
    var totalExecutions: Int = 0,
    var successfulExecutions: Int = 0,
    var failedExecutions: Int = 0,
    /** 调度器算出的下一次到期时刻；null 表示没有待跑的调度（手动触发的工作流永远是 null）。 */
    var nextRunAt: Long? = null,
    /** `repeat=false` 的调度跑过一次后置位，避免下次再被捞起来。 */
    var scheduleFinished: Boolean = false,
    /** 来自插件模板的工作流记下来源插件 id，供重新导入时去重。 */
    var sourcePluginId: String? = null,
)

@Serializable
enum class ExecutionStatus { SUCCESS, FAILED, RUNNING }

@Serializable(with = WorkflowNodeSerializer::class)
sealed class WorkflowNode {
    abstract val id: String
    abstract val type: String
    abstract val name: String
    abstract val description: String
    abstract val position: NodePosition
}

/**
 * 触发节点：工作流入口，本身不执行任何动作，执行时被直接标记为成功。
 *
 * [triggerType]：`manual`（手动触发）、`schedule`（定时，本轮支持）、其余类型（tasker /
 * intent / speech / app_open）保留字段但不参与调度。
 * [triggerConfig] 的键见 [WorkflowTriggers]。
 */
@Serializable
@SerialName("com.ai.assistance.operit.data.model.TriggerNode")
data class TriggerNode(
    override val id: String = "",
    override val type: String = "trigger",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val triggerType: String = "manual",
    @Serializable(with = LenientStringMapSerializer::class)
    val triggerConfig: Map<String, String> = emptyMap(),
) : WorkflowNode()

/** 执行节点：`actionType` 形如 `包名:工具名`，参数值可以是字面量或对上游节点的引用。 */
@Serializable
@SerialName("com.ai.assistance.operit.data.model.ExecuteNode")
data class ExecuteNode(
    override val id: String = "",
    override val type: String = "execute",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val actionType: String = "",
    val actionConfig: Map<String, ParameterValue> = emptyMap(),
    /** Operit 预留的 JS 直执行字段；上游执行器同样没有实现，这里只保留以便解析模板。 */
    val jsCode: String? = null,
) : WorkflowNode()

@Serializable
enum class ConditionOperator { EQ, NE, GT, GTE, LT, LTE, CONTAINS, NOT_CONTAINS, IN, NOT_IN }

@Serializable
@SerialName("com.ai.assistance.operit.data.model.ConditionNode")
data class ConditionNode(
    override val id: String = "",
    override val type: String = "condition",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val left: ParameterValue = ParameterValue.StaticValue(""),
    val operator: ConditionOperator = ConditionOperator.EQ,
    val right: ParameterValue = ParameterValue.StaticValue(""),
) : WorkflowNode()

@Serializable
enum class LogicOperator { AND, OR }

@Serializable
@SerialName("com.ai.assistance.operit.data.model.LogicNode")
data class LogicNode(
    override val id: String = "",
    override val type: String = "logic",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val operator: LogicOperator = LogicOperator.AND,
) : WorkflowNode()

@Serializable
enum class ExtractMode { REGEX, JSON, SUB, CONCAT, RANDOM_INT, RANDOM_STRING }

/** 提取节点：把上游结果按正则 / JSON 路径 / 下标截取 / 拼接 / 随机值加工成一段文本。 */
@Serializable
@SerialName("com.ai.assistance.operit.data.model.ExtractNode")
data class ExtractNode(
    override val id: String = "",
    override val type: String = "extract",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val source: ParameterValue = ParameterValue.StaticValue(""),
    val mode: ExtractMode = ExtractMode.REGEX,
    val expression: String = "",
    @Serializable(with = LenientIntSerializer::class)
    val group: Int = 0,
    val defaultValue: String = "",
    val others: List<ParameterValue> = emptyList(),
    @Serializable(with = LenientIntSerializer::class)
    val startIndex: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val length: Int = -1,
    @Serializable(with = LenientIntSerializer::class)
    val randomMin: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val randomMax: Int = 100,
    @Serializable(with = LenientIntSerializer::class)
    val randomStringLength: Int = 8,
    val randomStringCharset: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
    @Serializable(with = LenientBooleanSerializer::class)
    val useFixed: Boolean = false,
    val fixedValue: String = "",
) : WorkflowNode()

/**
 * 未知节点类型的占位：保留原始 JSON 以便原样写回，执行时明确报错而不是静默跳过。
 *
 * 之所以反序列化而不是抛异常：一份模板里出现一个新版本节点，不应该让整个工作流加载失败。
 */
@Serializable
data class PlaceholderNode(
    override val id: String = "",
    override val type: String = "unknown",
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    /** 原始 `__type`（或 `type`）值，报错时原样展示。 */
    val originalType: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) : WorkflowNode()

/** 参数值：字面量或对上游节点结果的引用。 */
@Serializable(with = ParameterValueSerializer::class)
sealed class ParameterValue {
    @Serializable
    @SerialName("com.ai.assistance.operit.data.model.ParameterValue.StaticValue")
    data class StaticValue(val value: String = "") : ParameterValue()

    @Serializable
    @SerialName("com.ai.assistance.operit.data.model.ParameterValue.NodeReference")
    data class NodeReference(val nodeId: String = "") : ParameterValue()

    /** 未知参数类型：原样保留，解析时抛出明确错误。 */
    data class Unsupported(val raw: kotlinx.serialization.json.JsonElement) : ParameterValue()
}

/** 节点坐标，仅供将来的编辑器使用；执行时不读。 */
@Serializable
data class NodePosition(
    @Serializable(with = LenientFloatSerializer::class)
    val x: Float = 0f,
    @Serializable(with = LenientFloatSerializer::class)
    val y: Float = 0f,
)

/**
 * 节点连接。[condition] 决定源节点处于什么状态时才会走到目标节点，取值见
 * [WorkflowExecutor] 的条件分支（`on_success` / `on_error` / `true` / `false` / 正则）。
 */
@Serializable
data class WorkflowNodeConnection(
    val id: String = "",
    val sourceNodeId: String = "",
    val targetNodeId: String = "",
    @Serializable(with = LenientNullableStringSerializer::class)
    val condition: String? = null,
)

/** 把 `__type` 的全限定名压成最后一段；同时兼容 `ParameterValue$NodeReference` 这种嵌套写法。 */
internal fun simpleTypeName(raw: String?): String =
    raw.orEmpty().trim().replace('$', '.').substringAfterLast('.').trim()
