package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt（行 263-1137），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 *
 * 本分片（Column/Row/Box/Spacer/Lazy 列表/Text/TextField/Switch/Checkbox/按钮/卡片等）仅有一处降级：
 * Switch 改用 AppSwitch（见 renderSwitchNode 上方注释）；其余节点按原实现。
 * 已核对本仓 compose-bom 2026.01.00（material3 1.4.0）均含所需 API。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.aharou.core.ui.AppSwitch

@Composable
internal fun renderColumnNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    androidx.compose.foundation.layout.Column(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        verticalArrangement = props.verticalArrangement("verticalArrangement", spacing),
        horizontalAlignment = props.horizontalAlignment("horizontalAlignment"),
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
internal fun renderRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    val onClick = ComposeDslParser.extractActionId(props["onClick"])
    Row(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver).let { modifier ->
            if (!onClick.isNullOrBlank()) {
                modifier.clickable { onAction(onClick, null) }
            } else {
                modifier
            }
        },
        horizontalArrangement = props.horizontalArrangement("horizontalArrangement", spacing),
        verticalAlignment = props.verticalAlignment("verticalAlignment")
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = { base, slotProps -> rowComposeDslModifierResolver(base, slotProps) },
            fallbackToChildren = true
        )
    }
}

@Composable
internal fun renderBoxNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.foundation.layout.Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        contentAlignment = props.boxAlignment("contentAlignment"),
        propagateMinConstraints = props.bool("propagateMinConstraints", false),
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> boxComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderSpacerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    Spacer(
        modifier =
            applyScopedCommonModifier(Modifier, props, modifierResolver)
                .width(props.dp("width"))
                .height(props.dp("height"))
    )
}

@Composable
internal fun renderLazyColumnNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    val reverseLayout = props.bool("reverseLayout", false)
    val autoScrollToEnd = props.bool("autoScrollToEnd", false)
    val listState =
        rememberComposeDslLazyListState(
            nodePath = nodePath,
            nodeType = node.type,
            nodeKey = props["key"]
        )
    val contentNodes = node.slotChildren("content", fallbackToChildren = true)
    val autoScrollSignature =
        if (!autoScrollToEnd) {
            0
        } else {
            contentNodes.fold(1) { acc, child -> 31 * acc + child.autoScrollSignature() }
        }

    LaunchedEffect(nodePath, autoScrollToEnd, reverseLayout, autoScrollSignature) {
        if (autoScrollToEnd && contentNodes.isNotEmpty()) {
            listState.scrollToItem(if (reverseLayout) 0 else contentNodes.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        horizontalAlignment = props.horizontalAlignment("horizontalAlignment"),
        reverseLayout = reverseLayout,
        verticalArrangement = props.verticalArrangement("verticalArrangement", spacing),
        contentPadding = PaddingValues(0.dp)
    ) {
        itemsIndexed(
            items = contentNodes,
            key = { index, child -> child.lazyItemRenderKey(nodePath, index) }
        ) { index, child ->
            val childPath = "$nodePath/$index"
            key(child.lazyItemRenderKey(nodePath, index)) {
                renderComposeDslNode(
                    node = child,
                    onAction = onAction,
                    nodePath = childPath
                )
            }
        }
    }
}

@Composable
internal fun renderLazyRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    val listState =
        rememberComposeDslLazyListState(
            nodePath = nodePath,
            nodeType = node.type,
            nodeKey = props["key"]
        )
    val contentNodes = node.slotChildren("content", fallbackToChildren = true)
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        horizontalArrangement = props.horizontalArrangement("horizontalArrangement", spacing),
        verticalAlignment = props.verticalAlignment("verticalAlignment")
    ) {
        itemsIndexed(
            items = contentNodes,
            key = { index, child -> child.lazyItemRenderKey(nodePath, index) }
        ) { index, child ->
            val childPath = "$nodePath/$index"
            key(child.lazyItemRenderKey(nodePath, index)) {
                renderComposeDslNode(
                    node = child,
                    onAction = onAction,
                    nodePath = childPath
                )
            }
        }
    }
}

