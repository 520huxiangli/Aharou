package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValueimport androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.settings.presentation.MarketAlert
import com.aharou.feature.settings.presentation.MarketSkillUi

/**
 * 技能市场页（全屏二级页，与「技能详情」同一层级）：顶部粘贴仓库地址或选源，下方列出可安装的技能。
 *
 * 列表为空可能是源本身没内容、网络不通或还在加载，统一给一句提示，不区分——对用户没有可操作的区别。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillMarketSection(
    sources: List<Pair<String, String>>,
    selectedSourceId: String,
    skills: List<MarketSkillUi>,
    loading: Boolean,
    alert: MarketAlert?,
    scope: SkillScope,
    searchable: Boolean,
    onScopeChange: (SkillScope) -> Unit,
    onSelectSource: (String) -> Unit,
    onInstall: (MarketSkill) -> Unit,
    onLoadRepo: (String) -> Unit,
    onSearch: (String) -> Unit
) {
    var repoInput by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.skills_market_repo_hint),
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { onLoadRepo(repoInput) })
                )
                TextButton(
                    onClick = { onLoadRepo(repoInput) },
                    enabled = repoInput.isNotBlank() && !loading
                ) {
                    Text(stringResource(R.string.skills_market_repo_load))
                }
            }

            // 检索型源没有「列全部」的入口，得多一个关键词搜索框
            if (searchable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.skills_market_search_hint),
                        singleLine = true,
                        keyboardActions = KeyboardActions(onDone = { onSearch(query) })
                    )
                    // 检索接口要求关键词至少 2 个字，不够就置灰，省得白跑一趟
                    TextButton(
                        onClick = { onSearch(query) },
                        enabled = query.trim().length >= 2 && !loading
                    ) {
                        Text(stringResource(R.string.skills_market_search))
                    }
                }
            }

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

            // 源一多单行就排不下（横向滚动没有任何提示，新源会整个看不见），换成换行排
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                sources.forEach { (id, label) ->
                    FilterChip(
                        selected = id == selectedSourceId,
                        onClick = { onSelectSource(id) },
                        label = { Text(label) }
                    )
                }
            }

            when (alert) {
                MarketAlert.InvalidAddress -> MarketAlertText(stringResource(R.string.skills_market_repo_invalid))
                MarketAlert.NoSkills -> MarketAlertText(stringResource(R.string.skills_market_no_skills))
                MarketAlert.LoadFailed -> MarketAlertText(stringResource(R.string.skills_market_load_failed))
                MarketAlert.SearchByKeyword -> MarketAlertText(stringResource(R.string.skills_market_search_by_keyword))
                MarketAlert.SkillSiteNeedsDetail -> MarketAlertText(stringResource(R.string.skills_market_site_needs_detail))
                null -> Unit
            }
        }

        when {
            // 列表已有内容就先显示——描述还在后台补，不必等全部读完
            skills.isNotEmpty() -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(skills, key = { ui -> "${ui.skill.sourceId}@${ui.skill.repo}@${ui.skill.dir}@${ui.skill.name}" }) { ui ->
                    MarketSkillRow(ui, onInstall)
                }
                item { Spacer(modifier = Modifier.padding(bottom = Spacing.xl)) }
            }
            loading -> MarketHint(stringResource(R.string.skills_market_loading))
            else -> MarketHint(stringResource(R.string.skills_market_empty))
        }
    }
}

/** 单条市场技能：名称 + 描述 + 作者/许可，右侧是安装/更新/已安装操作。 */
@Composable
private fun MarketSkillRow(ui: MarketSkillUi, onInstall: (MarketSkill) -> Unit) {
    val skill = ui.skill
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.displayName.ifBlank { skill.name },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val meta = remember(skill) {
                listOfNotNull(
                    // 显示名与技能标识不同时，把标识也带上——调用技能/排查时认的是它
                    skill.name.takeIf { it.isNotBlank() && it != skill.displayName },
                    // 检索结果来自各个仓库，带上来源才知道装的是谁的
                    skill.repo.takeIf { skill.needsLocate && it.isNotBlank() },
                    skill.author.takeIf { it.isNotBlank() },
                    skill.version.takeIf { it.isNotBlank() },
                    skill.license.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        when {
            ui.hasUpdate -> TextButton(onClick = { onInstall(skill) }) {
                Text(stringResource(R.string.skills_market_update))
            }
            ui.installed -> Text(
                text = stringResource(R.string.skills_market_installed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
            else -> TextButton(onClick = { onInstall(skill) }) {
                Text(stringResource(R.string.skills_market_install))
            }
        }
    }
}

/** 列表区的占位提示（加载中 / 空）。 */
@Composable
private fun MarketHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 顶部的就地提示（地址不合法 / 仓库里没技能），不占列表位置。 */
@Composable
private fun MarketAlertText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.subtleText
    )
}
