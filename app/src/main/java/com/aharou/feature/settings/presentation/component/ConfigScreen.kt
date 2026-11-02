package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.GlassSurface
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.FileText
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Users

/** 配置页的一个入口：图标 + 标题 + 一句话说明 + 点击后落到设置页的哪个分区。 */
private data class ConfigEntry(
    val icon: ImageVector,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    val section: SettingsSection
)

private val ConfigEntries = listOf(
    ConfigEntry(FeatherIcons.BookOpen, R.string.memory_settings_title, R.string.config_memory_desc, SettingsSection.Memory),
    ConfigEntry(FeatherIcons.Archive, R.string.settings_skills, R.string.config_skills_desc, SettingsSection.Skills),
    ConfigEntry(FeatherIcons.HardDrive, R.string.settings_knowledge_base, R.string.config_knowledge_desc, SettingsSection.Knowledge),
    ConfigEntry(FeatherIcons.Users, R.string.settings_teams, R.string.config_teams_desc, SettingsSection.Teams),
    ConfigEntry(FeatherIcons.MessageSquare, R.string.prompts_title, R.string.config_prompts_desc, SettingsSection.Prompts),
    ConfigEntry(FeatherIcons.FileText, R.string.rules_title, R.string.config_rules_desc, SettingsSection.Rules)
)

/**
 * 底栏「配置」页：只放 AI 的能力与资产入口（记忆 / 技能 / 知识库 / 团队 / 提示词 / 规则）。
 *
 * 点进去复用设置页的既有分区页（[SettingsScreen]），不在这里重做一套编辑界面——
 * 两边各写一份的话，改一处忘另一处。
 */
@Composable
fun ConfigScreen(
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.config_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = Spacing.lg)
        )
        Text(
            text = stringResource(R.string.config_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            modifier = Modifier.fillMaxSize()
        ) {
            items(ConfigEntries, key = { it.section.name }) { entry ->
                ConfigCard(entry = entry, onClick = { onOpenSection(entry.section) })
            }
        }
    }
}

@Composable
private fun ConfigCard(entry: ConfigEntry, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.xl),
        shadowElevation = 6.dp,
        contentPadding = PaddingValues(Spacing.md)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(entry.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(entry.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
