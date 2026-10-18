package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing

/**
 * 容器页顶部的「镜像与 DNS」入口。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮——那边每加一行都得从别处省一行。
 */
@Composable
internal fun ContainerSourcesEntry(onOpen: () -> Unit) {
    SettingsGroup {
        SettingsRow(title = stringResource(R.string.container_sources_title), onClick = onOpen)
    }
    Spacer(Modifier.height(Spacing.md))
}
