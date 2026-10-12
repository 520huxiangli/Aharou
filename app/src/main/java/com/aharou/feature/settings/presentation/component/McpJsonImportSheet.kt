package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.mcp.McpImportError
import com.aharou.feature.agent.domain.mcp.McpImportOutcome
import com.aharou.feature.settings.presentation.McpImportViewModel

/**
 * MCP JSON 导入弹窗：粘贴标准 `mcpServers` 片段 → 实时预览将要导入的服务器 → 一键合并写回。
 * 上游 JSON 里的 `auth` 字段被忽略（Aharou 的 OAuth 需要 endpoints/PKCE/redirectUri，硬映射会产出半配置的失效块）；
 * 需 OAuth 的服务请走「连接器 / OAuth 面板」接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpJsonImportSheet(
    onDismiss: () -> Unit,
    onImported: () -> Unit,
    viewModel: McpImportViewModel = hiltViewModel()
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)
    val preview = remember(text) { viewModel.preview() }

    LaunchedEffect(outcome) {
        if (outcome is McpImportOutcome.Imported) onImported()
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.mcp_import_json_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextField(
                value = text,
                onValueChange = viewModel::onTextChange,
                label = stringResource(R.string.mcp_import_json),
                placeholder = stringResource(R.string.mcp_import_json_placeholder),
                singleLine = false,
                minLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .nestedScroll(flingFix)
            )

            when (val result = outcome) {
                is McpImportOutcome.Imported -> {
                    ImportNote(stringResource(R.string.mcp_import_json_success, result.names.size))
                    if (result.skipped.isNotEmpty()) {
                        ImportNote(stringResource(R.string.mcp_import_json_skipped, result.skipped.size), isError = true)
                    }
                }
                is McpImportOutcome.Failed ->
                    ImportNote(stringResource(mcpImportErrorRes(result.reason)), isError = true)
                null -> preview?.let { note ->
                    note.error?.let { ImportNote(stringResource(mcpImportErrorRes(it)), isError = true) }
                        ?: if (note.valid.isNotEmpty()) {
                            ImportNote(stringResource(R.string.mcp_import_json_preview, note.valid.size))
                            if (note.skipped.isNotEmpty()) {
                                ImportNote(stringResource(R.string.mcp_import_json_skipped, note.skipped.size), isError = true)
                            } else Unit
                        } else Unit
                }
            }

            Button(
                enabled = text.isNotBlank() && !importing,
                onClick = { viewModel.import() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.mcp_import_json_action), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun ImportNote(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun mcpImportErrorRes(reason: McpImportError): Int = when (reason) {
    McpImportError.NOT_JSON -> R.string.mcp_import_json_error_not_json
    McpImportError.MISSING_SERVERS -> R.string.mcp_import_json_error_missing_servers
    McpImportError.EMPTY_SERVERS -> R.string.mcp_import_json_error_empty_servers
    McpImportError.INVALID_ENTRIES -> R.string.mcp_import_json_error_invalid_entries
}
