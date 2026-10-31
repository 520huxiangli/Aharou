package com.aharou.feature.agent.domain.tool.editor

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.editor.domain.diagnostics.DiagnosticsRunner
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 语法检查工具：把编辑器里那套波浪线（[DiagnosticsRunner]）暴露给 AI。
 *
 * 与编辑器共享同一份检查器实现，好处是模型改完代码能自己确认「还报不报错」，不必再拼一条
 * `python3 -m py_compile` 的 Bash 命令去解析输出；代价是覆盖范围与编辑器完全一致——
 * 当前只有 Python / Lua，其它语言返回空列表而不是报错。
 *
 * 只读：[DiagnosticsRunner] 内部跑的是固定的检查命令（`py_compile` / `luac -p`），
 * 不执行用户或模型提供的任意命令，因此按 [ToolCapability.READ_WORKSPACE] 免授权。
 */
class DiagnosticsTool @Inject constructor(
    private val diagnosticsRunner: DiagnosticsRunner,
    private val fileAccess: FileAccessProvider,
) : AbstractContextualTool() {

    override val name = "diagnostics"

    override val description =
        "对工作区文件跑一次语法检查，返回行号、级别与提示，等价于编辑器里的波浪线。当前只覆盖 " +
            "Python 与 Lua，其它扩展名返回空列表。容器未就绪时静默返回空（不会为检查拉起容器）。"

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters = mapOf(
        "path" to ToolParameter(
            "path",
            ParameterType.STRING,
            "要检查的文件路径：~/workspace/... 为项目文件；其它绝对路径为容器系统文件。",
            required = true
        ),
        "severity" to ToolParameter(
            "severity",
            ParameterType.STRING,
            "只返回指定级别，缺省返回全部。",
            required = false,
            enum = listOf("ERROR", "WARNING", "INFO")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return ToolResult.Error("路径参数缺失", "MISSING_PATH")

        val access = fileAccess.forWorkspace(context.projectRoot)
        return try {
            // 取文件元数据可能是远程 SFTP 的阻塞调用，不能放在调用方线程（工具默认跑在主线程）。
            val size = withContext(Dispatchers.IO) {
                if (access.exists(path)) access.fileSize(path) else -1L
            }
            if (size < 0L) return ToolResult.Error("文件不存在: $path", "FILE_NOT_FOUND")
            // fileSize 是 Long，check 的阈值按 Int 比；超过 Int 上限的文件必然也已越过 512KB 阈值，
            // 直接饱和到 MAX_VALUE 让它照常被跳过，不做窄化截断（那会把大文件误判成小文件）。
            val contentLength = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val all = diagnosticsRunner.check(path, contentLength, context.projectRoot)
            val only = args["severity"]?.jsonPrimitive?.contentOrNull?.uppercase()
            val kept = if (only.isNullOrEmpty()) all else all.filter { it.severity.name == only }

            ToolResult.Success(
                buildJsonObject {
                    put("file", JsonPrimitive(path))
                    put("count", JsonPrimitive(kept.size))
                    put("diagnostics", buildJsonArray {
                        kept.forEach { d ->
                            add(
                                buildJsonObject {
                                    put("line", JsonPrimitive(d.line))
                                    put("column", JsonPrimitive(d.column))
                                    put("end_column", JsonPrimitive(d.endColumn))
                                    put("severity", JsonPrimitive(d.severity.name))
                                    put("message", JsonPrimitive(d.message))
                                }
                            )
                        }
                    })
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "diagnostics 执行失败: $path", e)
            ToolResult.Error(e.message ?: "语法检查失败", "DIAGNOSTICS_FAILED")
        }
    }

    private companion object {
        const val TAG = "DiagnosticsTool"
    }
}
