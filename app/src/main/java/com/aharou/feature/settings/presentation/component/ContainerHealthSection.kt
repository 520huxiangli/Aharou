package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.container.ContainerDoctor
import com.aharou.feature.agent.domain.container.HealthItem
import com.aharou.feature.agent.domain.container.HealthReport
import com.aharou.feature.agent.domain.container.HealthStatus
import com.aharou.feature.agent.domain.container.InstallOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 容器环境体检入口：一键跑完四项探测，把结论逐条摆在设置页。
 *
 * 只在用户点按钮时跑，不做后台轮询——探测要起进程，没必要常驻。
 */
@Composable
internal fun ContainerHealthSection() {
    val context = LocalContext.current.applicationContext
    val doctor = remember(context) {
        EntryPointAccessors.fromApplication(context, ContainerDoctorEntryPoint::class.java).containerDoctor()
    }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var running by remember { mutableStateOf(false) }
    // 正在装的那一项 id。同一时刻只可能有一项在装，用单值比 Map 简单。
    var installingId by remember { mutableStateOf<String?>(null) }
    // 安装结果的提示文案。key 是体检项 id，重新体检时清空。
    var installMessage by remember { mutableStateOf<Pair<String, String>?>(null) }

    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.container_health_section),
            subtitle = report?.let { overallSubtitle(it) },
            trailing = {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    TextButton(
                        onClick = {
                            running = true
                            installMessage = null
                            scope.launch {
                                report = runCatching { doctor.check() }.getOrNull()
                                running = false
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (report == null) {
                                    R.string.container_health_run
                                } else {
                                    R.string.container_health_rerun
                                }
                            )
                        )
                    }
                }
            }
        )

        report?.items?.forEach { item ->
            SettingsDivider()
            HealthItemRow(
                item = item,
                installing = installingId == item.id,
                message = installMessage?.takeIf { it.first == item.id }?.second,
                onInstall = if (item.missingTools.isEmpty()) null else {
                    {
                        installingId = item.id
                        installMessage = null
                        scope.launch {
                            val text = describeInstall(context, doctor.installMissingTools(item.missingTools))
                            installMessage = item.id to text
                            installingId = null
                            // 装完直接重测：用户关心的是「现在齐了没」，不该让他再点一次。
                            report = runCatching { doctor.check() }.getOrNull()
                        }
                    }
                }
            )
        }
    }
}

/**
 * 把安装结果转成给用户看的一句话。
 *
 * 不能做成 @Composable：它要在协程里被调用（安装是挂起操作），那个上下文取不到 stringResource。
 * 失败时带上命令末尾输出——装不上多半是源不通或包名不对，只说「失败」等于让用户去翻日志。
 */
private fun describeInstall(context: android.content.Context, outcome: InstallOutcome): String =
    when (outcome) {
        is InstallOutcome.Installed -> context.getString(
            R.string.container_health_tools_installed,
            outcome.packages.joinToString("、")
        )

        InstallOutcome.NotReady -> context.getString(R.string.container_health_busy)
        InstallOutcome.NoPackageManager ->
            context.getString(R.string.container_health_tools_no_manager)

        is InstallOutcome.Failed ->
            context.getString(R.string.container_health_tools_install_failed, outcome.output)
    }

@Composable
private fun HealthItemRow(
    item: HealthItem,
    installing: Boolean = false,
    message: String? = null,
    onInstall: (() -> Unit)? = null,
) {
    // 不用 SettingsRow 的 trailing：那个槽是整行垂直居中，副标题换行后状态点会落到
    // 两行之间，和标题对不齐。这里把点放进标题那一行，无论副标题几行都对齐。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(item.status)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(item.summaryRes, *item.summaryArgs.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.detailRes?.let {
                Text(
                    text = stringResource(it, *item.detailArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onInstall != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacing.xs)
                ) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.container_health_tools_installing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        TextButton(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.container_health_tools_install),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                // 装进的是运行中的容器，不是镜像：重写 rootfs 会把它冲掉。这点必须写明，
                // 否则用户重建镜像后发现工具又没了，只会以为功能坏了。
                Text(
                    text = stringResource(R.string.container_health_tools_install_scope),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: HealthStatus) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(statusColor(status))
    )
}

@Composable
private fun statusColor(status: HealthStatus) = when (status) {
    HealthStatus.HEALTHY -> MaterialTheme.semanticColors.success
    HealthStatus.WARNING -> MaterialTheme.semanticColors.warning
    HealthStatus.ERROR -> MaterialTheme.colorScheme.error
    // UNKNOWN 用中性灰：探测不可达不等于出错，不该和故障抢注意力。
    HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun overallSummaryRes(overall: HealthStatus): Int = when (overall) {
    HealthStatus.HEALTHY -> R.string.container_health_overall_healthy
    HealthStatus.WARNING -> R.string.container_health_overall_warning
    HealthStatus.ERROR -> R.string.container_health_overall_error
    HealthStatus.UNKNOWN -> R.string.container_health_overall_unknown
}

/** 有具体待处理项时直接给数量，比「有需要注意的项」更能说明该不该管。 */
@Composable
private fun overallSubtitle(report: HealthReport): String {
    val attentionCount = report.items.count {
        it.status == HealthStatus.ERROR || it.status == HealthStatus.WARNING
    }
    return if (attentionCount > 0) {
        stringResource(R.string.container_health_attention_count, attentionCount)
    } else {
        stringResource(overallSummaryRes(report.overall))
    }
}

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ContainerDoctorEntryPoint {
    fun containerDoctor(): ContainerDoctor
}
