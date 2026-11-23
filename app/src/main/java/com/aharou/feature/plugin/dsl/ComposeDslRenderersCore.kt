package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt（行 55-262），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

/**
 * AUTO-GENERATED from Compose Material3/Foundation component bindings.
 * Do not edit manually. Regenerate via tools/compose_dsl/generate_compose_dsl_artifacts.py.
 */

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

@Composable
internal fun defaultComposeDslModifierResolver(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    return applyCommonModifier(base, props)
}

@Composable
internal fun RowScope.rowComposeDslModifierResolver(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    var modifier = applyCommonModifier(base, props)
    val weightSpec = props.modifierWeightSpecOrNull()
    if (weightSpec != null) {
        modifier = modifier.weight(weightSpec.weight, weightSpec.fill)
    }
    val alignToken = props.scopeAlignToken()
    if (alignToken != null) {
        modifier = modifier.align(verticalAlignmentFromToken(alignToken))
    }
    return modifier
}

@Composable
internal fun ColumnScope.columnComposeDslModifierResolver(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    var modifier = applyCommonModifier(base, props)
    val weightSpec = props.modifierWeightSpecOrNull()
    if (weightSpec != null) {
        modifier = modifier.weight(weightSpec.weight, weightSpec.fill)
    }
    val alignToken = props.scopeAlignToken()
    if (alignToken != null) {
        modifier = modifier.align(horizontalAlignmentFromToken(alignToken))
    }
    return modifier
}

@Composable
internal fun BoxScope.boxComposeDslModifierResolver(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    var modifier = applyCommonModifier(base, props)
    if (props.hasModifierOp("matchparentsize")) {
        modifier = modifier.matchParentSize()
    }
    val alignToken = props.scopeAlignToken()
    if (alignToken != null) {
        modifier = modifier.align(boxAlignmentFromToken(alignToken))
    }
    return modifier
}

@Composable
internal fun applyScopedCommonModifier(
    base: Modifier,
    props: Map<String, Any?>,
    modifierResolver: ComposeDslModifierResolver
): Modifier {
    return applyComposeDslNodeDebugLayoutModifier(modifierResolver(base, props))
}

@Composable
internal fun renderComposeDslNodes(
    nodes: List<ComposeDslNode>,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver = { base, props ->
        defaultComposeDslModifierResolver(base, props)
    }
) {
    nodes.forEachIndexed { index, child ->
        val childPath = "$nodePath/$index"
        renderComposeDslNode(
            node = child,
            onAction = onAction,
            nodePath = childPath,
            modifierResolver = modifierResolver
        )
    }
}

@Composable
internal fun renderNodeChildren(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver = { base, props ->
        defaultComposeDslModifierResolver(base, props)
    }
) {
    renderComposeDslNodes(node.children, onAction, nodePath, modifierResolver)
}

internal fun ComposeDslNode.slotChildren(
    slotName: String,
    fallbackToChildren: Boolean = false
): List<ComposeDslNode> {
    val normalizedSlotName = slotName.trim()
    val slotNodes =
        if (normalizedSlotName.isBlank()) {
            emptyList()
        } else {
            slots[normalizedSlotName].orEmpty()
        }
    if (slotNodes.isNotEmpty()) {
        return slotNodes
    }
    return if (fallbackToChildren) children else emptyList()
}

internal fun ComposeDslNode.lazyItemRenderKey(
    parentNodePath: String,
    index: Int
): String {
    val explicitKey = props["key"]?.toString()?.trim()?.ifBlank { null }
    if (explicitKey != null) {
        return "$parentNodePath:key:$explicitKey"
    }
    return "$parentNodePath/$index:${normalizeToken(type)}"
}

@Composable
internal fun renderSlotChildren(
    node: ComposeDslNode,
    slotName: String,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver = { base, props ->
        defaultComposeDslModifierResolver(base, props)
    },
    fallbackToChildren: Boolean = false
) {
    val slotNodes = node.slotChildren(slotName, fallbackToChildren)
    renderComposeDslNodes(slotNodes, onAction, "$nodePath:$slotName", modifierResolver)
}

@Composable
internal fun RowScope.renderRowScopeNodeChildren(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String
) {
    renderNodeChildren(
        node = node,
        onAction = onAction,
        nodePath = nodePath,
        modifierResolver = { base, props -> rowComposeDslModifierResolver(base, props) }
    )
}

@Composable
internal fun ColumnScope.renderColumnScopeNodeChildren(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String
) {
    renderNodeChildren(
        node = node,
        onAction = onAction,
        nodePath = nodePath,
        modifierResolver = { base, props -> columnComposeDslModifierResolver(base, props) }
    )
}

@Composable
internal fun BoxScope.renderBoxScopeNodeChildren(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String
) {
    renderNodeChildren(
        node = node,
        onAction = onAction,
        nodePath = nodePath,
        modifierResolver = { base, props -> boxComposeDslModifierResolver(base, props) }
    )
}

internal fun ComposeDslNode.autoScrollSignature(): Int {
    var result = type.hashCode()
    result = 31 * result + (props["key"]?.hashCode() ?: 0)
    result = 31 * result + (props["text"]?.hashCode() ?: 0)
    result = 31 * result + (props["value"]?.hashCode() ?: 0)
    result = 31 * result + children.size
    children.forEach { child ->
        result = 31 * result + child.autoScrollSignature()
    }
    result = 31 * result + slots.size
    slots.toSortedMap().forEach { (slotName, slotChildren) ->
        result = 31 * result + slotName.hashCode()
        result = 31 * result + slotChildren.size
        slotChildren.forEach { child ->
            result = 31 * result + child.autoScrollSignature()
        }
    }
    return result
}
