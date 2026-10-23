package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing

/**
 * 「数字助理」独立页面：状态、一键设置、屏幕数据权限、两个隐私开关都在这里。
 *
 * 从「AI 配置 → 默认模型」里的一行入口进来。单独一页是因为这一块要跟系统来回打交道
 * （换助手、开屏幕权限都要回本页看结果），挤在模型列表里既看不清状态也说不清怎么修。
 */
@Composable
internal fun AssistantDigitalScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.assistant_digital_page_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs)
        )
        Spacer(Modifier.height(Spacing.md))
        SettingsGroup {
            AssistantDigitalSection()
        }
    }
}
