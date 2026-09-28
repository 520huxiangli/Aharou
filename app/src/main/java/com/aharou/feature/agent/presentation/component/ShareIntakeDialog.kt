package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R

/**
 * 外部分享进来的文件「怎么处理」确认框。
 *
 * 分享在冷启动时送达，那一刻聊天面板往往还没组合完；收到就直接投进输入框会落空，
 * 表现为「分享进来没有任何反应」。所以文件先落地，等用户点一下再投——投递发生在
 * 交互之后，界面必然已就绪；顺带也给了明确反馈。预览与插入指向同一份已落地的文件。
 */
@Composable
internal fun ShareIntakeDialog(
    items: List<PendingUploadAttachment>,
    onPreview: (PendingUploadAttachment) -> Unit,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (items.size == 1) R.string.share_intake_title
                    else R.string.share_intake_title_multiple
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEach { item ->
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInsert) {
                Text(stringResource(R.string.share_intake_insert))
            }
        },
        dismissButton = {
            Row {
                // 多文件时「预览」只能看一个，索性不给这个出口，避免误解成逐个预览。
                if (items.size == 1) {
                    TextButton(onClick = { onPreview(items.first()) }) {
                        Text(stringResource(R.string.share_intake_preview))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
