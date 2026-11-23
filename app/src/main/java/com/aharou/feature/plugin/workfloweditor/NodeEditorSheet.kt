/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 *        （NodeDialog）
 * 改动：
 * - 从 AlertDialog 改成 AdaptiveModalBottomSheet，长表单在手机上更好滚，平板自动收成居中弹窗；
 * - 节点名称/描述与各类型配置统一放在一个可编辑副本里，取消即丢弃，不半途写回工作流；
 * - 类型不可改（新建时就定了），去掉上游创建模式的类型下拉；
 * - 文案全部走 R.string，工具候选来自 Aharou 的 ToolRegistry。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.plugin.workflow.ConditionNode
import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.LogicNode
import com.aharou.feature.plugin.workflow.PlaceholderNode
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.WorkflowNode

/**
 * 节点属性编辑底栏。
 *
 * [node] 必须是已存在于工作流里的节点：新建由 ViewModel 先落默认值再选中，编辑期只有「修改」一条路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowNodeEditorSheet(
    node: WorkflowNode,
    allNodes: List<WorkflowNode>,
    toolNames: List<String>,
    toolLookup: (String) -> AgentTool?,
    onDismiss: () -> Unit,
    onSubmit: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(node.id) { mutableStateOf(node) }
    var confirmDelete by remember { mutableStateOf(false) }

        AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.workflow_editor_node_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AppTextField(
                value = editing.name,
                onValueChange = { value -> editing = editing.withBasics(name = value) },
                label = stringResource(R.string.workflow_editor_node_name),
                singleLine = true,
            )
            AppTextField(
                value = editing.description,
                onValueChange = { value -> editing = editing.withBasics(description = value) },
                label = stringResource(R.string.workflow_editor_node_description),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )

            when (val current = editing) {
                is TriggerNode -> TriggerNodeSection(
                    trigger = current,
                    onChange = { editing = it },
                )
                is ExecuteNode -> ExecuteNodeSection(
                    execute = current,
                    nodes = allNodes,
                    toolNames = toolNames,
                    toolLookup = toolLookup,
                    onChange = { editing = it },
                )
                is ExtractNode -> ExtractNodeSection(
                    extract = current,
                    nodes = allNodes,
                    onChange = { editing = it },
                )
                is ConditionNode -> ConditionNodeSection(
                    condition = current,
                    nodes = allNodes,
                    onChange = { editing = it },
                )
                is LogicNode -> LogicNodeSection(
                    logic = current,
                    onChange = { editing = it },
                )
                is PlaceholderNode -> Text(
                    text = stringResource(R.string.workflow_editor_node_unknown_hint, current.originalType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        text = stringResource(R.string.workflow_editor_delete_node),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = { onSubmit(editing) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.workflow_editor_save_node))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.workflow_editor_delete_node)) },
            text = { Text(stringResource(R.string.workflow_editor_delete_node_confirm, node.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.workflow_editor_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.workflow_editor_cancel))
                }
            },
        )
    }
}

/** 名称/描述是五种节点共有的字段，逐个分支 copy 一遍比给模型加接口面更省事。 */
private fun WorkflowNode.withBasics(name: String = this.name, description: String = this.description): WorkflowNode =
    when (this) {
        is TriggerNode -> copy(name = name, description = description)
        is ExecuteNode -> copy(name = name, description = description)
        is ExtractNode -> copy(name = name, description = description)
        is ConditionNode -> copy(name = name, description = description)
        is LogicNode -> copy(name = name, description = description)
        is PlaceholderNode -> this
    }
