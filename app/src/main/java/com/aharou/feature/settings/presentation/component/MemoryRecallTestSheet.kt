package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.MemoryRecallHit
import com.aharou.feature.settings.presentation.MemoryRecallReport

/**
 * 记忆检索测试弹层：输入一句话或一个域名，看它会召回哪些记忆摘要、各靠哪个字段命中。
 *
 * 只读预览——结果来自注入清单本身（见 MemorySettingsController.testRecall），
 * 用户据此判断「摘要里该补什么实体才 Recall 得到」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryRecallTestSheet(
    testing: Boolean,
    report: MemoryRecallReport?,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.memory_recall_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.memory_recall_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = query,
                onValueChange = { if (!testing) query = it },
                label = stringResource(R.string.memory_recall_input_label),
                placeholder = stringResource(R.string.memory_recall_input_placeholder),
                enabled = !testing,
                singleLine = true,
            )
            Button(
                onClick = { onRun(query) },
                enabled = !testing && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.memory_recall_running else R.string.memory_recall_run
                    )
                )
            }
            if (report != null) {
                RecallReportBody(report)
            }
        }
    }
}

@Composable
private fun RecallReportBody(report: MemoryRecallReport) {
    if (report.items.isEmpty()) {
        RecallNotice(stringResource(R.string.memory_recall_no_memory))
        return
    }
    // 一条都没命中时先说清楚「该去哪儿补」：清单会退化到按最近修改排序，
    // 这些条目技术上仍会注入，但对当前这句话没有帮助。
    if (!report.hasHit) {
        RecallNotice(stringResource(R.string.memory_recall_no_hit))
    }
    Text(
        text = stringResource(R.string.memory_recall_result_count, report.items.size),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsGroup {
        report.items.forEachIndexed { index, hit ->
            if (index > 0) SettingsDivider()
            RecallRow(hit)
        }
    }
}

@Composable
private fun RecallNotice(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecallRow(hit: MemoryRecallHit) {
    val memory = hit.memory
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = memory.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(
                    if (memory.scope == MemoryScope.PROJECT) R.string.memory_scope_project
                    else R.string.memory_scope_global
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Text(
            text = memory.description.ifBlank { stringResource(R.string.memory_entry_no_description) },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (hit.hitKeywords) {
                RecallFieldLabel(stringResource(R.string.memory_recall_field_keywords), hit = true)
            }
            if (hit.hitName) {
                RecallFieldLabel(stringResource(R.string.memory_recall_field_name), hit = true)
            }
            if (hit.hitDescription) {
                RecallFieldLabel(stringResource(R.string.memory_recall_field_description), hit = true)
            }
            if (!hit.matched) {
                RecallFieldLabel(stringResource(R.string.memory_recall_field_none), hit = false)
            }
            if (hit.inlined) {
                RecallFieldLabel(stringResource(R.string.memory_recall_field_inlined), hit = true)
            }
        }
    }
}

@Composable
private fun RecallFieldLabel(label: String, hit: Boolean) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = if (hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
