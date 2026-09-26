package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aharou.core.config.confirm.PendingConfigChange

/**
 * 配置变更确认弹窗（Aharou 配置通道：写入前由用户批准，120 秒窗口）。
 * 自 上游项目 的 ConfigConfirmDialog 移植（裁剪：暂不逐行开关）。
 */
@Composable
internal fun ConfigConfirmDialog(
    change: PendingConfigChange,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("配置变更确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    change.caption ?: "Aharou 想修改自己的设置",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                change.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${item.oldDisplay}  →  ${item.newDisplay}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(item.path, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApprove) { Text("同意") } },
        dismissButton = { TextButton(onClick = onReject) { Text("驳回") } },
    )
}
