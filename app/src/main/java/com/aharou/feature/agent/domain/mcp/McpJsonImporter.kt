package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 导入片段的结构性错误原因（UI 侧映射成对应文案）。 */
enum class McpImportError {
    /** 不是合法 JSON。 */
    NOT_JSON,

    /** 缺 `mcpServers` 字段，或其不是对象。 */
    MISSING_SERVERS,

    /** `mcpServers` 为空对象。 */
    EMPTY_SERVERS,

    /** 有 mcpServers，但没有一条能识别出 url/command 的条目。 */
    INVALID_ENTRIES
}

/** 只解析、不写盘的导入预览。 */
data class McpImportPreview(
    /** 名称合规、将被导入/覆盖的 server 名。 */
    val valid: List<String> = emptyList(),
    /** 因名称不合规（[McpServerConfig.isValidName]）被跳过的 server 名。 */
    val skipped: List<String> = emptyList(),
    /** 结构性错误；非空时 [valid]/[skipped] 无意义。 */
    val error: McpImportError? = null
) {
    val hasServer: Boolean get() = valid.isNotEmpty() || skipped.isNotEmpty()
}

/** 实际导入的结果。 */
sealed interface McpImportOutcome {
    /** 导入完成：[names] 为已写入的 server，[skipped] 为因名称不合规跳过的 server。 */
    data class Imported(val names: List<String>, val skipped: List<String>) : McpImportOutcome

    /** 解析/结构校验失败。 */
    data class Failed(val reason: McpImportError) : McpImportOutcome
}

/**
 * 标准 `mcpServers` JSON 导入：把上游 MCP 配置片段（`{"mcpServers": {"name": {...}}}`）合并进本机配置。
 *
 * - 结构（非 JSON / 缺 mcpServers / 空 / 无可识别条目）先校验，不合规直接返回错误，不落盘；
 * - 复用 [McpConfigRepository.parse]，本身已 `ignoreUnknownKeys`，故上游的 `auth` 等未知字段被静默忽略；
 * - 名称不合规（不能拼进 `mcp__名称__工具__`）的条目丢弃并在结果里回报；
 * - 同名条目按既有约定保留凭据：`oauth` 永远沿用旧值；`enabled`/`disabledTools` 属用户设置也保留；
 *   headers/env 仅在新值为空时回退旧值（新配置非空说明用户就是要改它）；
 * - 写回后逐个 [McpManager.reloadServer]。
 */
@Singleton
class McpJsonImporter @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val mcpManager: McpManager
) {
    private companion object {
        const val TAG = "McpJsonImporter"
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** 只解析、不写盘，给导入预览用。 */
    fun preview(raw: String): McpImportPreview {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return McpImportPreview(error = McpImportError.NOT_JSON)
        val root = runCatching { JSON.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return McpImportPreview(error = McpImportError.NOT_JSON)
        val serversObj = root["mcpServers"] as? JsonObject
            ?: return McpImportPreview(error = McpImportError.MISSING_SERVERS)
        if (serversObj.isEmpty()) return McpImportPreview(error = McpImportError.EMPTY_SERVERS)

        val parsed = configRepository.parse(trimmed)
        val valid = parsed.map { it.name }.filter { McpServerConfig.isValidName(it) }
        val skipped = parsed.map { it.name }.filterNot { McpServerConfig.isValidName(it) }
        if (!McpImportPreview(valid, skipped).hasServer) return McpImportPreview(error = McpImportError.INVALID_ENTRIES)
        return McpImportPreview(valid = valid, skipped = skipped)
    }

    /** 解析并写回；同名合并、非法名跳过。 */
    suspend fun import(raw: String): McpImportOutcome {
        val preview = preview(raw)
        preview.error?.let { return McpImportOutcome.Failed(it) }
        val parsed = configRepository.parse(raw.trim())
        val imported = mutableListOf<String>()
        for (server in parsed) {
            if (!McpServerConfig.isValidName(server.name)) continue
            upsert(server)
            mcpManager.reloadServer(server.name)
            imported += server.name
        }
        FileLogger.i(TAG, "导入 MCP 配置：写入 ${imported.size} 个，跳过 ${preview.skipped.size} 个")
        return McpImportOutcome.Imported(imported, preview.skipped)
    }

    /** 按名合并进 server 所属作用域（新增默认全局），保留既有凭据与用户设置。 */
    private suspend fun upsert(server: McpServerConfig) {
        val scope = configRepository.findEntry(server.name)?.scope ?: McpScope.GLOBAL
        val ordered = LinkedHashMap<String, McpServerConfig>()
        scopeServers(scope).forEach { ordered[it.name] = it }
        val prev = ordered[server.name]
        ordered[server.name] = if (prev == null) {
            server
        } else {
            server.copy(
                enabled = prev.enabled,
                disabledTools = prev.disabledTools,
                oauth = prev.oauth ?: server.oauth,
                headers = server.headers.ifEmpty { prev.headers },
                env = server.env.ifEmpty { prev.env }
            )
        }
        write(scope, ordered.values.toList())
    }

    private suspend fun scopeServers(scope: McpScope): List<McpServerConfig> =
        if (scope == McpScope.GLOBAL) configRepository.getGlobalServers()
        else configRepository.getProjectServers()

    private suspend fun write(scope: McpScope, servers: List<McpServerConfig>) {
        if (scope == McpScope.GLOBAL) configRepository.setGlobalServers(servers)
        else configRepository.setProjectServers(servers)
    }
}
