/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 *        （NodeDialog 的 condition / logic / extract 分支）
 * 改动：
 * - 条件比较与提取都直接复用 Aharou 的数据模型字段，不再经过上游那套显示枚举的翻译层；
 * - 数值字段用本地文本态承载，输入到一半（空串）不立刻回写 0，避免光标乱跳；
 * - CONCAT 的每一项都支持静态值/上游节点引用，与执行器的取值语义一致。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.ConditionNode
import com.aharou.feature.plugin.workflow.ConditionOperator
import com.aharou.feature.plugin.workflow.ExtractMode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.LogicNode
import com.aharou.feature.plugin.workflow.LogicOperator
import com.aharou.feature.plugin.workflow.ParameterValue
import com.aharou.feature.plugin.workflow.WorkflowNode

@Composable
fun ConditionNodeSection(
    condition: ConditionNode,
    nodes: List<WorkflowNode>,
    onChange: (ConditionNode) -> Unit,
) {
    EditorSectionTitle(stringResource(R.string.workflow_editor_condition_section))
    ParameterValueEditor(
        label = stringResource(R.string.workflow_editor_condition_left),
        value = condition.left,
        nodes = nodes,
        excludeNodeId = condition.id,
        onChange = { onChange(condition.copy(left = it)) },
    )
    EditorPickerField(
        label = stringResource(R.string.workflow_editor_condition_operator),
        value = condition.operator.name,
        options = ConditionOperator.values().map { it.name to operatorLabel(it) },
        onSelect = { name ->
            ConditionOperator.values().firstOrNull { it.name == name }?.let { onChange(condition.copy(operator = it)) }
        },
    )
    ParameterValueEditor(
        label = stringResource(R.string.workflow_editor_condition_right),
        value = condition.right,
        nodes = nodes,
        excludeNodeId = condition.id,
        onChange = { onChange(condition.copy(right = it)) },
    )
}

