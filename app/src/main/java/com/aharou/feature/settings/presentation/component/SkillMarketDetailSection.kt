package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.agent.domain.skill.market.SkillSafety
import com.aharou.feature.agent.domain.skill.market.SkillTranslation
import com.aharou.feature.agent.domain.skill.market.translationKey
import com.aharou.feature.settings.presentation.MarketDetailUi

/**
 * 技能市场的详情页：装之前先看清是什么。
 *
 * 内容全部来自源里那份 SKILL.md 与文件清单，没有安装逻辑之外的动作——这里不执行、不下载整包，
 * 所以「风险提示」只列看得见的事实，不替用户判断安不安全。
 */
@Composable
internal fun SkillMarketDetailSection(
    state: MarketDetailUi,
    translations: Map<String, SkillTranslation>,
    scope: SkillScope,
    onScopeChange: (SkillScope) -> Unit,
    onInstall: (MarketSkill) -> Unit
) {
    val skill = state.ui.skill
    val detail = state.detail
    val safety = detail?.safety
    // 与列表页一致：仓库没给中文的条目显示模型译文（英文标识仍留在下面那行小字）
    val translation = translations[skill.translationKey()]
    val title = translation?.name?.takeIf { it.isNotBlank() }
        ?: skill.displayName.ifBlank { skill.name }
    val description = translation?.description?.takeIf { it.isNotBlank() } ?: skill.description

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (skill.name.isNotBlank() && skill.name != skill.displayName) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingsGroup {
                DetailRow(stringResource(R.string.skills_market_detail_source), skill.repo)
                if (skill.author.isNotBlank()) {
                    SettingsDivider()
                    DetailRow(stringResource(R.string.skills_market_detail_author), skill.author)
                }
                if (skill.version.isNotBlank()) {
                    SettingsDivider()
                    DetailRow(stringResource(R.string.skills_market_detail_version), skill.version)
                }
                if (skill.license.isNotBlank()) {
                    SettingsDivider()
                    DetailRow(stringResource(R.string.skills_market_detail_license), skill.license)
                }
                if (skill.installs > 0) {
                    SettingsDivider()
                    DetailRow(
                        stringResource(R.string.skills_market_detail_installs),
                        stringResource(R.string.skills_market_detail_installs_value, skill.installs)
                    )
                }
            }
        }

        if (safety != null && (safety.scripts.isNotEmpty() || safety.needsCredentials)) {
            item { SafetyCard(safety) }
        }

        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.skills_market_detail_content),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    when {
                        state.loading -> Text(
                            text = stringResource(R.string.skills_market_detail_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                        // 拿得到就整篇可选中复制——用户常要把里面的命令抄出去
                        detail?.skillMd != null -> SelectionContainer {
                            Text(
                                text = detail.skillMd,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> Text(
                            text = stringResource(R.string.skills_market_detail_no_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                }
            }
        }

        if (detail != null && detail.files.isNotEmpty()) {
            item { FilesCard(detail.files) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                Button(
                    onClick = { onInstall(skill) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            state.ui.hasUpdate -> stringResource(R.string.skills_market_detail_update)
                            state.ui.installed -> stringResource(R.string.skills_market_detail_reinstall)
                            else -> stringResource(R.string.skills_market_detail_install)
                        }
                    )
                }
                Spacer(modifier = Modifier.padding(bottom = Spacing.lg))
            }
        }
    }
}

/** 风险提示卡：只列事实（带哪些脚本、要不要凭据），不说「安全 / 不安全」。 */
@Composable
private fun SafetyCard(safety: SkillSafety) {
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.skills_market_risk_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
            if (safety.scripts.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.skills_market_risk_scripts_detail,
                        safety.scripts.take(10).joinToString("、")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (safety.needsCredentials) {
                Text(
                    text = stringResource(R.string.skills_market_risk_credentials_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 文件清单卡：只列前若干个，剩下的给一句总数，免得长清单把页面撑到几屏。 */
@Composable
private fun FilesCard(files: List<String>) {
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.skills_market_detail_files, files.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            files.take(FILE_PREVIEW_LIMIT).forEach { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (files.size > FILE_PREVIEW_LIMIT) {
                Text(
                    text = stringResource(
                        R.string.skills_market_detail_files_more,
                        files.size - FILE_PREVIEW_LIMIT
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        }
    }
}

/** 一行「标签 — 值」，值右对齐，过长折两行。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val FILE_PREVIEW_LIMIT = 30
