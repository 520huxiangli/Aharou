/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/components/ConnectionMenu.kt
 *        （ConnectionConditionDialog 部分）
 * 改动：
 * - 只保留「改条件 / 删连线」；新建连线在画布上拖出来，不必再开一层选目标节点的弹窗；
 * - 选项直接对齐 Aharou WorkflowExecutor 认的条件值（on_success / on_error / true / false / 正则），
 *   不再有上游那种「默认 / false / 自定义」三选一的间接表达。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.WorkflowNode
import com.aharou.feature.plugin.workflow.WorkflowNodeConnection

private const val CONDITION_DEFAULT = ""
private const val CONDITION_SUCCESS = "on_success"
private const val CONDITION_ERROR = "on_error"
private const val CONDITION_TRUE = "true"
private const val CONDITION_FALSE = "false"
private const val CONDITION_CUSTOM = "__custom__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowConnectionEditorSheet(
    connection: WorkflowNodeConnection,
    nodes: List<WorkflowNode>,
    onDismiss: () -> Unit,
    onSubmit: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    val source = nodes.firstOrNull { it.id == connection.sourceNodeId }
    val target = nodes.firstOrNull { it.id == connection.targetNodeId }
    val preset = when (connection.condition?.trim().orEmpty()) {
        CONDITION_DEFAULT -> CONDITION_DEFAULT
        CONDITION_SUCCESS -> CONDITION_SUCCESS
        CONDITION_ERROR -> CONDITION_ERROR
        CONDITION_TRUE -> CONDITION_TRUE
        CONDITION_FALSE -> CONDITION_FALSE
        else -> CONDITION_CUSTOM
    }
    var mode by remember(connection.id) { mutableStateOf(preset) }
    var custom by remember(connection.id) {
        mutableStateOf(if (preset == CONDITION_CUSTOM) connection.condition.orEmpty() else "")
    }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.workflow_editor_connection_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.workflow_editor_connection_between,
                    source?.name.orEmpty(),
                    target?.name.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorPickerField(
                label = stringResource(R.string.workflow_editor_connection_condition),
                value = mode,
                options = listOf(
                    CONDITION_DEFAULT to stringResource(R.string.workflow_editor_condition_default),
                    CONDITION_SUCCESS to stringResource(R.string.workflow_editor_condition_success),
                    CONDITION_ERROR to stringResource(R.string.workflow_editor_condition_error),
                    CONDITION_TRUE to stringResource(R.string.workflow_editor_condition_true),
                    CONDITION_FALSE to stringResource(R.string.workflow_editor_condition_false),
                    CONDITION_CUSTOM to stringResource(R.string.workflow_editor_condition_custom),
                ),
                onSelect = { mode = it },
            )
            if (mode == CONDITION_CUSTOM) {
                AppTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = stringResource(R.string.workflow_editor_connection_regex),
                    placeholder = "(?i)done",
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.workflow_editor_connection_regex_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TextButton(onClick = { onDelete() }) {
                    Text(
                        text = stringResource(R.string.workflow_editor_delete_connection),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = {
                        val value = when (mode) {
                            CONDITION_DEFAULT -> null
                            CONDITION_CUSTOM -> custom.trim().ifBlank { null }
                            else -> mode
                        }
                        onSubmit(value)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.workflow_editor_save_node))
                }
            }
        }
    }
}
