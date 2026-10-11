package com.aharou.feature.connectors.presentation

import android.graphics.Color as AndroidColor
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.agent.domain.mcp.MCP_OAUTH_CUSTOM_SCHEME_REDIRECT
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.connectors.domain.Connector
import com.aharou.feature.connectors.domain.ConnectorAuthKind
import com.aharou.feature.connectors.domain.ConnectorLibrary
import com.aharou.feature.settings.presentation.component.McpOAuthEntryPoint
import com.aharou.feature.settings.presentation.component.McpOAuthHost
import com.aharou.feature.settings.presentation.component.McpPill
import com.aharou.feature.settings.presentation.component.ModelSearchField
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import compose.icons.FeatherIcons
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Trash2
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * 连接器目录页：按分类分组的远程 MCP 服务清单，支持关键词搜索；每行左侧是品牌色首字母圆标。
 * 点击未接入项按 [ConnectorAuthKind] 分流（OAuth 走既有授权面板，密钥类弹表单存凭据库），
 * 点击已接入项弹出启停/移除。授权面板复用设置页的 [McpOAuthHost]（其内已含 WebView 与错误映射）。
 */
@Composable
internal fun ConnectorsSection(
    languageTag: String?,
    viewModel: ConnectorsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val oauthController = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, McpOAuthEntryPoint::class.java)
            .mcpOAuthController()
    }
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val installedByConnector = remember(installed) { installed.associateBy { it.server.name } }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var keyTarget by remember { mutableStateOf<Connector?>(null) }
    var clientTarget by remember { mutableStateOf<Connector?>(null) }
    var manageTarget by remember { mutableStateOf<Pair<Connector, McpServerEntry>?>(null) }

    val keyword = query.trim()
    val filtered = remember(viewModel.catalog, keyword, languageTag) {
        viewModel.catalog.filter { connector ->
            keyword.isEmpty() ||
                connector.name.contains(keyword, ignoreCase = true) ||
                connector.about.resolve(languageTag).contains(keyword, ignoreCase = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(Modifier.height(Spacing.sm))
            ModelSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.connector_search_hint)
            )
            Spacer(Modifier.height(Spacing.sm))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.connector_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.xl)
                ) {
                    ConnectorLibrary.CATEGORY_ORDER.forEach { category ->
                        val rows = filtered.filter { it.category == category }
                        if (rows.isEmpty()) return@forEach
                        item(key = "category_$category") {
                            SettingsGroupHeader(text = stringResource(connectorCategoryLabel(category)))
                            SettingsGroup {
                                rows.forEachIndexed { index, connector ->
                                    if (index > 0) SettingsDivider()
                                    ConnectorRow(
                                        connector = connector,
                                        languageTag = languageTag,
                                        entry = installedByConnector[connector.id],
                                        onClick = {
                                            val entry = installedByConnector[connector.id]
                                            when {
                                                entry != null -> manageTarget = connector to entry
                                                connector.authKind == ConnectorAuthKind.NONE ->
                                                    viewModel.installOpen(connector)
                                                connector.authKind == ConnectorAuthKind.KEY_HEADER ||
                                                    connector.authKind == ConnectorAuthKind.KEY_QUERY ->
                                                    keyTarget = connector
                                                connector.authKind == ConnectorAuthKind.OAUTH_CLIENT_ID ->
                                                    clientTarget = connector
                                                else -> scope.launch {
                                                    val prepared = viewModel.prepareOAuth(connector)
                                                    oauthController.open(prepared)
                                                    oauthController.start("", "", MCP_OAUTH_CUSTOM_SCHEME_REDIRECT)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        McpOAuthHost(oauthController)
    }

    keyTarget?.let { connector ->
        ConnectorKeyDialog(
            connector = connector,
            onDismiss = { keyTarget = null },
            onSubmit = { key ->
                viewModel.installWithKey(connector, key)
                keyTarget = null
            }
        )
    }
    clientTarget?.let { connector ->
        ConnectorClientDialog(
            connector = connector,
            onDismiss = { clientTarget = null },
            onSubmit = { clientId, clientSecret ->
                clientTarget = null
                scope.launch {
                    val prepared = viewModel.prepareOAuth(connector)
                    oauthController.open(prepared)
                    oauthController.start(clientId, clientSecret, MCP_OAUTH_CUSTOM_SCHEME_REDIRECT)
                }
            }
        )
    }
    manageTarget?.let { (connector, entry) ->
        ConnectorManageDialog(
            connector = connector,
            entry = entry,
            onToggle = { enabled -> viewModel.setEnabled(entry.server.name, enabled, entry.scope) },
            onRemove = {
                viewModel.remove(entry.server.name)
                manageTarget = null
            },
            onDismiss = { manageTarget = null }
        )
    }
}

/** 单条连接器行：品牌色首字母圆标 + 名称/简介 + 文档外链 + 接入状态。整行可点。 */
@Composable
private fun ConnectorRow(
    connector: Connector,
    languageTag: String?,
    entry: McpServerEntry?,
    onClick: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val brand = remember(connector.color) { parseBrandColor(connector.color) }
    val onBrand = if (brand.luminance() > 0.55f) Color.Black else Color.White

    val statusText = when {
        entry == null -> stringResource(R.string.connector_status_off)
        entry.server.enabled -> stringResource(R.string.connector_status_connected)
        else -> stringResource(R.string.connector_status_disabled)
    }
    val statusColor = when {
        entry == null -> MaterialTheme.colorScheme.outline
        entry.server.enabled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(brand),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = connector.initial,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = onBrand
            )
        }

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connector.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = connector.about.resolve(languageTag),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val docs = connector.docs
        if (!docs.isNullOrBlank()) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.ExternalLink,
                contentDescription = stringResource(R.string.connector_docs),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { runCatching { uriHandler.openUri(docs) } }
            )
        }

        Spacer(Modifier.width(Spacing.sm))
        McpPill(
            text = statusText,
            textColor = statusColor,
            backgroundColor = statusColor.copy(alpha = 0.12f)
        )
    }
}

/** 密钥表单（header / query 形态）：密钥粘进来存凭据库，配置里只留占位符。 */
@Composable
private fun ConnectorKeyDialog(
    connector: Connector,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_key_title, connector.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                connector.auth.where?.takeIf { it.isNotBlank() }?.let { where ->
                    Text(
                        text = stringResource(R.string.connector_key_where, where),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (connector.authKind == ConnectorAuthKind.KEY_QUERY) {
                        stringResource(R.string.connector_key_query_hint, connector.auth.query ?: "key")
                    } else {
                        stringResource(R.string.connector_key_header_hint, connector.auth.header ?: "Authorization")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppTextField(
                    value = key,
                    onValueChange = { key = it; error = false },
                    label = stringResource(R.string.connector_key_field),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = error,
                    colors = dialogTextFieldColors()
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.connector_key_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (key.isBlank()) error = true else onSubmit(key.trim()) }) {
                Text(stringResource(R.string.connector_key_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 自建 OAuth 应用表单：展示厂商开发者后台链接与需登记的回调地址，收 client id 后走授权。 */
@Composable
private fun ConnectorClientDialog(
    connector: Connector,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_client_title, connector.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.connector_client_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                connector.auth.developer?.takeIf { it.isNotBlank() }?.let { developer ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(developer) } }) {
                        Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.connector_client_open_console))
                    }
                }
                Text(
                    text = stringResource(R.string.connector_client_redirect_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = MCP_OAUTH_CUSTOM_SCHEME_REDIRECT,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                AppTextField(
                    value = clientId,
                    onValueChange = { clientId = it; error = false },
                    label = stringResource(R.string.connector_client_id),
                    singleLine = true,
                    isError = error,
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = stringResource(R.string.connector_client_secret),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = dialogTextFieldColors()
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.connector_client_id_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (clientId.isBlank()) error = true else onSubmit(clientId.trim(), clientSecret.trim())
            }) {
                Text(stringResource(R.string.connector_client_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 已接入项的管理表单：启停开关 + 移除（移除二次确认）。 */
@Composable
private fun ConnectorManageDialog(
    connector: Connector,
    entry: McpServerEntry,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(connector.name) },
        text = {
            if (confirming) {
                Text(stringResource(R.string.connector_remove_body))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.connector_enabled_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    AppSwitch(checked = entry.server.enabled, onCheckedChange = onToggle)
                }
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.connector_remove), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirming = true }) {
                    Icon(FeatherIcons.Trash2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.connector_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirming) confirming = false else onDismiss() }) {
                Text(stringResource(if (confirming) R.string.common_cancel else R.string.common_close))
            }
        }
    )
}

/** 分类 id → 显示名。清单分类固定九种，未识别的一律归「其它」。 */
@StringRes
private fun connectorCategoryLabel(category: String): Int = when (category) {
    "work" -> R.string.connector_category_work
    "talk" -> R.string.connector_category_talk
    "files" -> R.string.connector_category_files
    "dev" -> R.string.connector_category_dev
    "data" -> R.string.connector_category_data
    "design" -> R.string.connector_category_design
    "money" -> R.string.connector_category_money
    "search" -> R.string.connector_category_search
    else -> R.string.connector_category_misc
}

/** 解析品牌色（#RRGGBB）；清单里个别颜色可能非法，退回中性灰避免整行渲染失败。 */
private fun parseBrandColor(hex: String): Color =
    runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color(0xFF8A8A8A))
