package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.settings.data.repository.ProotSeccompMode
import com.aharou.feature.settings.presentation.ContainerSeccompViewModel

/**
 * 容器页的「PRoot 兼容模式」三选一。
 *
 * 陌生 ROM 上 proot 可能被 seccomp 卡住、容器里一条命令都跑不起来；用户在这里能手动指定
 * 要不要退回全量 ptrace。默认「自动」，正常设备不该被这个开关影响到。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮，与
 * [ContainerGradleHeapSection]、[ContainerEnvRedactionSection] 同样处理。
 */
@Composable
internal fun ContainerSeccompSection(
    viewModel: ContainerSeccompViewModel = hiltViewModel()
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val options = ProotSeccompMode.entries

    Spacer(Modifier.height(Spacing.md))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.container_seccomp_mode_title),
            subtitle = stringResource(R.string.container_seccomp_mode_summary),
        )
        // 用项目分段控件而不是单选按钮：三项互斥、切换即生效，与容器页「本地/远程」同一手感。
        SegmentedTabs(
            selected = options.indexOf(mode).coerceAtLeast(0),
            labels = listOf(
                stringResource(R.string.container_seccomp_mode_auto),
                stringResource(R.string.container_seccomp_mode_force_default),
                stringResource(R.string.container_seccomp_mode_compat),
            ),
            onSelect = { index -> options.getOrNull(index)?.let(viewModel::setMode) },
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
    Spacer(Modifier.height(Spacing.md))
}
