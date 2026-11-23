package com.aharou.feature.plugin.workflow

import com.aharou.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件模板 → 工作流的唯一入口。
 *
 * 插件包里的工作流模板（温柔巡检这类）在安装后由 [WorkflowTemplateSync] 扫到并调这里：
 * ```
 * val result = workflowTemplateImporter.registerFromTemplate(
 *     json = 模板文件内容,
 *     sourcePluginId = 插件 id
 * )
 * ```
 * 注册出来的是**未启用**的禁用条目：不会自己排调度，也不会在后台开跑。
 * 使用者显式启用（`workflow.setEnabled` / `Tools.Workflow.enable`）时才排调度。
 *
 * JSON 结构与 Operit 的导出格式一致（`name` / `description` / `nodes` / `connections`），
 * 节点用 `__type` 判别，未知节点会落成 [PlaceholderNode] 留待执行时明确报错。
 */
@Singleton
class WorkflowTemplateImporter @Inject constructor(
    private val store: WorkflowStore,
    private val scheduler: WorkflowScheduler,
) {

    /**
     * 注册一个模板。
     *
     * **默认禁用**：插件装上就每 3 小时跑一轮、用户完全不知情，是不可接受的默认行为。
     * 注册只负责把工作流放进列表（[Workflow.enabled] = false），排调度发生在使用者显式
     * 启用时（`Tools.Workflow.setEnabled` / `workflow.setEnabled`，见 [WorkflowHostApi]）。
     */
    suspend fun registerFromTemplate(
        json: String,
        sourcePluginId: String,
        enabled: Boolean = false,
    ): Result<Workflow> {
        val parsed = runCatching {
            WorkflowParseJson.decodeFromString(Workflow.serializer(), json)
        }.getOrElse { error ->
            FileLogger.w(TAG, "工作流模板解析失败: $sourcePluginId", error)
            return Result.failure(IllegalArgumentException("工作流模板不是合法 JSON: ${error.message}"))
        }
        if (parsed.nodes.isEmpty()) {
            return Result.failure(IllegalArgumentException("工作流模板没有节点: $sourcePluginId"))
        }
        val blankIds = parsed.nodes.filter { it.id.isBlank() }
        if (blankIds.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException("工作流节点缺少 id: ${blankIds.joinToString { it.name.ifBlank { it.type } }}")
            )
        }
        val duplicated = parsed.nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("工作流节点 id 重复: ${duplicated.joinToString()}"))
        }
        val nodeIds = parsed.nodes.map { it.id }.toSet()
        val dangling = parsed.connections.filter { it.sourceNodeId !in nodeIds || it.targetNodeId !in nodeIds }
        if (dangling.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "连接指向不存在的节点: " + dangling.joinToString { "${it.sourceNodeId}->${it.targetNodeId}" }
                )
            )
        }

        // 同一插件、同名模板重复导入时只刷新内容：使用者对工作流的启用状态与执行统计
        // 不应该因为插件重新同步被清掉，id 也得保持不变，否则外部引用会断。
        val existing = store.list().firstOrNull {
            it.sourcePluginId == sourcePluginId && it.name == parsed.name
        }
        if (existing != null) {
            val description = parsed.description.ifBlank { existing.description }
            val unchanged = existing.nodes == parsed.nodes &&
                existing.connections == parsed.connections &&
                existing.description == description
            if (unchanged) return Result.success(existing)
            val updated = existing.copy(
                description = description,
                nodes = parsed.nodes,
                connections = parsed.connections,
                // 内容变了，旧的到期时刻是按旧图算的；重新武装交给下面按启用状态决定。
                nextRunAt = null,
                scheduleFinished = false,
            )
            val saved = store.save(updated)
            if (saved.enabled) scheduler.reschedule()
            FileLogger.i(TAG, "工作流模板已更新: ${saved.name} (${saved.id})，启用状态沿用 ${saved.enabled}")
            return Result.success(saved)
        }
        val created = parsed.copy(
            id = "",
            sourcePluginId = sourcePluginId,
            enabled = enabled,
            nextRunAt = null,
            scheduleFinished = false,
        )
        val saved = store.save(created)
        if (saved.enabled) scheduler.reschedule()
        FileLogger.i(
            TAG,
            "已注册工作流模板: ${saved.name} (${saved.id}) 来自 $sourcePluginId，" +
                "节点 ${saved.nodes.size} 个，触发: ${triggerSummary(saved)}，默认启用=${saved.enabled}",
        )
        return Result.success(saved)
    }

    private fun triggerSummary(workflow: Workflow): String {
        val trigger = WorkflowTriggers.scheduleTrigger(workflow) ?: return "手动"
        return WorkflowTriggers.describe(trigger)
    }

    /** 判断某个插件是否已登记过同名模板（同步器用它区分「新登记」与「内容未变」）。 */
    suspend fun hasTemplate(sourcePluginId: String, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return store.list().any { it.sourcePluginId == sourcePluginId && it.name == name }
    }

    private companion object {
        const val TAG = "WorkflowTemplateImporter"
    }
}
