package com.aharou.feature.workspace.presentation.remote

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.FloatingTabBar
import com.aharou.core.ui.FloatingTabItem
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.container.SshLoginKey
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.settingsLightMode
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield

/** SSH 密钥设置独立页面：底部双 tab——「密钥」管理登录私钥库，「指纹」展示已保存的主机指纹。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostKeysScreen(
    hostKeys: Map<String, String>,
    loginKeys: List<SshLoginKey>,
    onAddLoginKey: (Uri) -> Unit,
    onRemoveHostKey: (String, Int) -> Unit,
    onRemoveLoginKey: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    // 系统返回键与顶栏返回箭头一致：回到远程服务器列表，而不是被外层设置页拦截直接跳回首页。
    BackHandler { onNavigateBack() }
    var detailLoginKey by remember { mutableStateOf<SshLoginKey?>(null) }
    var detailHostAddress by remember { mutableStateOf<String?>(null) }
    val keysScrollState = rememberScrollState()
    val fingerprintsScrollState = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf {
            keysScrollState.isScrollInProgress || fingerprintsScrollState.isScrollInProgress
        }
    }

    val keyFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onAddLoginKey(uri)
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.ssh_host_key_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (pagerState.currentPage == 0) {
                        IconButton(onClick = { keyFilePicker.launch(arrayOf("*/*")) }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.ssh_login_key_add))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                if (tab == 0) {
                    if (loginKeys.isEmpty()) {
                        EmptyCenterHint(stringResource(R.string.ssh_login_key_empty))
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(keysScrollState)
                                .padding(horizontal = Spacing.lg)
                                .padding(bottom = 70.dp)
                        ) {
                            SettingsGroup {
                                loginKeys.sortedBy { it.name }.forEachIndexed { index, key ->
                                    if (index > 0) {
                                        SettingsDivider()
                                    }
                                    LoginKeyRow(
                                        key = key,
                                        onDelete = { onRemoveLoginKey(key.id) },
                                        onClick = { detailLoginKey = key }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (hostKeys.isEmpty()) {
                        EmptyCenterHint(stringResource(R.string.ssh_host_key_empty))
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(fingerprintsScrollState)
                                .padding(horizontal = Spacing.lg)
                                .padding(bottom = 70.dp)
                        ) {
                            SettingsGroup {
                                hostKeys.toSortedMap().entries.toList().forEachIndexed { index, (address, fingerprint) ->
                                    if (index > 0) {
                                        SettingsDivider()
                                    }
                                    val separator = address.lastIndexOf(':')
                                    val host = address.substring(0, separator.coerceAtLeast(0))
                                    val port = address.substring(separator + 1).toIntOrNull()
                                    SwipeToDeleteRow(
                                        onDelete = { if (port != null) onRemoveHostKey(host, port) },
                                        onClick = { detailHostAddress = address }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.lg, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = address,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = fingerprint,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(Spacing.xs))
                                            Icon(
                                                imageVector = FeatherIcons.ChevronRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.semanticColors.subtleText,
                                                modifier = Modifier.width(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Key, stringResource(R.string.ssh_host_key_tab_keys)),
                    FloatingTabItem(FeatherIcons.Shield, stringResource(R.string.ssh_host_key_tab_fingerprints))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    detailLoginKey?.let { key ->
        AlertDialog(
            onDismissRequest = { detailLoginKey = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(key.name) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.ssh_login_key_fingerprint_value,
                            key.fingerprint ?: stringResource(R.string.ssh_login_key_encrypted)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = key.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailLoginKey = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    detailHostAddress?.let { address ->
        val separator = address.lastIndexOf(':')
        val host = address.substring(0, separator.coerceAtLeast(0))
        val port = address.substring(separator + 1).toIntOrNull()
        if (port != null) {
            AlertDialog(
                onDismissRequest = { detailHostAddress = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(address) },
                text = {
                    Text(
                        stringResource(
                            R.string.ssh_host_key_detail,
                            hostKeys[address].orEmpty()
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { detailHostAddress = null }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            )
        }
    }
}

/** 居中空态提示。 */
@Composable
private fun EmptyCenterHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 登录密钥行：图标 + 名称/指纹 + 右箭头，左滑删除，点击查看详情。 */
@Composable
private fun LoginKeyRow(
    key: SshLoginKey,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val light = settingsLightMode()
    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = key.fingerprint ?: stringResource(R.string.ssh_login_key_encrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
