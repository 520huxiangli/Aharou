package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.plugin.data.import.OperitCompatLevel
import com.aharou.feature.plugin.data.import.OperitImportReport
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup

/** 导入页最多逐条列出的能力映射数，其余折成一句。 */
private const val MAX_LISTED_MAPPINGS = 8

/**
 * Operit 包的能力对照：能对上的显示宿主方法，对不上的在末尾汇总。
 * 只是提示，安装按钮不受影响；完整结论同时写进包内的 `.operit-origin.json`。
 */
@Composable
internal fun OperitCompatRows(report: OperitImportReport) {
    val compat = report.compat
    val mappings = (compat.available + compat.degraded).filter { it.replacement != null }
    if (mappings.isEmpty() && compat.unavailable.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsGroup {
            mappings.take(MAX_LISTED_MAPPINGS).forEachIndexed { index, finding ->
                if (index > 0) SettingsDivider()
                CompatRow(
                    marker = if (finding.level == OperitCompatLevel.AVAILABLE) "✓" else "~",
                    call = finding.call,
                    replacement = finding.replacement
                )
            }
            val hidden = mappings.size - MAX_LISTED_MAPPINGS
            if (hidden > 0) {
                if (mappings.isNotEmpty()) SettingsDivider()
                Text(
                    text = stringResource(R.string.plugins_import_failed_more, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 8.dp)
                )
            }
            if (compat.unavailable.isNotEmpty()) {
                SettingsDivider()
                Text(
                    text = stringResource(
                        R.string.plugins_error_unsupported_contribution,
                        compat.unavailable.joinToString(", ") { it.call }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CompatRow(marker: String, call: String, replacement: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = if (marker == "✓") MaterialTheme.semanticColors.success else MaterialTheme.semanticColors.warning
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = call,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (replacement != null) {
            Text(
                text = "→ $replacement",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
