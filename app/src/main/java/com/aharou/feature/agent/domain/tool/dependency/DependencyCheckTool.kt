package com.aharou.feature.agent.domain.tool.dependency

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.dependency.DependencyChecker
import com.aharou.feature.agent.domain.dependency.UpdateStatus
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 检查 Gradle 工程里各依赖是否有新版本。
 *
 * 只读工具（读工作区 + 读网络），[permissionPolicy] 为 AUTO_APPROVE，PLAN 模式下也放行。
 * 结果里每项可升级依赖都带着「改哪一行、从什么改成什么」的建议（`old_string` / `new_string`），
 * 由 AI 用既有的 `editFile` 工具写回——本工具**不**写文件。
 */
class DependencyCheckTool @Inject constructor(
    private val checker: DependencyChecker
) : AgentTool() {

    private companion object {
        const val TAG = "DependencyCheckTool"
    }

    override val name = "check_dependencies"
    override val description =
        "检查 Gradle 工程依赖是否有新版本，支持 gradle/libs.versions.toml 与 build.gradle(.kts) 两种声明形态。" +
            "返回可升级项及每项的「改哪一行、从什么改成什么」（old_string/new_string 建议），" +
            "确认后用 editFile 写回；本工具只读、不修改文件。" +
            "实时仓库全部不可达时会回退 Aharou 自建离线索引（快照、可能滞后，" +
            "此时结果里 repository 为 aharou-index，需向用户说明版本可能不是最新）。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE, ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "要检查的工程目录（默认当前工作区根目录）；一般无需传。",
            required = false
        ),
        "refresh" to ToolParameter(
            name = "refresh",
            type = ParameterType.BOOLEAN,
            description = "true 时忽略 12 小时磁盘缓存，强制重新联网拉取仓库元数据；默认 false。",
            required = false
        ),
        "include_up_to_date" to ToolParameter(
            name = "include_up_to_date",
            type = ParameterType.BOOLEAN,
            description = "true 时结果里也包含已是最新的依赖；默认 false，只看可升级项。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val subPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim().takeIf { !it.isNullOrBlank() }
        val refresh = args["refresh"]?.jsonPrimitive?.booleanOrNull ?: false
        val includeUpToDate = args["include_up_to_date"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val report = checker.check(context.projectRoot, subPath, refresh)
            val visible = if (includeUpToDate) report.results
                else report.results.filter { it.status != UpdateStatus.UP_TO_DATE }

            val updates = JsonArray(
                visible.map { result ->
                    buildJsonObject {
                        put("group", result.declaration.group)
                        put("artifact", result.declaration.artifact)
                        put("label", "${result.declaration.group}:${result.declaration.artifact}")
                        put("current", result.declaration.currentVersion)
                        put("status", result.status.name)
                        put("type", result.declaration.declarationType.name)
                        put("configuration", result.declaration.configuration)
                        put("file", result.declaration.filePath)
                        put("line", result.declaration.line)
                        result.latestStable?.let { put("latest", it) }
                        result.latestVersion?.let { put("repo_latest", it) }
                        result.releaseVersion?.let { put("repo_release", it) }
                        result.repository?.let { put("repository", it) }
                        result.error?.let { put("error", it) }
                        result.edit?.let { edit ->
                            put("old_string", edit.oldString)
                            put("new_string", edit.newString)
                        }
                    }
                }
            )
            val unparsed = JsonArray(
                report.unparsed.map { line ->
                    buildJsonObject {
                        put("file", line.filePath)
                        put("line", line.line)
                        put("text", line.text)
                        put("reason", line.reason)
                    }
                }
            )
            FileLogger.d(TAG, "check_dependencies root=${context.projectRoot} 返回=${visible.size}")
            ToolResult.Success(
                buildJsonObject {
                    put("network_ok", report.networkOk)
                    put("root", report.rootPath)
                    put("update_count", visible.count { it.status == UpdateStatus.UPDATE_AVAILABLE })
                    put("up_to_date", report.results.count { it.status == UpdateStatus.UP_TO_DATE })
                    put("unparsed_count", report.unparsed.size)
                    put("scanned_files", JsonArray(report.scannedFiles.map { JsonPrimitive(it) }))
                    put("repositories", JsonArray(report.repositories.map { JsonPrimitive(it) }))
                    put("updates", updates)
                    put("unparsed", unparsed)
                }
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "check_dependencies 异常", e)
            ToolResult.Error(e.message ?: "依赖检查失败", "DEPENDENCY_CHECK_ERROR")
        }
    }
}
