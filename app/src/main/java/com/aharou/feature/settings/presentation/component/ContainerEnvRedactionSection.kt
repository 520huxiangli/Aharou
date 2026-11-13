package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.settings.presentation.ContainerEnvRedactionViewModel

/**
 * 容器页的「命令输出隐藏环境变量值」开关。
 *
 * 与 [EnvVarsSection] 的分工：那页管「往容器里注入哪些变量」，这一行只决定命令输出里要不要把它们
 * 的值抹掉——用户排查输出为什么变短时，不用先绕去环境变量页才找到开关。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮，与 [ContainerGradleHeapSection] 同样处理。
 */
@Composable
internal fun ContainerEnvRedactionSection(
    viewModel: ContainerEnvRedactionViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()

    Spacer(Modifier.height(Spacing.md))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.settings_container_env_redaction_title),
            subtitle = stringResource(R.string.settings_container_env_redaction_desc),
            trailing = {
                AppSwitch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }
        )
    }
    Spacer(Modifier.height(Spacing.md))
}
