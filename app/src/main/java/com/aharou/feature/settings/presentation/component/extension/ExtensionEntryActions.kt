package com.aharou.feature.settings.presentation.component.extension

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.semanticColors
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.extension.domain.ExtensionState
import com.aharou.feature.plugin.export.shareExportedFile
import com.aharou.feature.settings.presentation.ExtensionsViewModel

/**
 * 一条扩展底部的操作区。
 *
 * 技能 / MCP 在「打开原页」旁边多一个「导出」：把整目录或配置打成文件发给别人，
 * 比让对方照着截图重填一遍靠谱。插件不走这里——它有自己的发布流程。
 */
@Composable
internal fun ExtensionEntryActions(
    entry: ExtensionEntry,
    busy: Boolean,
    anyBusy: Boolean,
    onAction: () -> Unit,
    onUninstall: () -> Unit,
    onOpenOriginal: () -> Unit,
    onExport: (() -> Unit)?,
    exportBusy: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            Text(
                text = stringResource(R.string.extension_busy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        } else if (entry.kind == ExtensionKind.PLUGIN) {
            TextButton(onClick = onAction, enabled = !anyBusy) {
                Text(
                    stringResource(
                        if (entry.state == ExtensionState.RUNNING) R.string.extension_action_stop
                        else R.string.extension_action_start
                    )
                )
            }
        } else {
            TextButton(onClick = onOpenOriginal) {
                Text(stringResource(R.string.extension_action_open))
            }
            if (onExport != null) {
                TextButton(onClick = onExport, enabled = !exportBusy) {
                    Text(stringResource(R.string.extension_action_export))
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (entry.kind == ExtensionKind.PLUGIN) {
            TextButton(onClick = onUninstall, enabled = !anyBusy) {
                Text(stringResource(R.string.extension_action_uninstall))
            }
        }
    }
}

/**
 * 导出产物的两个出口：成功就弹系统分享，失败只 toast 一句。
 *
 * 单独挂在列表外层而不是每行里：分享面板 / toast 都是全局一次性动作，挂在行上会随重组反复弹。
 * 失败不上报成页面级错误——导出失败不该让用户觉得整页坏了，技能/MCP 本身还好好的。
 */
@Composable
internal fun ExtensionExportFeedback(viewModel: ExtensionsViewModel) {
    val context = LocalContext.current
    val exported by viewModel.exportedFile.collectAsStateWithLifecycle()
    val failed by viewModel.exportFailed.collectAsStateWithLifecycle()

    LaunchedEffect(exported) {
        val result = exported ?: return@LaunchedEffect
        shareExportedFile(context, result)
        viewModel.clearExportedFile()
    }
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(R.string.extension_export_failed),
            Toast.LENGTH_SHORT
        ).show()
        viewModel.clearExportFailure()
    }
}