@Composable
fun LogicNodeSection(
    logic: LogicNode,
    onChange: (LogicNode) -> Unit,
) {
    EditorSectionTitle(stringResource(R.string.workflow_editor_logic_section))
    EditorPickerField(
        label = stringResource(R.string.workflow_editor_logic_operator),
        value = logic.operator.name,
        options = LogicOperator.values().map { it.name to it.name },
        onSelect = { name ->
            LogicOperator.values().firstOrNull { it.name == name }?.let { onChange(logic.copy(operator = it)) }
        },
    )
    Text(
        text = stringResource(R.string.workflow_editor_logic_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ExtractNodeSection(
    extract: ExtractNode,
    nodes: List<WorkflowNode>,
    onChange: (ExtractNode) -> Unit,
) {
    EditorSectionTitle(stringResource(R.string.workflow_editor_extract_section))
    EditorPickerField(
        label = stringResource(R.string.workflow_editor_extract_mode),
        value = extract.mode.name,
        options = ExtractMode.values().map { it.name to extractModeLabel(it) },
        onSelect = { name ->
            ExtractMode.values().firstOrNull { it.name == name }?.let { onChange(extract.copy(mode = it)) }
        },
    )

    // 数值字段用文本态：用户清空输入框时不能立刻把 0 写回去，否则光标会跳。
    var groupText by remember(extract.id) { mutableStateOf(extract.group.toString()) }
    var startIndexText by remember(extract.id) { mutableStateOf(extract.startIndex.toString()) }
    var lengthText by remember(extract.id) { mutableStateOf(extract.length.toString()) }
    var randomMinText by remember(extract.id) { mutableStateOf(extract.randomMin.toString()) }
    var randomMaxText by remember(extract.id) { mutableStateOf(extract.randomMax.toString()) }
    var randomLengthText by remember(extract.id) { mutableStateOf(extract.randomStringLength.toString()) }

    when (extract.mode) {
        ExtractMode.REGEX, ExtractMode.JSON -> {
            AppTextField(
                value = extract.expression,
                onValueChange = { onChange(extract.copy(expression = it)) },
                label = stringResource(
                    if (extract.mode == ExtractMode.REGEX) {
                        R.string.workflow_editor_extract_regex
                    } else {
                        R.string.workflow_editor_extract_json_path
                    }
                ),
                singleLine = true,
            )
            if (extract.mode == ExtractMode.REGEX) {
                AppTextField(
                    value = groupText,
                    onValueChange = { text ->
                        groupText = text
                        onChange(extract.copy(group = text.toIntOrNull() ?: extract.group))
                    },
                    label = stringResource(R.string.workflow_editor_extract_group),
                    singleLine = true,
                )
            }
            DefaultValueField(extract = extract, onChange = onChange)
        }

        ExtractMode.SUB -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppTextField(
                    value = startIndexText,
                    onValueChange = { text ->
                        startIndexText = text
                        onChange(extract.copy(startIndex = text.toIntOrNull() ?: extract.startIndex))
                    },
                    label = stringResource(R.string.workflow_editor_extract_start),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = lengthText,
                    onValueChange = { text ->
                        lengthText = text
                        onChange(extract.copy(length = text.toIntOrNull() ?: extract.length))
                    },
                    label = stringResource(R.string.workflow_editor_extract_length),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            DefaultValueField(extract = extract, onChange = onChange)
        }

        ExtractMode.CONCAT -> {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                extract.others.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ParameterValueEditor(
                            label = stringResource(R.string.workflow_editor_extract_concat_item),
                            value = value,
                            nodes = nodes,
                            excludeNodeId = extract.id,
                            onChange = { updated ->
                                onChange(
                                    extract.copy(
                                        others = extract.others.toMutableList().also { it[index] = updated }
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        EditorDeleteIcon(
                            onClick = {
                                onChange(
                                    extract.copy(
                                        others = extract.others.toMutableList().also { it.removeAt(index) }
                                    )
                                )
                            }
                        )
                    }
                }
                EditorAddRowButton(
                    text = stringResource(R.string.workflow_editor_extract_add_concat),
                    onClick = { onChange(extract.copy(others = extract.others + ParameterValue.StaticValue(""))) },
                )
            }
        }

        ExtractMode.RANDOM_INT -> {
            FixedValueSwitch(extract = extract, onChange = onChange)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppTextField(
                    value = randomMinText,
                    onValueChange = { text ->
                        randomMinText = text
                        onChange(extract.copy(randomMin = text.toIntOrNull() ?: extract.randomMin))
                    },
                    label = stringResource(R.string.workflow_editor_extract_min),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = randomMaxText,
                    onValueChange = { text ->
                        randomMaxText = text
                        onChange(extract.copy(randomMax = text.toIntOrNull() ?: extract.randomMax))
                    },
                    label = stringResource(R.string.workflow_editor_extract_max),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ExtractMode.RANDOM_STRING -> {
            FixedValueSwitch(extract = extract, onChange = onChange)
            AppTextField(
                value = randomLengthText,
                onValueChange = { text ->
                    randomLengthText = text
                    onChange(extract.copy(randomStringLength = text.toIntOrNull() ?: extract.randomStringLength))
                },
                label = stringResource(R.string.workflow_editor_extract_string_length),
                singleLine = true,
            )
            AppTextField(
                value = extract.randomStringCharset,
                onValueChange = { onChange(extract.copy(randomStringCharset = it)) },
                label = stringResource(R.string.workflow_editor_extract_charset),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
        }
    }

    if (extract.mode != ExtractMode.RANDOM_INT && extract.mode != ExtractMode.RANDOM_STRING) {
        ParameterValueEditor(
            label = stringResource(R.string.workflow_editor_extract_source),
            value = extract.source,
            nodes = nodes,
            excludeNodeId = extract.id,
            onChange = { onChange(extract.copy(source = it)) },
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun DefaultValueField(extract: ExtractNode, onChange: (ExtractNode) -> Unit) {
    AppTextField(
        value = extract.defaultValue,
        onValueChange = { onChange(extract.copy(defaultValue = it)) },
        label = stringResource(R.string.workflow_editor_extract_default),
        singleLine = true,
    )
}

@Composable
private fun FixedValueSwitch(extract: ExtractNode, onChange: (ExtractNode) -> Unit) {
    EditorSwitchRow(
        label = stringResource(R.string.workflow_editor_extract_use_fixed),
        checked = extract.useFixed,
        onCheckedChange = { onChange(extract.copy(useFixed = it)) },
    )
    if (extract.useFixed) {
        AppTextField(
            value = extract.fixedValue,
            onValueChange = { onChange(extract.copy(fixedValue = it)) },
            label = stringResource(R.string.workflow_editor_extract_fixed_value),
            singleLine = true,
        )
    }
}

@Composable
private fun operatorLabel(operator: ConditionOperator): String = when (operator) {
    ConditionOperator.EQ -> stringResource(R.string.workflow_editor_operator_eq)
    ConditionOperator.NE -> stringResource(R.string.workflow_editor_operator_ne)
    ConditionOperator.GT -> stringResource(R.string.workflow_editor_operator_gt)
    ConditionOperator.GTE -> stringResource(R.string.workflow_editor_operator_gte)
    ConditionOperator.LT -> stringResource(R.string.workflow_editor_operator_lt)
    ConditionOperator.LTE -> stringResource(R.string.workflow_editor_operator_lte)
    ConditionOperator.CONTAINS -> stringResource(R.string.workflow_editor_operator_contains)
    ConditionOperator.NOT_CONTAINS -> stringResource(R.string.workflow_editor_operator_not_contains)
    ConditionOperator.IN -> stringResource(R.string.workflow_editor_operator_in)
    ConditionOperator.NOT_IN -> stringResource(R.string.workflow_editor_operator_not_in)
}

@Composable
private fun extractModeLabel(mode: ExtractMode): String = when (mode) {
    ExtractMode.REGEX -> stringResource(R.string.workflow_editor_mode_regex)
    ExtractMode.JSON -> stringResource(R.string.workflow_editor_mode_json)
    ExtractMode.SUB -> stringResource(R.string.workflow_editor_mode_sub)
    ExtractMode.CONCAT -> stringResource(R.string.workflow_editor_mode_concat)
    ExtractMode.RANDOM_INT -> stringResource(R.string.workflow_editor_mode_random_int)
    ExtractMode.RANDOM_STRING -> stringResource(R.string.workflow_editor_mode_random_string)
}
