package com.aharou.feature.agent.domain.tool.shizuku

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.BoundedOutput
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 通过 Shizuku 以 adb shell（uid 2000）身份执行命令的工具。
 *
 * 与 [com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool]（本地容器 / 远程 SSH）不同，
 * 本工具直接作用于 Android 系统本身：可执行 `pm` / `am` / `cmd` 等系统命令、读写 `/sdcard` 等。
 * 需用户已安装 Shizuku 并授予本应用权限，否则返回错误提示。
 *
 * UserService 的 AIDL 调用是同步、一次性的（无逐行回传），故不实现流式输出。
 */
class ShizukuTool @Inject constructor(
    private val shizukuManager: ShizukuManager
) : AgentTool() {
    private companion object {
        const val TAG = "ShizukuTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Shizuku"

    override val description =
        "通过 Shizuku 以 adb shell（uid 2000）身份在宿主 Android 系统上执行命令（等价 `adb shell`），用于 `pm`/`am`/`cmd` 等系统操作与读写 /sdcard。" +
            "需用户已安装并授权 Shizuku，且每次调用都会弹窗确认。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要通过 adb shell 执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行 Shizuku 命令",
            summary = command,
            details = "将以 adb shell 身份在 Android 系统上执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")

        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            return ToolResult.Error("Shizuku 未就绪：${stateHint(state)}", code = "SHIZUKU_NOT_READY")
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Shizuku exec (timeout=${timeoutMs}ms): $command")
            val result = shizukuManager.runCommand(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Shizuku exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku exec 失败: $command", e)
            ToolResult.Error("执行 Shizuku 命令失败: ${e.message}")
        }
    }

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku，请先安装并启动 Shizuku 服务"
        ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行，请在 Shizuku 应用中启动服务"
        ShizukuState.PERMISSION_DENIED -> "本应用尚未获得 Shizuku 授权，请在设置中授予"
        ShizukuState.READY -> ""
    }
}
