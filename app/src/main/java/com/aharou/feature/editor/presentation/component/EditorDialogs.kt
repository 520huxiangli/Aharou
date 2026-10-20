package com.aharou.feature.editor.presentation.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.aharou.R
import com.aharou.core.util.LineDiff

/** 关闭带未保存修改的标签页前的确认框：放弃修改并关掉，或取消留下。 */
@Composable
internal fun EditorCloseTabDialog(
    fileName: String,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_close_tab_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_close_tab_unsaved_message, fileName)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.editor_close_tab_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 保存冲突提示：磁盘内容已被外部改过，展示磁盘版与本地版的差异，让用户选择覆盖、用磁盘版、或取消。
 */
@Composable
internal fun EditorSaveConflictDialog(
    diskContent: String,
    localContent: String,
    onOverwrite: () -> Unit,
    onUseDisk: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_save_conflict_title)) },
        text = {
            Text(
                stringResource(R.string.editor_save_conflict_message) + "\n\n" + LineDiff.toUnified(diskContent, localContent),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onOverwrite) { Text(stringResource(R.string.editor_save_conflict_overwrite)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = onUseDisk) { Text(stringResource(R.string.editor_save_conflict_discard)) }
            }
        }
    )
}

/**
 * 未保存修改提示：[confirmLabelRes] 是确认键文案（关编辑页「保存并退出」、右栏换文件「保存并切换」）。
 *
 * [onSave] 保存后执行待确认的离开动作；[onDiscard] 直接丢弃修改并执行它；[onDismiss] 取消，留在当前文件。
 */
@Composable
internal fun EditorUnsavedDialog(
    confirmLabelRes: Int,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_message)) },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(confirmLabelRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.editor_dont_save)) }
        }
    )
}
