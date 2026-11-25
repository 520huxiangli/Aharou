package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField

/**
 * MCP 资源触发的输入区：server 名 + 资源 uri。
 *
 * 从 ScheduledTasksSection 拆出来，理由同位置围栏那一组：主文件贴着行数门禁。
 * 两个字段必须成对填写，缺一个就不会真的去订阅，所以由调用方把「只填了一半」标成错误。
 */
@Composable
internal fun ScheduledTaskMcpFields(
    serverText: String,
    onServerChange: (String) -> Unit,
    uriText: String,
    onUriChange: (String) -> Unit,
    incomplete: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AppTextField(
            value = serverText,
            onValueChange = onServerChange,
            label = stringResource(R.string.scheduled_tasks_mcp_server),
            isError = incomplete,
            supportingText = {
                Text(stringResource(R.string.scheduled_tasks_mcp_server_hint))
            },
        )
        AppTextField(
            value = uriText,
            onValueChange = onUriChange,
            label = stringResource(R.string.scheduled_tasks_mcp_resource_uri),
            isError = incomplete,
            supportingText = {
                Text(stringResource(R.string.scheduled_tasks_mcp_resource_uri_hint))
            },
        )
        if (incomplete) {
            Text(
                text = stringResource(R.string.scheduled_tasks_mcp_incomplete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
