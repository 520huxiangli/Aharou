package com.aharou.feature.settings.presentation.component

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.mcp.McpDirectoryAuth
import com.aharou.feature.agent.domain.mcp.McpDirectoryLibrary
import com.aharou.feature.agent.domain.mcp.McpDirectoryServer
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ExternalLink

/**
 * 连接器目录页：内置远程 MCP server 清单，支持关键词搜索与分类筛选。
 * 点条目回调 [onPick]，把名称 + URL 预填进添加表单后走既有保存流程。
 */
@Composable
internal fun McpDirectorySection(
    languageTag: String?,
    onPick: (McpServerConfig) -> Unit
) {
    val context = LocalContext.current
    val catalog = remember { McpDirectoryLibrary.load(context) }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val servers = remember(catalog, query, selectedCategory, languageTag) {
        val keyword = query.trim()
        catalog.servers.filter { server ->
            (selectedCategory == null || selectedCategory in server.categories) &&
                (keyword.isEmpty() ||
                    server.title.contains(keyword, ignoreCase = true) ||
                    server.description.resolve(languageTag).contains(keyword, ignoreCase = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
    ) {
        Spacer(Modifier.height(Spacing.sm))

        ModelSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.mcp_directory_search_hint)
        )

        if (catalog.categories.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                item(key = "__all__") {
                    DirectoryCategoryChip(
                        label = stringResource(R.string.mcp_directory_all),
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null }
                    )
                }
                items(catalog.categories, key = { it.id }) { category ->
                    DirectoryCategoryChip(
                        label = category.name.resolve(languageTag),
                        selected = selectedCategory == category.id,
                        onClick = {
                            selectedCategory = if (selectedCategory == category.id) null else category.id
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.mcp_directory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(servers, key = { it.id }) { server ->
                    DirectoryServerRow(
                        server = server,
                        languageTag = languageTag,
                        onClick = { onPick(server.toPrefillConfig()) }
                    )
                }
            }
        }
    }
}

/** 分类筛选小胶囊：选中态用主色描底，未选中用浅灰底。 */
@Composable
private fun DirectoryCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 目录条目行：名称 + 授权标签 + 说明，右侧「查看文档」外链；整行点击即预填添加。 */
@Composable
private fun DirectoryServerRow(
    server: McpDirectoryServer,
    languageTag: String?,
    onClick: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val (authText, authColor, authBg) = directoryAuthStyle(server.auth)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = server.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                McpPill(text = authText, textColor = authColor, backgroundColor = authBg)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = server.description.resolve(languageTag),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val docsUrl = server.docsUrl
        if (!docsUrl.isNullOrBlank()) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.ExternalLink,
                contentDescription = stringResource(R.string.mcp_directory_docs),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { runCatching { uriHandler.openUri(docsUrl) } }
            )
        }
    }
}

/** 授权方式 → 标签文案与配色。 */
@Composable
private fun directoryAuthStyle(auth: McpDirectoryAuth): Triple<String, Color, Color> {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val neutralBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    return when (auth) {
        McpDirectoryAuth.NONE -> Triple(
            stringResource(R.string.mcp_auth_none),
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        )
        McpDirectoryAuth.OPTIONAL -> Triple(
            stringResource(R.string.mcp_auth_optional),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
        McpDirectoryAuth.TOKEN -> Triple(
            stringResource(R.string.mcp_auth_token),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
        McpDirectoryAuth.OAUTH -> Triple(
            stringResource(R.string.mcp_auth_oauth),
            neutral,
            neutralBg
        )
    }
}
