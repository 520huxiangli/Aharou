package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.setValue
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
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.agent.domain.skill.market.SkillSafety
import com.aharou.feature.agent.domain.skill.market.SkillTranslation
import com.aharou.feature.agent.domain.skill.market.translationKey
import com.aharou.feature.settings.presentation.MarketAlert
import com.aharou.feature.settings.presentation.MarketSkillUi

/**
 * 技能市场页（全屏二级页，与「技能详情」同一层级）：顶部粘贴仓库地址或选源，下方列出可安装的技能。
 *
 * 列表为空可能是源本身没内容、网络不通或还在加载，统一给一句提示，不区分——对用户没有可操作的区别。
 * 列表滚动位置由外部传入的 [listState] 持有，这样进详情页再返回时不会跳回第一条。
 * 仓库只给英文的条目由后台翻译（[translations]），翻好一批就换一批中文。
 */
@Composable
internal fun SkillMarketSection(
    sources: List<Pair<String, String>>,
    selectedSourceId: String,
    skills: List<MarketSkillUi>,
    translations: Map<String, SkillTranslation>,
    loading: Boolean,
    alert: MarketAlert?,
    listState: LazyListState,
    onSelectSource: (String) -> Unit,
    onInstall: (MarketSkill) -> Unit,
    onLoadRepo: (String) -> Unit,
    onSearch: (String, String) -> Unit,
    onOpenDetail: (MarketSkill) -> Unit
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
            // 源多了一行排不下就左右滑（保持单行，别占列表的竖向空间）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                sources.forEach { (id, label) ->
                    FilterChip(
                        selected = id == selectedSourceId,
                        onClick = { onSelectSource(id) },
                        label = { Text(label) }
                    )
                }
            }

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

            // 两个框都常驻：上面填网页地址（可单独点「加载」），下面填关键词。
            // 地址框填了内容时，搜索就在那个仓库里筛；空着才走全网检索。
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
                    keyboardActions = KeyboardActions(onDone = { onSearch(query, repoInput) })
                )
                // 检索接口要求关键词至少 2 个字，不够就置灰，省得白跑一趟
                TextButton(
                    onClick = { onSearch(query, repoInput) },
                    enabled = query.trim().length >= 2 && !loading
                ) {
                    Text(stringResource(R.string.skills_market_search))
                }
            }

            when (alert) {
                MarketAlert.InvalidAddress -> MarketAlertText(stringResource(R.string.skills_market_repo_invalid))
                MarketAlert.NoSkills -> MarketAlertText(stringResource(R.string.skills_market_no_skills))
                MarketAlert.NoMatchInRepo -> MarketAlertText(stringResource(R.string.skills_market_no_match_in_repo))
                MarketAlert.LoadFailed -> MarketAlertText(stringResource(R.string.skills_market_load_failed))
                MarketAlert.MarketNotLoaded -> MarketAlertText(stringResource(R.string.skills_market_not_loaded))
                MarketAlert.TranslateUnavailable ->
                    MarketAlertText(stringResource(R.string.skills_market_translate_unavailable))
                MarketAlert.SkillSiteNeedsDetail -> MarketAlertText(stringResource(R.string.skills_market_site_needs_detail))
                null -> Unit
            }
        }

        when {
            // 列表已有内容就先显示——描述还在后台补，不必等全部读完
            skills.isNotEmpty() -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(skills, key = { ui -> "${ui.skill.sourceId}@${ui.skill.repo}@${ui.skill.dir}@${ui.skill.name}" }) { ui ->
                    MarketSkillRow(ui, translations, onInstall, onOpenDetail)
                }
                item { Spacer(modifier = Modifier.padding(bottom = Spacing.xl)) }
            }
            loading -> MarketHint(stringResource(R.string.skills_market_loading))
            else -> MarketHint(stringResource(R.string.skills_market_empty))
        }
    }
}

/** 单条市场技能：名称 + 描述 + 作者/许可/安装量/风险标记，右侧是安装/更新/已安装操作。整行可点进详情。 */
@Composable
private fun MarketSkillRow(
    ui: MarketSkillUi,
    translations: Map<String, SkillTranslation>,
    onInstall: (MarketSkill) -> Unit,
    onOpenDetail: (MarketSkill) -> Unit
) {
    val skill = ui.skill
    // 仓库只给英文的条目：模型翻好后标题与描述都换成中文（英文原名留在下面那行小字里）
    val translation = translations[skill.translationKey()]
    val title = translation?.name?.takeIf { it.isNotBlank() }
        ?: skill.displayName.ifBlank { skill.name }
    val description = translation?.description?.takeIf { it.isNotBlank() } ?: skill.description
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(10.dp))
            .clickable { onOpenDetail(skill) }
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
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
                    skill.license.takeIf { it.isNotBlank() },
                    skill.installs.takeIf { it > 0 }?.let { formatInstalls(it) }
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
            // 风险提示只报「有什么」，不报「危不危险」；列表里给一行浅字，详情页再展开说
            val risk = skill.safety?.let { safetyHint(it) }
            if (risk != null) {
                Text(
                    text = risk,
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

/** 安装量折成 1.2k / 10.5k，免得长数字把一整行挤满。 */
private fun formatInstalls(count: Long): String =
    if (count >= 1000) "${count / 1000}.${(count % 1000) / 100}k" else count.toString()

/** 列表上的风险概要；没得说的返回 null。 */
@Composable
private fun safetyHint(safety: SkillSafety): String? {
    val parts = buildList {
        if (safety.scripts.isNotEmpty()) {
            add(stringResource(R.string.skills_market_risk_scripts, safety.scripts.size))
        }
        if (safety.needsCredentials) add(stringResource(R.string.skills_market_risk_credentials))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
