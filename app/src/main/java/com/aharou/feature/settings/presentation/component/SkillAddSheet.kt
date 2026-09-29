package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.skill.SkillScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText

/**
 * 「添加技能」底部弹层：顶部选择作用域（全局 / 当前项目），下方四种添加方式——
 * 手动新建（进编辑表单）、从文件导入（.md）、从压缩包导入（.zip，可含多个技能）、从市场安装。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SkillAddSheet(
    scope: SkillScope,
    onScopeChange: (SkillScope) -> Unit,
    onManual: () -> Unit,
    onPickFile: () -> Unit,
    onPickZip: () -> Unit,
    onMarket: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.skills_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.skills_editor_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = scope == SkillScope.GLOBAL,
                    onClick = { onScopeChange(SkillScope.GLOBAL) },
                    label = { Text(stringResource(R.string.skills_scope_global)) }
                )
                FilterChip(
                    selected = scope == SkillScope.PROJECT,
                    onClick = { onScopeChange(SkillScope.PROJECT) },
                    label = { Text(stringResource(R.string.skills_scope_project)) }
                )
            }

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Edit3,
                    title = stringResource(R.string.skills_add_manual),
                    subtitle = stringResource(R.string.skills_add_manual_desc),
                    onClick = onManual
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.skills_add_from_file),
                    subtitle = stringResource(R.string.skills_add_from_file_desc),
                    onClick = onPickFile
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Archive,
                    title = stringResource(R.string.skills_add_from_zip),
                    subtitle = stringResource(R.string.skills_add_from_zip_desc),
                    onClick = onPickZip
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Download,
                    title = stringResource(R.string.skills_add_from_market),
                    subtitle = stringResource(R.string.skills_add_from_market_desc),
                    onClick = onMarket
                )
            }
        }
    }
}
