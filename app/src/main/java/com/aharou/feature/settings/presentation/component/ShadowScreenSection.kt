package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import com.aharou.feature.settings.presentation.VdViewModel

/**
 * 设置页「影子屏」分区：状态 + 启动/停止。
 *
 * 影子屏 = 一块**不在设备屏幕显示**的虚拟显示屏（机制改编自 scrcpy 的
 * new-display，Apache-2.0）。启动后，Agent 可经 `vscreen` 工具在其中运行 App、
 * 截图、点按滑动，用户主屏零打扰。
 */
@Composable
internal fun ShadowScreenSection(viewModel: VdViewModel = hiltViewModel()) {
    val vd by viewModel.vdState.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.vd_settings_title))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val info = vd
                Text(
                    text = if (info != null) {
                        stringResource(R.string.vd_status_running, info.displayId, info.width, info.height)
                    } else {
                        stringResource(R.string.vd_status_stopped)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.vd_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shizuku != ShizukuState.READY) {
                    Text(
                        text = stringResource(R.string.vd_shizuku_hint, shizuku.name),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = shizuku == ShizukuState.READY && vd == null && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.vd_start))
                }
                OutlinedButton(
                    onClick = { viewModel.stop() },
                    enabled = vd != null && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.vd_stop))
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeMessage() },
            title = { Text(stringResource(R.string.vd_settings_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeMessage() }) {
                    Text(stringResource(R.string.soul_ok))
                }
            },
        )
    }
}
