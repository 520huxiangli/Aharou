package com.aharou.feature.plugin.runtime

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.subagent.TaskTool
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `agent.runSubAgent`：插件派一个子代理去干活。
 *
 * `tools.execute` 对 `task` 是拒绝的（它要真会话与 Agent 上下文），但插件确实需要「派一个能长跑的
 * 子任务」。这里复用 [TaskTool] 的创建逻辑（写租约冲突检查、并发上限、嵌套深度、选角、
 * 模型覆盖全都在里面），只补一个来自插件的父会话与上下文，不另写一套派发。
 *
 * 派出去之后不在本方法里等：子会话的消息、运行状态对插件同样可见
 * （`chat.listMessages` / `chat.getRunState` 用的是当前工作区的会话），插件按自己的节奏取。
 * 这样也不会因为一次 RPC 挂十几分钟而拖住插件进程。
 */
@Singleton
class PluginAgentService @Inject constructor(
    private val pluginManager: PluginManager,
    private val toolRegistry: ToolRegistry,
    private val workspaceRepository: WorkspaceRepository,
    private val sessionUseCase: SessionUseCase
) {

    suspend fun runSubAgent(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val parentSessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val parent = sessionUseCase.getSessionById(parentSessionId)
            ?: throw PluginHostException("SESSION_NOT_FOUND", "session not found: $parentSessionId")
        if (parent.workspacePath.trimEnd('/') != workspace.trimEnd('/')) {
            throw PluginHostException("SESSION_FORBIDDEN", "session is outside the current workspace")
        }
        PluginHostPaths.requireString(params, "prompt").trim().takeIf { it.isNotEmpty() }
            ?: throw PluginHostException("PARAM_INVALID", "prompt is blank")

        val tool = toolRegistry.getTool(TASK_TOOL) as? TaskTool
            ?: throw PluginHostException("SERVICE_UNAVAILABLE", "task tool is not registered")

        val args = buildJsonObject {
            put("action", "create")
            listOf(
                "prompt",
                "description",
                "agent",
                "department",
                "agentQuery",
                "model",
                "provider"
            ).forEach { key ->
                val value = (params[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                put(key, value)
            }
            (params["writePaths"] as? JsonArray)?.let { paths ->
                put("writePaths", JsonArray(paths.mapNotNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.let { JsonPrimitive(it) }
                }))
            }
        }
        val context = AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = workspace,
            language = null,
            sessionId = parent.id,
            mode = AgentMode.BUILD
        )
        val result = try {
            tool.createSubagent(args, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PluginHostException("SUBAGENT_FAILED", e.message ?: e.javaClass.simpleName)
        }
        return when (result) {
            is ToolResult.Error -> throw PluginHostException(
                result.code.ifBlank { "SUBAGENT_FAILED" },
                result.message
            )
            is ToolResult.Success -> {
                val data = result.data as? JsonObject
                buildJsonObject {
                    put("ok", true)
                    put("sessionId", parent.id)
                    data?.forEach { (key, value) -> put(key, value) }
                }
            }
            is ToolResult.Partial -> buildJsonObject {
                put("ok", false)
                put("message", result.message)
                (result.data as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun requireWorkspace(): String {
        val current = workspaceRepository.current.value
        if (current == null || current.path.isBlank()) {
            throw PluginHostException("WORKSPACE_UNAVAILABLE", "no active workspace")
        }
        return current.path
    }

    private companion object {
        const val TASK_TOOL = "task"
    }
}
