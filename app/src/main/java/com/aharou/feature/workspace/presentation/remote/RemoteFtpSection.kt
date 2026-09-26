package com.aharou.feature.workspace.presentation.remote

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Text
import com.aharou.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.Spacing
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.settingsLightMode
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Info
import compose.icons.feathericons.Share2
import kotlinx.coroutines.launch

@Composable
fun WiFiFtpServerSection(
    viewModel: RemoteServerViewModel,
    scrollState: ScrollState
) {
    val isRunning by viewModel.ftpServerManager.isRunning.collectAsStateWithLifecycle()
    val serverUrl by viewModel.ftpServerManager.serverUrl.collectAsStateWithLifecycle()
    val port by viewModel.ftpServerManager.port.collectAsStateWithLifecycle()
    val username by viewModel.ftpServerManager.username.collectAsStateWithLifecycle()
    val password by viewModel.ftpServerManager.password.collectAsStateWithLifecycle()
    val isAnonymous by viewModel.ftpServerManager.isAnonymous.collectAsStateWithLifecycle()
    val autoStart by viewModel.ftpServerManager.autoStart.collectAsStateWithLifecycle()
    val errorMessage by viewModel.ftpServerManager.errorMessage.collectAsStateWithLifecycle()

    var editPort by remember(port) { mutableStateOf(port.toString()) }
    var editUsername by remember(username) { mutableStateOf(username) }
    var editPassword by remember(password) { mutableStateOf(password) }
    var editAnonymous by remember(isAnonymous) { mutableStateOf(isAnonymous) }
    var editAutoStart by remember(autoStart) { mutableStateOf(autoStart) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = 70.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.ftp_usage_title))
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 11.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    FeatherIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = "• " + stringResource(R.string.ftp_usage_item_1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• " + stringResource(R.string.ftp_usage_item_2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = "• " + stringResource(R.string.ftp_usage_item_3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.remote_tab_ftp))
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        FeatherIcons.Share2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column {
                        Text(
                            text = stringResource(R.string.remote_tab_ftp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isRunning) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ftp", serverUrl)))
                                        }
                                        android.widget.Toast.makeText(context, context.getString(R.string.ftp_address_copied), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(top = 2.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.ftp_running, serverUrl),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    FeatherIcons.Copy,
                                    contentDescription = stringResource(R.string.ftp_copy_address),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.ftp_not_running),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                AppSwitch(
                    checked = isRunning,
                    onCheckedChange = { viewModel.toggleFtpServer() }
                )
            }

            if (errorMessage != null) {
                val error = errorMessage
                Text(
                    text = context.getString(R.string.ftp_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm)
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.ftp_config_title))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppTextField(
                    value = editPort,
                    onValueChange = { editPort = it.filter { char -> char.isDigit() } },
                    label = stringResource(R.string.ftp_listen_port),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                AppTextField(
                    value = editUsername,
                    onValueChange = { editUsername = it },
                    label = stringResource(R.string.ftp_login_username),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !editAnonymous
                )

                AppTextField(
                    value = editPassword,
                    onValueChange = { editPassword = it },
                    label = stringResource(R.string.ftp_login_password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !editAnonymous
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ftp_allow_anonymous), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = stringResource(R.string.ftp_anonymous_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppSwitch(checked = editAnonymous, onCheckedChange = { editAnonymous = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ftp_auto_start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = stringResource(R.string.ftp_auto_start_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppSwitch(checked = editAutoStart, onCheckedChange = { editAutoStart = it })
                }

                Button(
                    onClick = {
                        val p = editPort.toIntOrNull() ?: 2121
                        viewModel.saveFtpServerConfig(p, editUsername, editPassword, editAnonymous, editAutoStart)
                        android.widget.Toast.makeText(context, context.getString(R.string.ftp_config_saved), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.ftp_save_config))
                }
            }
        }
    }
}
