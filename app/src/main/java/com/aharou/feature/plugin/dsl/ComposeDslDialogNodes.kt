package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 2143-2453，Markdown 与弹窗节点），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

/*
 * Markdown 节点改用本仓的 MarkdownContent（本仓唯一的 markdown 渲染入口）。
 *
 * 降级说明：
 * - 原实现支持 fontSize 覆盖与 streamTagName 流式追加（逐字增长时增量渲染），本仓组件按全局
 *   排版体系出字号、也不接受外部字符流增量，因此只按静态文本整段重渲染；流式场景下每次
 *   树更新都是整段解析，长文会有一定开销。
 * - enableDialogs 在 Operit 里控制 md 内链接弹窗，本仓组件自带链接处理，不再透传。
 */

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aharou.feature.agent.presentation.component.MarkdownContent

@Composable
internal fun renderMarkdownNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    val textColor = props.colorOrNull("color") ?: MaterialTheme.colorScheme.onSurface
    MarkdownContent(
        text = props.string("text"),
        color = textColor,
        modifier = modifier,
        // 面板内容是插件自带的长文，被折叠成「展开查看」占位会让面板交互变得不可预期。
        largeContentGuard = false
    )
}

@Composable
internal fun renderComposeDslAlertDialogNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val dismissHost = LocalComposeDslDialogDismissHandler.current
    val onDismissRequestActionId =
        ComposeDslParser.extractActionId(props["onDismissRequest"])
    val titleNodes = node.slots["title"].orEmpty()
    val textNodes = node.slots["text"].orEmpty()
    val iconNodes = node.slots["icon"].orEmpty()
    val confirmButtonNodes = node.slots["confirmButton"].orEmpty()
    val dismissButtonNodes = node.slots["dismissButton"].orEmpty()
    val contentNodes = node.slots["content"].orEmpty()
    val titleText = props.stringOrNull("title")
    val textValue = props.stringOrNull("text")
    val markdownValue = props.stringOrNull("markdown")

    val titleContent: (@Composable () -> Unit)? =
        when {
            titleNodes.isNotEmpty() -> {
                {
                    renderComposeDslNodes(
                        nodes = titleNodes,
                        onAction = onAction,
                        nodePath = "$nodePath:title"
                    )
                }
            }
            !titleText.isNullOrBlank() -> {
                { Text(text = titleText) }
            }
            else -> null
        }
    val textContent: (@Composable () -> Unit)? =
        when {
            textNodes.isNotEmpty() -> {
                {
                    renderComposeDslNodes(
                        nodes = textNodes,
                        onAction = onAction,
                        nodePath = "$nodePath:text",
                        modifierResolver = { base, slotProps ->
                            defaultComposeDslModifierResolver(base, slotProps)
                        }
                    )
                }
            }
            contentNodes.isNotEmpty() -> {
                {
                    renderComposeDslNodes(
                        nodes = contentNodes,
                        onAction = onAction,
                        nodePath = "$nodePath:content",
                        modifierResolver = { base, slotProps ->
                            defaultComposeDslModifierResolver(base, slotProps)
                        }
                    )
                }
            }
            !markdownValue.isNullOrBlank() -> {
                {
                    MarkdownContent(
                        text = markdownValue,
                        color = MaterialTheme.colorScheme.onSurface,
                        largeContentGuard = false
                    )
                }
            }
            !textValue.isNullOrBlank() -> {
                { Text(text = textValue) }
            }
            else -> null
        }
    val iconContent: (@Composable () -> Unit)? =
        if (iconNodes.isNotEmpty()) {
            {
                renderComposeDslNodes(
                    nodes = iconNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:icon"
                )
            }
        } else {
            null
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (!onDismissRequestActionId.isNullOrBlank()) {
                onAction(onDismissRequestActionId, null)
            }
            if (props.bool("closeOnDismissRequest", true)) {
                dismissHost?.invoke()
            }
        },
        confirmButton = {
            if (confirmButtonNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = confirmButtonNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:confirmButton"
                )
            } else {
                ComposeDslDialogTextButton(
                    text = props.stringOrNull("confirmText"),
                    actionId = ComposeDslParser.extractActionId(props["onConfirm"]),
                    closeOnClick = props.bool("closeOnConfirm", true),
                    onAction = onAction,
                    onClose = dismissHost
                )
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        dismissButton = {
            if (dismissButtonNodes.isNotEmpty()) {
                renderComposeDslNodes(
                    nodes = dismissButtonNodes,
                    onAction = onAction,
                    nodePath = "$nodePath:dismissButton"
                )
            } else {
                ComposeDslDialogTextButton(
                    text = props.stringOrNull("dismissText"),
                    actionId = ComposeDslParser.extractActionId(props["onDismiss"]),
                    closeOnClick = props.bool("closeOnDismiss", true),
                    onAction = onAction,
                    onClose = dismissHost
                )
            }
        },
        icon = iconContent,
        title = titleContent,
        text = textContent,
        shape = props.shapeOrNull() ?: RoundedCornerShape(28.dp),
        containerColor = props.colorOrNull("containerColor") ?: MaterialTheme.colorScheme.surface,
        iconContentColor = props.colorOrNull("iconContentColor") ?: MaterialTheme.colorScheme.secondary,
        titleContentColor = props.colorOrNull("titleContentColor") ?: MaterialTheme.colorScheme.onSurface,
        textContentColor = props.colorOrNull("textContentColor") ?: MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = props.dp("tonalElevation", 6.dp),
        properties = dialogPropertiesFromValue(props["properties"])
    )
}

@Composable
private fun ComposeDslDialogTextButton(
    text: String?,
    actionId: String?,
    closeOnClick: Boolean,
    onAction: (String, Any?) -> Unit,
    onClose: (() -> Unit)?
) {
    if (text.isNullOrBlank()) {
        return
    }
    androidx.compose.material3.TextButton(
        onClick = {
            if (!actionId.isNullOrBlank()) {
                onAction(actionId, null)
            }
            if (closeOnClick) {
                onClose?.invoke()
            }
        }
    ) {
        Text(text = text)
    }
}

@Composable
internal fun renderComposeDslDialogNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val dismissHost = LocalComposeDslDialogDismissHandler.current
    val onDismissRequestActionId =
        ComposeDslParser.extractActionId(props["onDismissRequest"])
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            if (!onDismissRequestActionId.isNullOrBlank()) {
                onAction(onDismissRequestActionId, null)
            }
            if (props.bool("closeOnDismissRequest", true)) {
                dismissHost?.invoke()
            }
        },
        properties = dialogPropertiesFromValue(props["properties"])
    ) {
        androidx.compose.material3.Surface(
            modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
            shape = props.shapeOrNull() ?: RoundedCornerShape(28.dp),
            color = props.colorOrNull("containerColor") ?: MaterialTheme.colorScheme.surface,
            tonalElevation = props.dp("tonalElevation", 6.dp)
        ) {
            val contentNodes =
                node.slots["content"]
                    ?.takeIf { it.isNotEmpty() }
                    ?: node.children
            renderComposeDslNodes(
                nodes = contentNodes,
                onAction = onAction,
                nodePath = "$nodePath:content",
                modifierResolver = { base, slotProps ->
                    defaultComposeDslModifierResolver(base, slotProps)
                }
            )
        }
    }
}
