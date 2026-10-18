package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.presentation.ContainerDnsMode
import com.aharou.feature.settings.presentation.ContainerMirrorMode
import com.aharou.feature.settings.presentation.ContainerSourcesBusy
import com.aharou.feature.settings.presentation.ContainerSourcesViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 设置页「镜像与 DNS」分区：两组单选（镜像源 / DNS）+ 最近一次执行结果。
 *
 * 选项是点一下即时生效的动作，不是先改后存的开关，所以没做成 AppSwitch——选中态靠打勾表达。
 */
@Composable
internal fun ContainerSourcesSection(
    viewModel: ContainerSourcesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mirrorBusy = (state.busy as? ContainerSourcesBusy.Mirror)?.mode
    val dnsBusy = (state.busy as? ContainerSourcesBusy.Dns)?.mode
    val rowsEnabled = state.loaded && state.busy == null

    // 进入分区就核对一次容器内配置：容器被重置/换镜像后 /etc 里的改动会没，宿主侧的选择还在。
    LaunchedEffect(Unit) { viewModel.replayIfNeeded() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.container_sources_mirror_group))
        SettingsGroup {
            SourcesOptionRow(
                title = stringResource(R.string.container_sources_mirror_official),
                subtitle = stringResource(R.string.container_sources_mirror_official_desc),
                selected = state.mirror == ContainerMirrorMode.OFFICIAL,
                busy = mirrorBusy == ContainerMirrorMode.OFFICIAL,
                enabled = rowsEnabled,
                onClick = { viewModel.selectMirror(ContainerMirrorMode.OFFICIAL) }
            )
            SettingsDivider()
            SourcesOptionRow(
                title = stringResource(R.string.container_sources_mirror_china),
                subtitle = if (state.mirror == ContainerMirrorMode.CHINA && state.mirrorHost.isNotBlank()) {
                    stringResource(R.string.container_sources_mirror_china_active, state.mirrorHost)
                } else {
                    stringResource(R.string.container_sources_mirror_china_desc)
                },
                selected = state.mirror == ContainerMirrorMode.CHINA,
                busy = mirrorBusy == ContainerMirrorMode.CHINA,
                enabled = rowsEnabled,
                onClick = { viewModel.selectMirror(ContainerMirrorMode.CHINA) }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.container_sources_dns_group))
        SettingsGroup {
            SourcesOptionRow(
                title = stringResource(R.string.container_sources_dns_system),
                subtitle = stringResource(R.string.container_sources_dns_system_desc),
                selected = state.dns == ContainerDnsMode.SYSTEM,
                busy = dnsBusy == ContainerDnsMode.SYSTEM,
                enabled = rowsEnabled,
                onClick = { viewModel.selectDns(ContainerDnsMode.SYSTEM) }
            )
            SettingsDivider()
            SourcesOptionRow(
                title = stringResource(R.string.container_sources_dns_cloudflare_google),
                subtitle = stringResource(R.string.container_sources_dns_cloudflare_google_desc),
                selected = state.dns == ContainerDnsMode.CLOUDFLARE_GOOGLE,
                busy = dnsBusy == ContainerDnsMode.CLOUDFLARE_GOOGLE,
                enabled = rowsEnabled,
                onClick = { viewModel.selectDns(ContainerDnsMode.CLOUDFLARE_GOOGLE) }
            )
            SettingsDivider()
            SourcesOptionRow(
                title = stringResource(R.string.container_sources_dns_ali_dnspod),
                subtitle = stringResource(R.string.container_sources_dns_ali_dnspod_desc),
                selected = state.dns == ContainerDnsMode.ALI_DNSPOD,
                busy = dnsBusy == ContainerDnsMode.ALI_DNSPOD,
                enabled = rowsEnabled,
                onClick = { viewModel.selectDns(ContainerDnsMode.ALI_DNSPOD) }
            )
        }

        SourcesStatusLine(state.busy, state.message, state.messageIsError)

        Text(
            text = stringResource(R.string.container_sources_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }
}

@Composable
private fun SourcesOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        // 刻意不传 onClick：SettingsRow 见 onClick 就在行尾补一个右箭头，单选行的箭头既暗示「进入下一页」，
        // 又会在忙碌（enabled=false）时整行少一个图标、文字跟着横跳。点击改由 modifier 承担。
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        enabled = enabled,
        trailing = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (selected) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

/**
 * 最近一次结果 / 重放进度。
 *
 * 失败必须留在这页上：换源失败的原因（源不通、代号识别不出、索引刷新失败）只有这里能看到，
 * 静默失败会让用户以为已经切好了。
 */
@Composable
private fun SourcesStatusLine(
    busy: ContainerSourcesBusy?,
    message: String?,
    isError: Boolean
) {
    val replaying = busy == ContainerSourcesBusy.Replay
    if (!replaying && message.isNullOrBlank()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (replaying) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.container_sources_replay_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.semanticColors.success
            )
        }
    }
}
