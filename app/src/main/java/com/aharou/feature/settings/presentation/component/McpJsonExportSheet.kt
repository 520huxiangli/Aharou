package com.aharou.feature.settings.presentation.component

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.settings.presentation.McpExportViewModel
import kotlinx.coroutines.launch

/**
 * MCP 配置导出弹窗：把当前生效的 server 配置吐成标准 `mcpServers` JSON，供复制或分享。
 *
 * 只导出配置文本本身：连接器引用的密钥在配置里是占位符，导出后仍是占位符、不会带出真值；
 * 但用户自己直接写进配置的明文密钥会被原样导出，所以界面上明确提示别公开分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpJsonExportSheet(
    onDismiss: () -> Unit,
    viewModel: McpExportViewModel = hiltViewModel()
) {
    val json by viewModel.json.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.mcp_export_json_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.mcp_export_json_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            )
            Button(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("mcp-json", json)))
                        copied = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (copied) R.string.mcp_export_json_copied else R.string.mcp_export_json_copy))
            }
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.mcp_export_json_title)))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.mcp_export_json_share))
            }
        }
    }
}
