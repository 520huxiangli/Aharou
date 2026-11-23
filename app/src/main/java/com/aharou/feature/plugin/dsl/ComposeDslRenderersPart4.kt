package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt（行 2878-3370），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 *
 * 本分片（Snackbar/Tab/TimePickerDialog/WideNavigationRailItem/BoxWithConstraints/Text/Image/
 * SelectionContainer 等）无 API 降级；Image 用依赖里已有的 coil-compose。
 */

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

@Composable
internal fun renderSmallFloatingActionButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderSnackbarNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.Snackbar(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        action = {
            renderSlotChildren(
                node = node,
                slotName = "action",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        dismissAction = {
            renderSlotChildren(
                node = node,
                slotName = "dismissAction",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        actionOnNewLine = props.bool("actionOnNewLine", false),
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        actionContentColor = props.colorOrNull("actionContentColor") ?: Color.Unspecified,
        dismissActionContentColor = props.colorOrNull("dismissActionContentColor") ?: Color.Unspecified,
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderSuggestionChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.SuggestionChip(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        label = {
            renderSlotChildren(
                node = node,
                slotName = "label",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        icon = {
            renderSlotChildren(
                node = node,
                slotName = "icon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    )
}

@Composable
internal fun renderTabNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.Tab(
        selected = props.bool("selected", false),
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        selectedContentColor = props.colorOrNull("selectedContentColor") ?: Color.Unspecified,
        unselectedContentColor = props.colorOrNull("unselectedContentColor") ?: Color.Unspecified,
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> columnComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderTextButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val containerColor = props.colorOrNull("containerColor")
    val contentColor = props.colorOrNull("contentColor")
    val disabledContainerColor = props.colorOrNull("disabledContainerColor")
    val disabledContentColor = props.colorOrNull("disabledContentColor")
    val buttonColors =
        if (
            containerColor != null ||
                contentColor != null ||
                disabledContainerColor != null ||
                disabledContentColor != null
        ) {
            androidx.compose.material3.ButtonDefaults.textButtonColors(
                containerColor = containerColor ?: Color.Unspecified,
                contentColor = contentColor ?: Color.Unspecified,
                disabledContainerColor = disabledContainerColor ?: Color.Unspecified,
                disabledContentColor = disabledContentColor ?: Color.Unspecified
            )
        } else {
            androidx.compose.material3.ButtonDefaults.textButtonColors()
        }
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.TextButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.ButtonDefaults.textShape,
        colors = buttonColors,
        contentPadding = props.paddingValuesOrNull("contentPadding") ?: androidx.compose.material3.ButtonDefaults.ContentPadding,
        content = {
            val slotNodes = node.slotChildren("content", fallbackToChildren = true)
            if (slotNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = slotNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:content",
                    modifierResolver = { base, slotProps -> rowComposeDslModifierResolver(base, slotProps) }
                )
            } else {
                Text(props.string("text", "TextButton"))
            }
        }
    )
}

@Composable
internal fun renderTimePickerDialogNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onDismissRequestActionId = ComposeDslParser.extractActionId(props["onDismissRequest"])
    androidx.compose.material3.TimePickerDialog(
        onDismissRequest = {
            if (!onDismissRequestActionId.isNullOrBlank()) {
                onAction(onDismissRequestActionId, null)
            }
        },
        confirmButton = {
            renderSlotChildren(
                node = node,
                slotName = "confirmButton",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        title = {
            renderSlotChildren(
                node = node,
                slotName = "title",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        modeToggleButton = {
            renderSlotChildren(
                node = node,
                slotName = "modeToggleButton",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        dismissButton = {
            renderSlotChildren(
                node = node,
                slotName = "dismissButton",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> columnComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderVerticalDividerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.VerticalDivider(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        thickness = props.dp("thickness"),
        color = props.colorOrNull("color") ?: Color.Unspecified
    )
}

@Composable
internal fun renderVerticalDragHandleNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.VerticalDragHandle(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    )
}

@Composable
internal fun renderWideNavigationRailNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    androidx.compose.material3.WideNavigationRail(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        header = {
            renderSlotChildren(
                node = node,
                slotName = "header",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        arrangement = props.verticalArrangement("verticalArrangement", spacing),
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderWideNavigationRailItemNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.WideNavigationRailItem(
        selected = props.bool("selected", false),
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        icon = {
            renderSlotChildren(
                node = node,
                slotName = "icon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        label = {
            renderSlotChildren(
                node = node,
                slotName = "label",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        railExpanded = props.bool("railExpanded", false),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true)
    )
}

@Composable
internal fun renderBoxWithConstraintsNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        contentAlignment = props.boxAlignment("contentAlignment"),
        propagateMinConstraints = props.bool("propagateMinConstraints", false),
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderBasicTextNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onTextLayoutActionId = ComposeDslParser.extractActionId(props["onTextLayout"])
    androidx.compose.foundation.text.BasicText(
        text = props.string("text"),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        style = props.resolvedTextStyle("style", includeColor = true),
        softWrap = props.bool("softWrap", false),
        maxLines = props.int("maxLines", 0),
        overflow = props.textOverflow("overflow"),
        onTextLayout = {
            if (!onTextLayoutActionId.isNullOrBlank()) {
                onAction(onTextLayoutActionId, null)
            }
        }
    )
}

@Composable
internal fun renderDisableSelectionNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.foundation.text.selection.DisableSelection(
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderImageNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val imageModel = props.imageModelOrNull()
    val alignment = props.boxAlignment("contentAlignment")
    val alpha = props.floatOrNull("alpha") ?: 1f
    val contentScale = props.contentScale("contentScale")
    val modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    if (imageModel != null) {
        androidx.compose.foundation.Image(
            painter = rememberAsyncImagePainter(model = imageModel),
            contentDescription = props.stringOrNull("contentDescription"),
            modifier = modifier,
            alignment = alignment,
            alpha = alpha,
            contentScale = contentScale
        )
    } else {
        androidx.compose.foundation.Image(
            painter = rememberVectorPainter(iconFromName(props.string("name", props.string("icon", "info")))),
            contentDescription = props.stringOrNull("contentDescription"),
            modifier = modifier,
            alignment = alignment,
            alpha = alpha,
            contentScale = contentScale
        )
    }
}

@Composable
internal fun renderSelectionContainerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.foundation.text.selection.SelectionContainer(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}
