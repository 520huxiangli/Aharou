package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.settings.presentation.SettingsViewModel

/** MCP 弹窗的打开请求：编辑现有条目（[editing]）或从连接器目录预填新增（[prefill]）。 */
internal data class McpDialogRequest(
    val editing: McpServerEntry?,
    val prefill: McpServerConfig?
)

/**
 * MCP 新增/编辑弹窗宿主：把取工具、刷新、看日志、保存等接线从设置页大文件里收出来，
 * 设置页只需持有 [McpDialogRequest] 状态，并在 [onOpenLogs] 里接管跳转日志页。
 */
@Composable
internal fun McpServerDialogHost(
    viewModel: SettingsViewModel,
    request: McpDialogRequest,
    onDismiss: () -> Unit,
    onOpenLogs: (String) -> Unit
) {
    val editing = request.editing
    McpServerEditDialog(
        initial = editing?.server,
        initialScope = editing?.scope,
        prefill = request.prefill,
        tools = viewModel.getMcpServerTools(editing?.server?.name),
        onRefreshTools = { editing?.let { viewModel.reloadMcpServer(it.server.name) } },
        onOpenLogs = editing?.let { existing -> { onOpenLogs(existing.server.name) } },
        onDismiss = onDismiss,
        onSave = { config, scope ->
            viewModel.upsertMcpServer(editing?.server?.name, editing?.scope, config, scope)
            onDismiss()
        }
    )
}
