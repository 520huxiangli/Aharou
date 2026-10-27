package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.settings.data.localmodels.LocalModelDiscovery
import compose.icons.FeatherIcons
import compose.icons.feathericons.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 本地模型服务发现弹层：点按钮扫描局域网（NSD 广播 + 常见端口探测），
 * 列出可用的 OpenAI 兼容 baseUrl，点选后通过 [onPick] 交给调用方填进供应商配置。
 *
 * 扫描状态由弹层自己持有；[discover] 传入的是真正的扫描动作（通常就是
 * [com.aharou.feature.settings.data.localmodels.LocalModelDiscovery.discover]），
 * 弹层关闭时组合被移除，[rememberCoroutineScope] 随之取消，正在进行的扫描会一并中止。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelsSheet(
    discover: suspend () -> List<LocalModelDiscovery.LocalModelService>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LocalModelDiscovery.LocalModelService>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Button(
                onClick = {
                    if (!scanning) {
                        scanning = true
                        failed = false
                        scope.launch {
                            try {
                                results = discover()
                                scanned = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                            } finally {
                                scanning = false
                            }
                        }
                    }
                },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(R.string.local_models_discover))
            }

            if (results.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.local_models_found, results.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsGroup {
                        results.forEachIndexed { index, service ->
                            if (index > 0) SettingsDivider()
                            SettingsRow(
                                icon = FeatherIcons.Server,
                                title = service.displayName,
                                subtitle = service.baseUrl,
                                onClick = { onPick(service.baseUrl) }
                            )
                        }
                    }
                }
            } else if (!scanning && (scanned || failed)) {
                Text(
                    text = stringResource(R.string.local_models_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
