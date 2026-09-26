package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.data.local.ProviderPresetLibrary
import compose.icons.FeatherIcons
import compose.icons.feathericons.ExternalLink

/**
 * 新增提供商底部弹层：展示自定义提供商与内置官方 provider 列表（来自 api.official.json）。
 * 选择自定义时回调 [onSelectCustom]；选择官方预设时回调 [onSelectOfficial]。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProviderPresetSheet(
    onDismiss: () -> Unit,
    onSelectCustom: () -> Unit,
    onSelectOfficial: (ProviderPreset) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // 纯本地内存/磁盘/assets 同步直读，零延迟零等待，绝无任何网络加载与转圈
    val officialPresets = remember { ProviderPresetLibrary.loadOfficial(context) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetContentHeight = screenHeight * 0.85f
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
                .height(sheetContentHeight)
        ) {
            Text(
                text = stringResource(R.string.provider_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            // ── 供应商列表：自定义 + 内置官方（同一列表，同一样式）──
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                // 自定义提供商：作为列表第一项，样式与内置提供商一致。
                item(key = "__custom__") {
                    Surface(
                        onClick = onSelectCustom,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModelLogoIcon(
                                modelName = "openai",
                                size = 28.dp
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.provider_custom),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                McpPill(
                                    text = "openai",
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                if (officialPresets.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.provider_preset_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(officialPresets, key = { it.id }) { preset ->
                        Surface(
                            onClick = { onSelectOfficial(preset) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ModelLogoIcon(
                                    modelName = preset.name,
                                    size = 28.dp
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (!preset.badge.isNullOrBlank()) {
                                            Spacer(Modifier.width(Spacing.xs))
                                            McpPill(
                                                text = preset.badge,
                                                textColor = MaterialTheme.colorScheme.primary,
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                    ) {
                                        McpPill(
                                            text = preset.type.lowercase(),
                                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                        preset.tags.forEach { tag ->
                                            Box(
                                                modifier = Modifier
                                                    .border(
                                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                                        RoundedCornerShape(com.aharou.core.theme.Radius.pill)
                                                    )
                                                    .background(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                        RoundedCornerShape(com.aharou.core.theme.Radius.pill)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = tag,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!preset.websiteUrl.isNullOrBlank()) {
                                    Surface(
                                        onClick = {
                                            runCatching { uriHandler.openUri(preset.websiteUrl) }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            androidx.compose.material3.Icon(
                                                FeatherIcons.ExternalLink,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = stringResource(R.string.provider_register_now),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}