@Composable
internal fun renderTextNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val resolvedStyle = props.resolvedTextStyle("style")
    val textColor = props.colorOrNull("color")
    Text(
        text = props.string("text"),
        style = resolvedStyle,
        color = textColor ?: Color.Unspecified,
        maxLines = props.int("maxLines", Int.MAX_VALUE),
        softWrap = props.bool("softWrap", true),
        overflow = props.textOverflow("overflow"),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    )
}

/*
 * 面板输入框用 OutlinedTextField 而不是 core/ui/AppTextField：回显保护需要 TextFieldValue 的
 * 光标与选区，并在失焦时主动 flush，AppTextField 只暴露 String，做不到这两点。这里是渲染层
 * 的通用控件，不属于业务页面的表单输入。
 */
@Composable
internal fun renderTextFieldNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val actionId = ComposeDslParser.extractActionId(props["onValueChange"])
    val onTextInputAction = LocalComposeDslTextInputActionHandler.current
    val flushTextInputState = LocalComposeDslFlushTextInputHandler.current
    val label = props.stringOrNull("label")
    val placeholder = props.stringOrNull("placeholder")
    val externalValue = props.string("value")
    val isPassword = props.bool("isPassword", false)
    val textFieldIdentity = props["key"]?.toString()?.trim()?.ifBlank { null } ?: nodePath
    val styleMap = props["style"] as? Map<*, *>
    val hasLabelSlot = node.slotChildren("label").isNotEmpty()
    val hasPlaceholderSlot = node.slotChildren("placeholder").isNotEmpty()
    val hasPrefixSlot = node.slotChildren("prefix").isNotEmpty()
    val hasSuffixSlot = node.slotChildren("suffix").isNotEmpty()
    val hasLeadingIconSlot = node.slotChildren("leadingIcon").isNotEmpty()
    val hasTrailingIconSlot = node.slotChildren("trailingIcon").isNotEmpty()
    val hasSupportingTextSlot = node.slotChildren("supportingText").isNotEmpty()

    var textFieldValue by remember(textFieldIdentity) {
        mutableStateOf(
            TextFieldValue(
                text = externalValue,
                selection = TextRange(externalValue.length)
            )
        )
    }
    val externalSyncGuard = remember(textFieldIdentity) { ComposeDslTextFieldEchoGuard(externalValue) }
    var isFocused by remember(textFieldIdentity) { mutableStateOf(false) }

    LaunchedEffect(textFieldIdentity, externalValue, isFocused) {
        if (
            externalSyncGuard.reconcile(externalValue, textFieldValue.text, isFocused) ==
                ComposeDslTextFieldEchoGuard.ExternalUpdate.EXTERNAL_CHANGE
        ) {
            val start = textFieldValue.selection.start.coerceIn(0, externalValue.length)
            val end = textFieldValue.selection.end.coerceIn(0, externalValue.length)
            textFieldValue =
                TextFieldValue(
                    text = externalValue,
                    selection = TextRange(start, end)
                )
        }
    }
    val textStyle =
        composeDslTextFieldStyleFromValue(styleMap)
    val textFieldModifier =
        applyScopedCommonModifier(Modifier.fillMaxWidth(), props, modifierResolver)
            .onFocusChanged { focusState ->
                val nextFocused = focusState.isFocused
                if (isFocused && !nextFocused && externalValue != textFieldValue.text) {
                    flushTextInputState()
                }
                isFocused = nextFocused
            }

    OutlinedTextField(
        value = textFieldValue,
        onValueChange = { nextValue ->
            if (!actionId.isNullOrBlank()) {
                val previousText = textFieldValue.text
                textFieldValue = nextValue
                if (nextValue.text != previousText) {
                    externalSyncGuard.onLocalEditDispatched(nextValue.text)
                    onTextInputAction(actionId, nextValue.text)
                }
            }
        },
        label =
            when {
                hasLabelSlot -> {
                    {
                        renderSlotChildren(
                            node = node,
                            slotName = "label",
                            onAction = onAction,
                            nodePath = nodePath
                        )
                    }
                }
                else -> label?.let { labelText -> { Text(labelText) } }
            },
        placeholder =
            when {
                hasPlaceholderSlot -> {
                    {
                        renderSlotChildren(
                            node = node,
                            slotName = "placeholder",
                            onAction = onAction,
                            nodePath = nodePath
                        )
                    }
                }
                else -> placeholder?.let { placeholderText -> { Text(placeholderText) } }
            },
        prefix =
            if (hasPrefixSlot) {
                {
                    renderSlotChildren(
                        node = node,
                        slotName = "prefix",
                        onAction = onAction,
                        nodePath = nodePath
                    )
                }
            } else null,
        suffix =
            if (hasSuffixSlot) {
                {
                    renderSlotChildren(
                        node = node,
                        slotName = "suffix",
                        onAction = onAction,
                        nodePath = nodePath
                    )
                }
            } else null,
        leadingIcon =
            if (hasLeadingIconSlot) {
                {
                    renderSlotChildren(
                        node = node,
                        slotName = "leadingIcon",
                        onAction = onAction,
                        nodePath = nodePath
                    )
                }
            } else null,
        trailingIcon =
            if (hasTrailingIconSlot) {
                {
                    renderSlotChildren(
                        node = node,
                        slotName = "trailingIcon",
                        onAction = onAction,
                        nodePath = nodePath
                    )
                }
            } else null,
        supportingText =
            if (hasSupportingTextSlot) {
                {
                    renderSlotChildren(
                        node = node,
                        slotName = "supportingText",
                        onAction = onAction,
                        nodePath = nodePath
                    )
                }
            } else null,
        singleLine = props.bool("singleLine", false),
        minLines = props.int("minLines", 1),
        maxLines = props.int("maxLines", if (props.bool("singleLine", false)) 1 else Int.MAX_VALUE),
        readOnly = props.bool("readOnly", false),
        isError = props.bool("isError", false),
        textStyle = textStyle ?: androidx.compose.ui.text.TextStyle.Default,
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = textFieldModifier
    )
}

