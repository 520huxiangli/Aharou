package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AgentUIMessage

/**
 * 工具详情页的载荷：当前要展开看的那条工具消息。
 *
 * 与 [com.aharou.feature.sandbox.FilePreviewHolder] 同构——走「静态持有 + 路由」而不是导航参数：
 * 工具结果可能有几十万字符，塞进导航参数会把它序列化进 Bundle。
 */
internal object ToolCallPreviewHolder {
    var current: AgentUIMessage? = null
}

/** 打开工具详情页的入口，由聊天页在导航层提供（与 `LocalBrowserOpener` 同构）。 */
internal val LocalToolPreviewOpener = staticCompositionLocalOf<((AgentUIMessage) -> Unit)?> { null }

/**
 * 工具调用详情页：把「指令」与「结果」铺满整屏看。
 *
 * 卡片里的片段要跟标题行挤在一起，几十万字符的构建日志/搜索结果在里面只能滚动看个大概；
 * 这里全文展示并允许选中复制（[SelectionContainer]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPreviewScreen(
    message: AgentUIMessage,
    onBack: () -> Unit,
) {
    val argsText = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }
    val resultText = remember(message.id, message.content.length) { formatToolResult(message.content) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = message.toolName ?: stringResource(R.string.common_tool),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            if (!argsText.isNullOrBlank()) {
                ToolPreviewSection(label = stringResource(R.string.tool_instruction), body = argsText)
            }
            if (!resultText.isNullOrBlank()) {
                ToolPreviewSection(label = stringResource(R.string.tool_result), body = resultText)
            }
        }
    }
}

@Composable
private fun ToolPreviewSection(label: String, body: String) {
    Column(modifier = Modifier.padding(bottom = Spacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        SelectionContainer {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
