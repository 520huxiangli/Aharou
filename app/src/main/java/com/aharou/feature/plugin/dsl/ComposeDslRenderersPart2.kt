package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt（行 1138-2009），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 *
 * 本分片（Chip/Card/FAB/Divider/Drawer/下拉菜单等）无 API 降级；Divider 已改用语义等价的
 * HorizontalDivider（原 API 在本仓 material3 中已标记废弃）。
 */

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun renderDismissibleNavigationDrawerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.DismissibleNavigationDrawer(
        drawerContent = {
            renderSlotChildren(
                node = node,
                slotName = "drawerContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        gesturesEnabled = props.bool("gesturesEnabled", false),
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
internal fun renderDividerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.HorizontalDivider(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        thickness = props.dp("thickness"),
        color = props.colorOrNull("color") ?: Color.Unspecified
    )
}

@Composable
internal fun renderDropdownMenuNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onDismissRequestActionId = ComposeDslParser.extractActionId(props["onDismissRequest"])
    androidx.compose.material3.DropdownMenu(
        expanded = props.bool("expanded", false),
        onDismissRequest = {
            if (!onDismissRequestActionId.isNullOrBlank()) {
                onAction(onDismissRequestActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        offset = DpOffset(props.dp("offset"), 0.dp),
        properties = popupPropertiesFromValue(props["properties"]),
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
internal fun renderElevatedAssistChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ElevatedAssistChip(
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
        leadingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "leadingIcon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        trailingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "trailingIcon",
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
internal fun renderElevatedButtonNode(
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
            androidx.compose.material3.ButtonDefaults.elevatedButtonColors(
                containerColor = containerColor ?: Color.Unspecified,
                contentColor = contentColor ?: Color.Unspecified,
                disabledContainerColor = disabledContainerColor ?: Color.Unspecified,
                disabledContentColor = disabledContentColor ?: Color.Unspecified
            )
        } else {
            androidx.compose.material3.ButtonDefaults.elevatedButtonColors()
        }
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ElevatedButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.ButtonDefaults.elevatedShape,
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
                Text(props.string("text", "ElevatedButton"))
            }
        }
    )
}

@Composable
internal fun renderElevatedCardNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ElevatedCard(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
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
internal fun renderElevatedFilterChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ElevatedFilterChip(
        selected = props.bool("selected", false),
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
        leadingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "leadingIcon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        trailingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "trailingIcon",
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
internal fun renderElevatedSuggestionChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ElevatedSuggestionChip(
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
internal fun renderExtendedFloatingActionButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ExtendedFloatingActionButton(
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
                modifierResolver = { base, slotProps -> rowComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderFilledIconButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.FilledIconButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.filledShape,
        content = {
            val slotNodes = node.slotChildren("content", fallbackToChildren = true)
            if (slotNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = slotNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:content",
                    modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) }
                )
            } else {
                val iconName = props.string("icon", props.string("name", "info"))
                Icon(
                    imageVector = iconFromName(iconName),
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
internal fun renderFilledIconToggleButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onCheckedChangeActionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    androidx.compose.material3.FilledIconToggleButton(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!onCheckedChangeActionId.isNullOrBlank()) {
                onAction(onCheckedChangeActionId, checked)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.filledShape,
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
internal fun renderFilledTonalButtonNode(
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
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor ?: Color.Unspecified,
                contentColor = contentColor ?: Color.Unspecified,
                disabledContainerColor = disabledContainerColor ?: Color.Unspecified,
                disabledContentColor = disabledContentColor ?: Color.Unspecified
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        }
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.FilledTonalButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.ButtonDefaults.filledTonalShape,
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
                Text(props.string("text", "FilledTonalButton"))
            }
        }
    )
}

@Composable
internal fun renderFilledTonalIconButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.FilledTonalIconButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.filledShape,
        content = {
            val slotNodes = node.slotChildren("content", fallbackToChildren = true)
            if (slotNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = slotNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:content",
                    modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) }
                )
            } else {
                val iconName = props.string("icon", props.string("name", "info"))
                Icon(
                    imageVector = iconFromName(iconName),
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
internal fun renderFilledTonalIconToggleButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onCheckedChangeActionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    androidx.compose.material3.FilledTonalIconToggleButton(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!onCheckedChangeActionId.isNullOrBlank()) {
                onAction(onCheckedChangeActionId, checked)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.filledShape,
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
internal fun renderFilterChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.FilterChip(
        selected = props.bool("selected", false),
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
        leadingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "leadingIcon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        trailingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "trailingIcon",
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
internal fun renderFloatingActionButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.FloatingActionButton(
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
internal fun renderHorizontalDividerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.HorizontalDivider(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        thickness = props.dp("thickness"),
        color = props.colorOrNull("color") ?: Color.Unspecified
    )
}

@Composable
internal fun renderIconToggleButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onCheckedChangeActionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    androidx.compose.material3.IconToggleButton(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!onCheckedChangeActionId.isNullOrBlank()) {
                onAction(onCheckedChangeActionId, checked)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        content = {
            val slotNodes = node.slotChildren("content", fallbackToChildren = true)
            if (slotNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = slotNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:content",
                    modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) }
                )
            } else {
                val iconName = props.string("icon", props.string("name", "info"))
                Icon(
                    imageVector = iconFromName(iconName),
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
internal fun renderInputChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.InputChip(
        selected = props.bool("selected", false),
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
        leadingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "leadingIcon",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        avatar = {
            renderSlotChildren(
                node = node,
                slotName = "avatar",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        trailingIcon = {
            renderSlotChildren(
                node = node,
                slotName = "trailingIcon",
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
internal fun renderLargeFloatingActionButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.LargeFloatingActionButton(
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
internal fun renderLeadingIconTabNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.LeadingIconTab(
        selected = props.bool("selected", false),
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        text = {
            renderSlotChildren(
                node = node,
                slotName = "text",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
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
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        selectedContentColor = props.colorOrNull("selectedContentColor") ?: Color.Unspecified,
        unselectedContentColor = props.colorOrNull("unselectedContentColor") ?: Color.Unspecified
    )
}

@Composable
internal fun renderListItemNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ListItem(
        headlineContent = {
            renderSlotChildren(
                node = node,
                slotName = "headlineContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        overlineContent = {
            renderSlotChildren(
                node = node,
                slotName = "overlineContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        supportingContent = {
            renderSlotChildren(
                node = node,
                slotName = "supportingContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        leadingContent = {
            renderSlotChildren(
                node = node,
                slotName = "leadingContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        trailingContent = {
            renderSlotChildren(
                node = node,
                slotName = "trailingContent",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        tonalElevation = props.dp("tonalElevation"),
        shadowElevation = props.dp("shadowElevation")
    )
}