/*
 * 本仓架构门禁禁止裸用 M3 Switch（bannedImports），统一走 core/ui/AppSwitch。
 * 降级说明：Operit 版支持 checkedThumbColor / checkedTrackColor / uncheckedThumbColor /
 * uncheckedTrackColor 与 thumbContent 槽位，AppSwitch 不接受这些参数——面板自定义配色的
 * 开关会退回全局主题配色，thumbContent 不渲染。
 */
@Composable
internal fun renderSwitchNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val actionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    AppSwitch(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!actionId.isNullOrBlank()) {
                onAction(actionId, checked)
            }
        },
        enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    )
}

@Composable
internal fun renderCheckboxNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val actionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    Checkbox(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!actionId.isNullOrBlank()) {
                onAction(actionId, checked)
            }
        },
        enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    )
}

@Composable
internal fun renderButtonNode(
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
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = containerColor ?: Color.Unspecified,
                contentColor = contentColor ?: Color.Unspecified,
                disabledContainerColor = disabledContainerColor ?: Color.Unspecified,
                disabledContentColor = disabledContentColor ?: Color.Unspecified
            )
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        }
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.Button(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.ButtonDefaults.shape,
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
                Text(props.string("text", "Button"))
            }
        }
    )
}

@Composable
internal fun renderIconButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.IconButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
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
internal fun renderCardNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val containerColor = props.colorOrNull("containerColor")
    val containerAlpha = props.floatOrNull("containerAlpha")
    val alpha = props.floatOrNull("alpha")
    val contentColor = props.colorOrNull("contentColor")
    val contentAlpha = props.floatOrNull("contentAlpha")
    val finalContainerColor = containerColor?.let { color ->
        when {
            containerAlpha != null -> color.copy(alpha = containerAlpha)
            alpha != null -> color.copy(alpha = alpha)
            else -> color
        }
    }
    val finalContentColor = contentColor?.let { color ->
        if (contentAlpha != null) color.copy(alpha = contentAlpha) else color
    }
    val cardColors =
        when {
            finalContainerColor != null && finalContentColor != null ->
                CardDefaults.cardColors(
                    containerColor = finalContainerColor,
                    contentColor = finalContentColor
                )
            finalContainerColor != null ->
                CardDefaults.cardColors(containerColor = finalContainerColor)
            finalContentColor != null ->
                CardDefaults.cardColors(contentColor = finalContentColor)
            else -> CardDefaults.cardColors()
        }
    Card(
        colors = cardColors,
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        shape = props.shapeOrNull() ?: CardDefaults.shape,
        border = props.borderOrNull(),
        elevation = CardDefaults.cardElevation(defaultElevation = props.dp("elevation", 1.dp))
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            fallbackToChildren = true
        )
    }
}

