package com.aharou.feature.workspace.presentation.remote

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.domain.container.SshHostKeyVerifier

/**
 * 全局「温柔提示」：任意路径（工作区连接 / 文件层 / 同步 / 测试连通性）撞到未确认的
 * SSH 主机指纹时，在任意页面弹出确认引导，而不是默默失败或只留一行日志。
 */
@Composable
internal fun HostKeyGlobalAlert(verifier: SshHostKeyVerifier) {
    val pending by verifier.globalPending.collectAsState()
    val context = LocalContext.current
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { verifier.dismissGlobalPending() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    stringResource(
                        if (p.changed) R.string.ssh_host_key_changed_title
                        else R.string.ssh_host_key_confirm_title
                    )
                )
            },
            text = {
                Text(
                    "${p.host}:${p.port}\n${p.keyType}\n" +
                        stringResource(R.string.ssh_host_key_fingerprint_value, p.fingerprint) +
                        "\n\n" + stringResource(R.string.ssh_host_key_global_hint)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        verifier.confirmGlobalPending()
                        Toast.makeText(context, context.getString(R.string.ssh_host_key_trusted_toast), Toast.LENGTH_LONG).show()
                    }
                ) {
                    Text(stringResource(R.string.ssh_host_key_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { verifier.dismissGlobalPending() }) {
                    Text(stringResource(R.string.ssh_host_key_reject))
                }
            }
        )
    }
}
