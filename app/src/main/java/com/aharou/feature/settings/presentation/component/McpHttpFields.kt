package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.aharou.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import com.aharou.R

/** Authorization 请求头的固定前缀，快捷令牌输入与此头双向绑定。 */
private const val BEARER_PREFIX = "Bearer "
private const val AUTHORIZATION_HEADER = "Authorization"

/**
 * HTTP 形态字段：URL + Bearer 令牌快捷输入 + 请求头键值对（按照卡片排版规范）。
 *
 * Bearer 令牌一栏等价于「Authorization: Bearer &lt;token&gt;」请求头，避免手敲头部名称；
 * 其它鉴权方式（如 CONTEXT7_API_KEY、X-API-Key）仍走下方的自定义请求头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpHttpFields(
    url: String,
    onUrlChange: (String) -> Unit,
    headers: SnapshotStateList<Pair<String, String>>
) {
    AppTextField(
        value = url,
        onValueChange = onUrlChange,
        label = stringResource(R.string.mcp_server_url),
        placeholder = stringResource(R.string.mcp_server_url_hint),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // ── 鉴权：Bearer 令牌快捷输入 ──
    Text(
        text = stringResource(R.string.mcp_auth_section),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(6.dp))

    val authIndex = headers.indexOfFirst { it.first.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
    val bearerToken = if (authIndex >= 0) headers[authIndex].second.removePrefix(BEARER_PREFIX) else ""
    var tokenVisible by remember { mutableStateOf(false) }

    AppTextField(
        value = bearerToken,
        onValueChange = { token ->
            val index = headers.indexOfFirst { it.first.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
            when {
                index >= 0 && token.isEmpty() -> headers.removeAt(index)
                index >= 0 -> headers[index] = headers[index].first to BEARER_PREFIX + token
                token.isNotEmpty() -> headers.add(AUTHORIZATION_HEADER to BEARER_PREFIX + token)
            }
        },
        label = stringResource(R.string.mcp_bearer_token),
        placeholder = stringResource(R.string.mcp_bearer_token_hint),
        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        trailingIcon = {
            Icon(
                imageVector = if (tokenVisible) FeatherIcons.Eye else FeatherIcons.EyeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { tokenVisible = !tokenVisible }
            )
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = stringResource(R.string.mcp_auth_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // ── 自定义请求头（Authorization 已由上方令牌栏接管，这里不再重复展示）──
    Text(
        text = stringResource(R.string.mcp_custom_headers),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )

    val customHeaders = headers.withIndex()
        .filterNot { it.value.first.equals(AUTHORIZATION_HEADER, ignoreCase = true) }

    if (customHeaders.isEmpty()) {
        Text(
            text = stringResource(R.string.mcp_no_custom_headers),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        customHeaders.forEach { indexed ->
            val index = indexed.index
            val (k, v) = indexed.value
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTextField(
                        value = k,
                        onValueChange = { headers[index] = it to v },
                        label = stringResource(R.string.mcp_header_name),
                        placeholder = stringResource(R.string.mcp_header_name_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    AppTextField(
                        value = v,
                        onValueChange = { headers[index] = k to it },
                        label = stringResource(R.string.mcp_header_value),
                        placeholder = stringResource(R.string.mcp_header_value_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { headers.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Row {
        Surface(
            onClick = { headers.add("" to "") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.mcp_add_header),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
