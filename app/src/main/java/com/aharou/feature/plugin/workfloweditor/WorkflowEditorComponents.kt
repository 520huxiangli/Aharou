/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 *        （NodeDialog 里的下拉选择器 / 参数行 / 前置节点选择器）
 * 改动：抽成可复用的小组件；选择器不再用 ExposedDropdownMenuBox + 只读 OutlinedTextField，
 * 改成卡片式选择行，避免只读输入框在暗色主题下边框与文字一起变灰看不清；
 * 参数值与节点引用的切换逻辑保留，文案全部走 R.string。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.ParameterValue
import com.aharou.feature.plugin.workflow.WorkflowNode
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Link
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/** 区块标题：编辑器里每一段配置的头，和设置页分组标题一个调子。 */
@Composable
fun EditorSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

/** 卡片式选择行：[options] 是 value 与展示文案的配对，选中回传 value。 */
@Composable
fun EditorPickerField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value
    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true },
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.semanticColors.cardSurface,
            border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = display.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    FeatherIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(optionValue)
                    },
                )
            }
        }
    }
}

/** 一行「标签 + 开关」，编辑器里开关不少，统一排版。 */
@Composable
fun EditorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 参数值编辑器：静态值（可输入）或上游节点引用（只读显示节点名）。
 *
 * 引用态不让人手填节点 id——填错就是悬空引用，直接给节点列表点选。
 */
@Composable
fun ParameterValueEditor(
    label: String,
    value: ParameterValue,
    nodes: List<WorkflowNode>,
    excludeNodeId: String?,
    onChange: (ParameterValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isReference = value is ParameterValue.NodeReference
    val referenceId = (value as? ParameterValue.NodeReference)?.nodeId.orEmpty()
    val display = if (isReference) {
        nodes.firstOrNull { it.id == referenceId }?.name ?: referenceId
    } else {
        (value as? ParameterValue.StaticValue)?.value.orEmpty()
    }
    val candidates = nodes.filter { it.id != excludeNodeId }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AppTextField(
                value = display,
                onValueChange = { onChange(ParameterValue.StaticValue(it)) },
                label = label,
                enabled = !isReference,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isReference) {
                // 引用态要把点击吃掉，否则点进去是禁用的输入框，看着像坏了。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { expanded = true },
                )
            }
        }
        IconButton(onClick = { expanded = true }) {
            Icon(
                FeatherIcons.Link,
                contentDescription = stringResource(R.string.workflow_editor_pick_node),
                modifier = Modifier.size(18.dp),
                tint = if (isReference) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isReference) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_editor_use_static_value)) },
                    onClick = {
                        expanded = false
                        onChange(ParameterValue.StaticValue(""))
                    },
                )
            }
            if (candidates.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_editor_no_reference_nodes)) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            candidates.forEach { node ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(node.name.ifBlank { node.type })
                            Text(
                                text = node.type,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onChange(ParameterValue.NodeReference(node.id))
                    },
                )
            }
        }
    }
}

/** 追加按钮：编辑器里「再加一行」统一用这个样式。 */
@Composable
fun EditorAddRowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.mutedSurface,
    ) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FeatherIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

/** 删除一行的小按钮，空白背景避免每行都糊一块色。 */
@Composable
fun EditorDeleteIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            FeatherIcons.Trash2,
            contentDescription = stringResource(R.string.workflow_editor_delete_row),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** 键值对编辑：trigger 的自由配置项（tasker/intent/speech 等）就用这个改。 */
@Composable
fun KeyValueEditors(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppTextField(
                    value = entry.first,
                    onValueChange = { key ->
                        onChange(entries.toMutableList().also { it[index] = key to entry.second })
                    },
                    label = stringResource(R.string.workflow_editor_config_key),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = entry.second,
                    onValueChange = { value ->
                        onChange(entries.toMutableList().also { it[index] = entry.first to value })
                    },
                    label = stringResource(R.string.workflow_editor_config_value),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                EditorDeleteIcon(onClick = { onChange(entries.toMutableList().also { it.removeAt(index) }) })
            }
        }
        EditorAddRowButton(
            text = stringResource(R.string.workflow_editor_add_config),
            onClick = { onChange(entries + ("" to "")) },
        )
    }
}

/** 参数行数据：执行节点的 actionConfig 在编辑期用「键 + 是否引用 + 值」表示。 */
data class EditableParameter(
    val key: String,
    val isReference: Boolean,
    val value: String,
) {
    fun toParameterValue(): ParameterValue =
        if (isReference) ParameterValue.NodeReference(value) else ParameterValue.StaticValue(value)

    companion object {
        fun from(key: String, value: ParameterValue): EditableParameter = when (value) {
            is ParameterValue.NodeReference -> EditableParameter(key, true, value.nodeId)
            is ParameterValue.StaticValue -> EditableParameter(key, false, value.value)
            is ParameterValue.Unsupported -> EditableParameter(key, false, value.toString())
        }
    }
}

/** 参数编辑行：参数名 + 值（静态/引用）+ 删除。 */
@Composable
fun EditableParameterRow(
    parameter: EditableParameter,
    nodes: List<WorkflowNode>,
    excludeNodeId: String?,
    onChange: (EditableParameter) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AppTextField(
                value = parameter.key,
                onValueChange = { onChange(parameter.copy(key = it)) },
                label = stringResource(R.string.workflow_editor_param_name),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ParameterValueEditor(
                label = stringResource(R.string.workflow_editor_param_value),
                value = parameter.toParameterValue(),
                nodes = nodes,
                excludeNodeId = excludeNodeId,
                onChange = { value ->
                    onChange(
                        when (value) {
                            is ParameterValue.NodeReference -> parameter.copy(isReference = true, value = value.nodeId)
                            is ParameterValue.StaticValue -> parameter.copy(isReference = false, value = value.value)
                            is ParameterValue.Unsupported -> parameter
                        }
                    )
                },
                modifier = Modifier.weight(1.4f),
            )
            EditorDeleteIcon(onClick = onRemove)
        }
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

@Composable
fun workflowEditorMessageText(message: WorkflowEditorMessage): String = when (message) {
    is WorkflowEditorMessage.Saved -> if (message.issueCount == 0) {
        stringResource(R.string.workflow_editor_saved)
    } else {
        stringResource(R.string.workflow_editor_saved_with_issues, message.issueCount)
    }
    is WorkflowEditorMessage.RunFinished -> if (message.success) {
        stringResource(R.string.workflow_editor_run_success)
    } else {
        stringResource(R.string.workflow_editor_run_failed, message.detail)
    }
    is WorkflowEditorMessage.Failed -> message.detail.ifBlank {
        stringResource(R.string.workflow_editor_operation_failed)
    }
}

/** 小圆点，用来在列表里标运行状态。 */
@Composable
fun EditorStatusDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(color, RoundedCornerShape(Radius.pill)),
    )
}
