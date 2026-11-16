package com.aharou.feature.im

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.im.domain.maibot.MaiBotInstaller
import com.aharou.feature.im.domain.maibot.MaiBotRuntimeManager
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** MaiBot 常驻进程的启停与状态查询。与 NapCat 共用机器人环境，需先装好环境并登录 QQ。 */
@Singleton
class MaiBotTool @Inject constructor(
    private val runtime: Provider<MaiBotRuntimeManager>,
    private val installer: Provider<MaiBotInstaller>,
) : AgentTool() {
    override val name = "maibot"
    override val description =
        "管理 MaiBot 拟人机器人常驻进程：status 查状态、start 启动、stop 停止、logs 看日志。" +
            "它跑在机器人环境里，通过 NapCat 的 OneBot 正向 WebSocket 收发 QQ 消息，模型与人格在 MaiBot 自己的配置里。"
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作", enum = ACTIONS),
    )
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> = when (action(args)) {
        "status", "logs" -> capabilities
        else -> setOf(ToolCapability.MODIFY_CONTAINER_ENV, ToolCapability.EXECUTE_COMMANDS)
    }
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = action(args)
        if (action !in ACTIONS) return ToolResult.Error("Unknown MaiBot action", "INVALID_ARGUMENT")
        if (args.keys.any { it != "action" && it != "tool_title" }) {
            return ToolResult.Error("Only action is accepted", "INVALID_ARGUMENT")
        }
        if (context.mode == AgentMode.PLAN && action !in setOf("status", "logs")) {
            return ToolResult.Error("MaiBot write operations are unavailable in PLAN mode", "READ_ONLY")
        }
        return try {
            val manager = runtime.get()
            when (action) {
                "install" -> {
                    val result = installer.get().install()
                    if (result.success) {
                        status()
                    } else {
                        ToolResult.Error(result.message, "MAIBOT_INSTALL_FAILED")
                    }
                }
                "start" -> {
                    manager.start()
                    status()
                }
                "stop" -> {
                    manager.stop()
                    status()
                }
                "logs" -> ToolResult.Success(buildJsonObject {
                    put("logs", JsonArray(manager.logs.value.map { JsonPrimitive(it) }))
                })
                else -> status()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: e.javaClass.simpleName, "MAIBOT_FAILED")
        }
    }

    private fun status(): ToolResult {
        val state = runtime.get().state.value
        return ToolResult.Success(buildJsonObject {
            put("phase", state.phase)
            put("pid", state.pid ?: 0)
            put("error", state.error)
        })
    }

    private fun action(args: Map<String, JsonElement>): String? =
        (args["action"] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val ACTIONS = listOf("status", "start", "stop", "logs", "install")
    }
}
