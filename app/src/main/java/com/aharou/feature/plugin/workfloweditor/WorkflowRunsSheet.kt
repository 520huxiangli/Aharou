/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 *        （WorkflowExecutionLogDialog）
 * 改动：日志来源从 Operit 的执行记录表换成 Aharou WorkflowStore 的追加式运行记录
 * （WorkflowRunRecord），只展示排障必需的时间/结果/失败节点，不搬它的日志级别模型。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.plugin.workflow.WorkflowRunRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowRunsSheet(
    runs: List<WorkflowRunRecord>,
    onDismiss: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.workflow_editor_runs_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (runs.isEmpty()) {
                Text(
                    text = stringResource(R.string.workflow_editor_runs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    runs.asReversed().forEach { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            EditorStatusDot(
                                color = if (record.success) MaterialTheme.semanticColors.success
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 5.dp, end = Spacing.sm),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatter.format(Date(record.startedAt)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = record.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (record.failedNodes.isNotEmpty()) {
                                    Text(
                                        text = stringResource(
                                            R.string.workflow_editor_runs_failed_nodes,
                                            record.failedNodes.joinToString(", "),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
