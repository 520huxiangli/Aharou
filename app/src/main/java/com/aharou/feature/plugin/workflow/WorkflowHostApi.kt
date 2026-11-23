package com.aharou.feature.plugin.workflow

import com.aharou.feature.plugin.domain.PluginHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 插件侧的工作流 API（Operit 的 `Tools.Workflow.*`）。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）
 * `core/tools/defaultTool/standard/StandardWorkflowTools.kt`，方法名与参数名保持不变：
 * `getAll / create / get / update / delete / setEnabled / trigger`，另加 `registerTemplate`。
 *
 * 接入：`PluginHostApiCatalog` 登记 `workflow.*`，`PluginHostApiDispatcher.route` 转发到本类，
 * `assets/plugin-ui/operit-shim.js` 的 `Tools.Workflow` 已改为调用这些方法。
 */
@Singleton
class WorkflowHostApi @Inject constructor(
    private val store: WorkflowStore,
    private val scheduler: WorkflowScheduler,
    private val importer: WorkflowTemplateImporter,
    private val templateSync: WorkflowTemplateSync,
) {

    suspend fun handle(method: String, params: JsonObject): JsonElement {
        val action = method.trim().substringAfterLast('.').lowercase()
        return when (action) {
            "getall", "list" -> getAll()
            "get" -> get(params)
            "create" -> create(params)
            "update" -> update(params)
            "delete", "remove" -> delete(params)
            "setenabled" -> setEnabled(params, forced = null)
            "enable" -> setEnabled(params, forced = true)
            "disable" -> setEnabled(params, forced = false)
            "trigger", "run" -> trigger(params)
            "patch" -> throw PluginHostException(
                METHOD_UNSUPPORTED,
                "Tools.Workflow.patch 暂未移植：Aharou 版工作流只支持整条更新（update）。" +
                    "请先 get 拿到节点与连接，改完再 update。"
            )
            "registertemplate" -> registerTemplate(params)
            else -> throw PluginHostException(METHOD_UNKNOWN, "未知的工作流方法: $method")
        }
    }

    private suspend fun getAll(): JsonElement {
        // 列表是使用者看工作流的入口：先把已安装插件里的模板补登记一次，
        // 免得「装完插件先在列表里找，什么也没有」。失败不影响读列表。
        runCatching { templateSync.syncInstalled() }
        val workflows = store.list()
        return buildJsonObject {
            put("workflows", JsonArray(workflows.map { summary(it) }))
            put("totalCount", workflows.size)
        }
    }

    private suspend fun get(params: JsonObject): JsonElement {
        val id = requireWorkflowId(params)
        val workflow = store.load(id) ?: throw PluginHostException(NOT_FOUND, "工作流不存在: $id")
        return detail(workflow)
    }

    private suspend fun create(params: JsonObject): JsonElement {
        val name = params.string("name")?.trim().orEmpty()
        if (name.isEmpty()) throw PluginHostException(PARAM_INVALID, "name 不能为空")
        val nodes = parseNodes(params)
        val connections = parseConnections(params)
        val workflow = Workflow(
            name = name,
            description = params.string("description").orEmpty(),
            nodes = nodes,
            connections = connections,
            enabled = params.boolean("enabled") ?: true,
        )
        val saved = store.save(workflow)
        scheduler.reschedule()
        return detail(saved)
    }

    private suspend fun update(params: JsonObject): JsonElement {
        val id = requireWorkflowId(params)
        val existing = store.load(id) ?: throw PluginHostException(NOT_FOUND, "工作流不存在: $id")
        val name = params.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name
        val nodes = if (params.containsKey("nodes")) parseNodes(params) else existing.nodes
        val connections = if (params.containsKey("connections")) {
            parseConnections(params)
        } else {
            existing.connections
        }
        val updated = existing.copy(
            name = name,
            description = params.string("description") ?: existing.description,
            nodes = nodes,
            connections = connections,
            enabled = params.boolean("enabled") ?: existing.enabled,
            // 节点或连接变了，旧的到期时刻是按旧图算的，清掉让 reschedule 重算。
            nextRunAt = null,
            scheduleFinished = false,
        )
        val saved = store.save(updated)
        scheduler.reschedule()
        return detail(saved)
    }

    private suspend fun delete(params: JsonObject): JsonElement {
        val id = requireWorkflowId(params)
        val deleted = store.delete(id)
        scheduler.reschedule()
        if (!deleted) throw PluginHostException(NOT_FOUND, "工作流不存在: $id")
        return buildJsonObject {
            put("deleted", true)
            put("workflowId", id)
            // Operit 这边回的是 StringResultData，插件可能直接读字符串
            put("result", "Workflow deleted: $id")
        }
    }

    private suspend fun setEnabled(params: JsonObject, forced: Boolean?): JsonElement {
        val id = requireWorkflowId(params)
        val existing = store.load(id) ?: throw PluginHostException(NOT_FOUND, "工作流不存在: $id")
        val enabled = forced ?: params.boolean("enabled") ?: true
        val saved = store.save(
            existing.copy(
                enabled = enabled,
                nextRunAt = null,
                // 重新启用后已跑完的一次性调度应该重新武装，否则再也触发不了。
                scheduleFinished = if (enabled) false else existing.scheduleFinished,
            )
        )
        scheduler.reschedule()
        return detail(saved)
    }

    private suspend fun trigger(params: JsonObject): JsonElement {
        val id = requireWorkflowId(params)
        val triggerNodeId = params.string("trigger_node_id")?.trim()?.takeIf { it.isNotEmpty() }
        val result = scheduler.triggerNow(id, triggerNodeId)
        return buildJsonObject {
            put("workflowId", id)
            put("success", result.success)
            put("message", result.message)
            // Operit 的 trigger 回的是 StringResultData；这里同时保留结构化结果。
            put("result", result.message)
            putJsonResults(result.nodeResults)
        }
    }

    private suspend fun registerTemplate(params: JsonObject): JsonElement {
        val json = params.string("json")
            ?: throw PluginHostException(PARAM_INVALID, "registerTemplate 需要 json 参数")
        val sourcePluginId = params.string("plugin_id")?.trim().orEmpty()
        val result = importer.registerFromTemplate(json, sourcePluginId)
        val workflow = result.getOrElse { error ->
            throw PluginHostException(PARAM_INVALID, "模板导入失败: ${error.message}")
        }
        return detail(workflow)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonResults(
        results: Map<String, NodeExecutionState>,
    ) {
        put("nodeResults", buildJsonObject {
            results.forEach { (nodeId, state) ->
                put(nodeId, when (state) {
                    is NodeExecutionState.Success -> state.result
                    is NodeExecutionState.Failed -> "failed: ${state.error}"
                    is NodeExecutionState.Skipped -> "skipped: ${state.reason}"
                    is NodeExecutionState.Running -> "running"
                    NodeExecutionState.Pending -> "pending"
                })
            }
        })
    }

    private fun summary(workflow: Workflow): JsonObject = buildJsonObject {
        put("id", workflow.id)
        put("name", workflow.name)
        put("description", workflow.description)
        put("nodeCount", workflow.nodes.size)
        put("connectionCount", workflow.connections.size)
        put("enabled", workflow.enabled)
        put("createdAt", workflow.createdAt)
        put("updatedAt", workflow.updatedAt)
        put("lastExecutionTime", workflow.lastExecutionTime)
        put("lastExecutionStatus", workflow.lastExecutionStatus?.name)
        put("totalExecutions", workflow.totalExecutions)
        put("successfulExecutions", workflow.successfulExecutions)
        put("failedExecutions", workflow.failedExecutions)
        put("trigger", WorkflowTriggers.scheduleTrigger(workflow)?.let { WorkflowTriggers.describe(it) } ?: "手动")
        put("nextRunAt", workflow.nextRunAt)
    }

    private fun detail(workflow: Workflow): JsonObject {
        val encoded = WorkflowParseJson.encodeToJsonElement(Workflow.serializer(), workflow) as JsonObject
        return JsonObject(
            encoded + mapOf(
                "nodeCount" to JsonPrimitive(workflow.nodes.size),
                "connectionCount" to JsonPrimitive(workflow.connections.size),
                "trigger" to JsonPrimitive(
                    WorkflowTriggers.scheduleTrigger(workflow)?.let { WorkflowTriggers.describe(it) } ?: "手动"
                ),
            )
        )
    }

    private fun requireWorkflowId(params: JsonObject): String {
        val id = params.string("workflow_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: params.string("id")?.trim()?.takeIf { it.isNotEmpty() }
        return id ?: throw PluginHostException(PARAM_INVALID, "workflow_id 不能为空")
    }

    /** `nodes` 既可能是 JSON 字符串（Operit 工具入参形态），也可能是真正的数组。 */
    private fun parseNodes(params: JsonObject): List<WorkflowNode> =
        parseNodePayload(params["nodes"]) ?: emptyList()

    private fun parseConnections(params: JsonObject): List<WorkflowNodeConnection> {
        val element = params["connections"] ?: return emptyList()
        val body = unwrapJsonString(element) ?: return emptyList()
        if (body is JsonNull) return emptyList()
        val wrapper = buildJsonObject { put("connections", body) }
        return decodeWorkflowPart(wrapper).connections
    }

    private fun parseNodePayload(element: JsonElement?): List<WorkflowNode>? {
        val body = unwrapJsonString(element) ?: return null
        if (body is JsonNull) return null
        if (body !is JsonArray) {
            throw PluginHostException(PARAM_INVALID, "nodes 必须是数组")
        }
        return decodeWorkflowPart(buildJsonObject { put("nodes", body) }).nodes
    }

    /** 字符串形态的 JSON 字段先解析成元素；非字符串原样返回。 */
    private fun unwrapJsonString(element: JsonElement?): JsonElement? = when (element) {
        null -> null
        is JsonNull -> JsonNull
        is JsonPrimitive -> if (element.isString) {
            val text = element.contentOrNull.orEmpty().trim()
            if (text.isEmpty()) {
                JsonNull
            } else {
                runCatching { Json.parseToJsonElement(text) }.getOrElse {
                    throw PluginHostException(PARAM_INVALID, "字段不是合法 JSON: ${it.message}")
                }
            }
        } else {
            element
        }
        else -> element
    }

    private fun decodeWorkflowPart(element: JsonObject): Workflow =
        runCatching { WorkflowParseJson.decodeFromJsonElement(Workflow.serializer(), element) }
            .getOrElse { error ->
                throw PluginHostException(PARAM_INVALID, "节点或连接解析失败: ${error.message}")
            }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private companion object {
        const val METHOD_UNKNOWN = "METHOD_UNKNOWN"
        const val METHOD_UNSUPPORTED = "METHOD_UNSUPPORTED"
        const val PARAM_INVALID = "PARAM_INVALID"
        const val NOT_FOUND = "NOT_FOUND"
    }
}
