package com.aharou.feature.browser.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.browser.PendingJsDialog

/**
 * [T-android-js-dialogs-256] 面板可见时的网页对话框宿主：把 [PendingJsDialog] 渲染成
 * 「网页提示 / 网页确认 / 网页输入」，由用户显式选择确定或取消。
 *
 * 点击外部不关闭（[AlertDialog] 的 onDismissRequest 是空实现）：一旦放走对话框，底层
 * JsResult 会一直悬空，页面 JS 会阻塞到浏览器超时才恢复。
 */
@Composable
fun BrowserJsDialogHost(
    dialog: PendingJsDialog,
    onResolve: (accept: Boolean, text: String?) -> Unit,
) {
    var text by remember(dialog) { mutableStateOf(dialog.defaultValue.orEmpty()) }

    val title = when (dialog.kind) {
        "confirm" -> stringResource(R.string.browser_dialog_confirm_title)
        "prompt" -> stringResource(R.string.browser_dialog_prompt_title)
        else -> stringResource(R.string.browser_dialog_alert_title)
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
                if (dialog.kind == "prompt") {
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        colors = dialogTextFieldColors(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true, text) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = if (dialog.kind == "alert") {
            null
        } else {
            {
                TextButton(onClick = { onResolve(false, null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
