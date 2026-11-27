package com.aharou.feature.settings.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.presentation.SftpChannelLimitViewModel

/**
 * 「远程连接 → SFTP 通道上限」分区。
 *
 * 上限调大能让更多文件操作并行，调小则少占服务器的会话名额；改完立即作用到已建的连接上
 * （见 `RemoteSshConnection` 对上限流的采集），不必重连。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮，与 [ContainerGradleHeapSection] 同样处理。
 */
@Composable
internal fun SftpChannelLimitSection(
    viewModel: SftpChannelLimitViewModel = hiltViewModel()
) {
    val limit by viewModel.limit.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    SettingsGroupHeader(text = stringResource(R.string.settings_general_remote))
    SettingsGroup {
        SettingsRow(
            icon = null,
            title = stringResource(R.string.settings_sftp_channel_limit),
            subtitle = stringResource(R.string.settings_sftp_channel_limit_desc),
            onClick = { editing = true },
            trailing = {
                Text(
                    text = stringResource(R.string.settings_sftp_channel_limit_value, limit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        )
    }

    if (editing) {
        NumberInputDialog(
            title = stringResource(R.string.settings_sftp_channel_limit),
            initialValue = limit,
            hint = stringResource(R.string.settings_sftp_channel_limit_input_hint),
            minValue = ContainerSettingsRepository.MIN_SFTP_CHANNEL_LIMIT,
            maxValue = ContainerSettingsRepository.MAX_SFTP_CHANNEL_LIMIT,
            onConfirm = {
                viewModel.setLimit(it)
                editing = false
            },
            onDismiss = { editing = false }
        )
    }
}
