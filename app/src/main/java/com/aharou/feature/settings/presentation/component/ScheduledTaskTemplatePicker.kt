package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.data.ScheduledTaskTemplate
import com.aharou.feature.agent.data.ScheduledTaskTemplateCategory
import com.aharou.feature.agent.data.ScheduledTaskTemplates
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.data.toSeedEntity
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Zap

/*
 * 「从模板创建」入口与模板选择器。
 *
 * 单独成文件是因为 ScheduledTasksSection.kt 已贴近架构门禁的单文件行数上限（1000 行），
 * 新增 UI 一律放这里，那边只留一次调用。
 */

/**
 * 任务列表里的「从模板创建」一行：自己管弹层显隐，选中后把预填用的实体交给调用方去开编辑器。
 */
@Composable
internal fun ScheduledTaskTemplateEntry(onTemplatePicked: (ScheduledTaskEntity) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    SettingsDivider()
    SettingsRow(
        icon = FeatherIcons.FileText,
        title = stringResource(R.string.scheduled_tasks_from_template),
        subtitle = stringResource(R.string.scheduled_tasks_from_template_hint),
        onClick = { picking = true }
    )
    if (picking) {
        ScheduledTaskTemplateSheet(
            onDismiss = { picking = false },
            onPick = { seed ->
                picking = false
                onTemplatePicked(seed)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTaskTemplateSheet(
    onDismiss: () -> Unit,
    onPick: (ScheduledTaskEntity) -> Unit
) {
    // 主题只被带 {{topic}} 的模板用；默认填模板给的示例主题，用户清空就落回它。
    val topicTemplate = remember { ScheduledTaskTemplates.all.firstOrNull { it.topicHintRes != null } }
    var topic by remember { mutableStateOf(topicTemplate?.topicDefault.orEmpty()) }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.scheduled_tasks_templates_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.md)
            )
            Text(
                text = stringResource(R.string.scheduled_tasks_templates_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ScheduledTaskTemplateCategory.entries.forEach { category ->
                val inCategory = ScheduledTaskTemplates.all.filter { it.category == category }
                SettingsGroupHeader(text = stringResource(category.labelRes()))
                // 主题框摆在对应分类之前：先填主题再点条目，点下去那一刻替换掉 {{topic}}。
                if (topicTemplate != null && topicTemplate.category == category) {
                    AppTextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = stringResource(R.string.scheduled_tasks_template_topic),
                        supportingText = {
                            topicTemplate.topicHintRes?.let { Text(stringResource(it)) }
                        }
                    )
                }
                SettingsGroup {
                    inCategory.forEachIndexed { index, template ->
                        val title = stringResource(template.nameRes)
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = category.icon,
                            title = title,
                            subtitle = stringResource(template.descriptionRes),
                            onClick = { onPick(template.toSeedEntity(title, template.renderedPrompt(topic))) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把模板指令里的 [ScheduledTaskTemplates.TOPIC_PLACEHOLDER] 换成用户填的主题。
 *
 * 运行期没有 topic 占位符（那套只有事件触发的 `{{event.*}}`），所以必须在建任务前换掉，
 * 否则那个标记会原样留在指令里发给模型。
 */
private fun ScheduledTaskTemplate.renderedPrompt(topic: String): String =
    prompt.replace(
        ScheduledTaskTemplates.TOPIC_PLACEHOLDER,
        topic.trim().ifEmpty { topicDefault }
    )

private val ScheduledTaskTemplateCategory.icon: ImageVector
    get() = when (this) {
        ScheduledTaskTemplateCategory.DEVELOPMENT -> FeatherIcons.Zap
        ScheduledTaskTemplateCategory.RADAR -> FeatherIcons.Globe
        ScheduledTaskTemplateCategory.DOCUMENT -> FeatherIcons.FileText
    }

@StringRes
private fun ScheduledTaskTemplateCategory.labelRes(): Int = when (this) {
    ScheduledTaskTemplateCategory.DEVELOPMENT -> R.string.scheduled_tasks_template_category_dev
    ScheduledTaskTemplateCategory.RADAR -> R.string.scheduled_tasks_template_category_radar
    ScheduledTaskTemplateCategory.DOCUMENT -> R.string.scheduled_tasks_template_category_doc
}
