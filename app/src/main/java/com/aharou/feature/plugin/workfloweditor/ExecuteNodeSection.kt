/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 *        （NodeDialog 的 execute 分支）
 * 改动：
 * - 工具候选从 Operit 的 AIToolHandler + SystemToolPrompts 换成 Aharou 的 ToolRegistry：
 *   工作流执行时也是按注册表名字解析，编辑期给同一套名字才不会「选得到跑不了」；
 * - 参数行按工具 schema 自动补空位，并显示类型/必填/说明；
 * - 保存时去掉空参数名，避免写进 actionConfig 一堆空键。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.WorkflowNode
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown

@Composable
fun ExecuteNodeSection(
    execute: ExecuteNode,
    nodes: List<WorkflowNode>,
    toolNames: List<String>,
    toolLookup: (String) -> AgentTool?,
    onChange: (ExecuteNode) -> Unit,
) {
    var suggestionsOpen by remember { mutableStateOf(false) }
    var parameters by remember(execute.id) {
        mutableStateOf(execute.actionConfig.map { EditableParameter.from(it.key, it.value) })
    }
    val tool = toolLookup(execute.actionType)
    val schema = tool?.parameters.orEmpty()
    val schemaNames = schema.keys.toList()

    // 换了工具就把 schema 里没被覆盖的参数补进来，旧的自定义键保留在后面。
    LaunchedEffect(execute.actionType) {
        if (schemaNames.isEmpty()) return@LaunchedEffect
        val existing = parameters.filter { it.key.isNotBlank() }.associateBy { it.key }
        val merged = schemaNames.map { name -> existing[name] ?: EditableParameter(name, false, "") } +
            parameters.filter { it.key.isBlank() || it.key !in schemaNames }
        if (merged != parameters) parameters = merged
    }

    fun publish(list: List<EditableParameter>) {
        parameters = list
        onChange(
            execute.copy(
                actionConfig = list
                    .filter { it.key.isNotBlank() }
                    .associate { it.key.trim() to it.toParameterValue() },
            )
        )
    }

    EditorSectionTitle(stringResource(R.string.workflow_editor_execute_section))
    Box(modifier = Modifier.fillMaxWidth()) {
        AppTextField(
            value = execute.actionType,
            onValueChange = { onChange(execute.copy(actionType = it)) },
            label = stringResource(R.string.workflow_editor_action_type),
            placeholder = stringResource(R.string.workflow_editor_action_type_hint),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { suggestionsOpen = true }) {
                    Icon(
                        FeatherIcons.ChevronDown,
                        contentDescription = stringResource(R.string.workflow_editor_pick_tool),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        val query = execute.actionType.trim()
        val filtered = remember(query, toolNames) {
            if (query.isBlank()) toolNames.take(40)
            else toolNames.filter { it.contains(query, ignoreCase = true) }.take(40)
        }
        DropdownMenu(expanded = suggestionsOpen, onDismissRequest = { suggestionsOpen = false }) {
            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_editor_no_tool_match)) },
                    onClick = { suggestionsOpen = false },
                    enabled = false,
                )
            }
            filtered.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        suggestionsOpen = false
                        onChange(execute.copy(actionType = name))
                    },
                )
            }
        }
    }
    tool?.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs),
            maxLines = 4,
        )
    }

    EditorSectionTitle(stringResource(R.string.workflow_editor_params_section))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        parameters.forEachIndexed { index, parameter ->
            val meta = schema[parameter.key.trim()]
            EditableParameterRow(
                parameter = parameter,
                nodes = nodes,
                excludeNodeId = execute.id,
                onChange = { updated ->
                    publish(parameters.toMutableList().also { it[index] = updated })
                },
                onRemove = { publish(parameters.toMutableList().also { it.removeAt(index) }) },
                hint = meta?.let { parameter ->
                    stringResource(
                        R.string.workflow_editor_param_hint,
                        parameter.type.name.lowercase(),
                        if (parameter.required) {
                            stringResource(R.string.workflow_editor_param_required)
                        } else {
                            stringResource(R.string.workflow_editor_param_optional)
                        },
                        parameter.description,
                    )
                },
            )
        }
        EditorAddRowButton(
            text = stringResource(R.string.workflow_editor_add_param),
            onClick = { publish(parameters + EditableParameter("", false, "")) },
        )
    }
}