@Composable
internal fun renderMaterialThemeNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.MaterialTheme(
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
internal fun renderSurfaceNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClick = ComposeDslParser.extractActionId(props["onClick"])
    val contentPadding = props.commonPaddingSpecOrNull()
    val modifierProps = if (contentPadding != null) props.withoutCommonPaddingProps() else props
    val resolvedModifier =
        applyScopedCommonModifier(Modifier, modifierProps, modifierResolver).let { modifier ->
            if (!onClick.isNullOrBlank()) {
                modifier.clickable { onAction(onClick, null) }
            } else {
                modifier
            }
        }
    androidx.compose.material3.Surface(
        modifier = resolvedModifier,
        shape = props.shapeOrNull() ?: androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        color = (props.colorOrNull("color") ?: props.colorOrNull("containerColor")).let { baseColor -> baseColor?.let { color -> props.floatOrNull("alpha")?.let { color.copy(alpha = it) } ?: color } ?: Color.Transparent },
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        tonalElevation = props.dp("tonalElevation"),
        shadowElevation = props.dp("shadowElevation"),
        content = {
            Box(modifier = contentPadding?.applyTo(Modifier) ?: Modifier) {
                renderSlotChildren(
                    node = node,
                    slotName = "content",
                    onAction = onAction,
                    nodePath = nodePath,
                    modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                    fallbackToChildren = true
                )
            }
        }
    )
}

@Composable
internal fun renderIconNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val iconName = props.string("name", props.string("icon", "info"))
    val tint = props.colorOrNull("tint") ?: MaterialTheme.colorScheme.onSurfaceVariant
    val size = props.floatOrNull("size")
    Icon(
        imageVector = iconFromName(iconName),
        contentDescription = null,
        tint = tint,
        modifier = if (size != null) {
            applyScopedCommonModifier(Modifier, props, modifierResolver).width(size.dp).height(size.dp)
        } else {
            applyScopedCommonModifier(Modifier, props, modifierResolver)
        }
    )
}

@Composable
internal fun renderLinearProgressIndicatorNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val progress = props.floatOrNull("progress")
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = applyScopedCommonModifier(Modifier.fillMaxWidth(), props, modifierResolver)
        )
    } else {
        LinearProgressIndicator(
            modifier = applyScopedCommonModifier(Modifier.fillMaxWidth(), props, modifierResolver)
        )
    }
}

@Composable
internal fun renderCircularProgressIndicatorNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val strokeWidth = props.floatOrNull("strokeWidth")
    val color = props.colorOrNull("color")
    CircularProgressIndicator(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        strokeWidth = if (strokeWidth != null) strokeWidth.dp else 4.dp,
        color = color ?: MaterialTheme.colorScheme.primary
    )
}

@Composable
internal fun renderSnackbarHostNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    Spacer(modifier = applyScopedCommonModifier(Modifier, node.props, modifierResolver))
}

@Composable
internal fun renderAssistChipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.AssistChip(
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
internal fun renderBadgeNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.Badge(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
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
internal fun renderBadgedBoxNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.BadgedBox(
        badge = {
            renderSlotChildren(
                node = node,
                slotName = "badge",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> boxComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        content = {
            renderSlotChildren(
                node = node,
                slotName = "content",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> boxComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = true
            )
        }
    )
}

@Composable
internal fun renderDismissibleDrawerSheetNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.DismissibleDrawerSheet(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        drawerContainerColor = props.colorOrNull("drawerContainerColor") ?: Color.Unspecified,
        drawerContentColor = props.colorOrNull("drawerContentColor") ?: Color.Unspecified,
        drawerTonalElevation = props.dp("drawerTonalElevation"),
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
