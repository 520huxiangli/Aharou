package com.aharou.feature.plugin.dsl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image

/**
 * 插件面板里高频出现、但主渲染器最早那批没覆盖的组件。
 *
 * 都按「尽量画出结构、拿不准的降级成可读占位」处理：
 * 少一个组件只会让那一块显示得朴素，不会让整页打不开。
 */

@Composable
internal fun renderScrollAreaNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val scrollState = rememberScrollState()
    Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
            .verticalScroll(scrollState)
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun renderFlowRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    val runSpacing = props.dp("runSpacing", spacing)
    FlowRow(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        horizontalArrangement = props.horizontalArrangement("horizontalArrangement", spacing),
        verticalArrangement = props.verticalArrangement("verticalArrangement", runSpacing)
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

@Composable
internal fun renderInkWellNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClick = ComposeDslParser.extractActionId(props["onClick"])
    Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver).let { base ->
            if (!onClick.isNullOrBlank()) base.clickable { onAction(onClick, null) } else base
        }
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

/**
 * Tooltip 只保留被包裹的内容。
 *
 * 悬浮提示需要悬停/长按容器与浮层锚点，插件里的用例都是「图标按钮 + 说明文字」，
 * 先把按钮本身画出来比做一个不跟随的浮层更有用。
 */
@Composable
internal fun renderTooltipNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    Box(modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

/**
 * 插件用 Svg 画小图标（文件类型图标之类）。
 *
 * 宿主没有 SVG 渲染依赖，按尺寸画一个占位图标：布局不会塌，按钮仍然可点。
 */
@Composable
internal fun renderSvgNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val width = props.dp("width", 16.dp)
    val height = props.dp("height", width)
    Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver).size(width, height),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width, height)
        )
    }
}

/**
 * MenuAnchor 是「点一下弹出列表」的锚点容器。
 *
 * 没有锚点浮层时把锚点内容画出来，点击动作仍然派发：插件收到后会自己切状态，
 * 多数实现会因此把列表渲染进树里。
 */
@Composable
internal fun renderMenuAnchorNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClick = ComposeDslParser.extractActionId(props["onClick"])
    Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver).let { base ->
            if (!onClick.isNullOrBlank()) base.clickable { onAction(onClick, null) } else base
        }
    ) {
        renderSlotChildren(
            node = node,
            slotName = "anchor",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

@Composable
internal fun renderSliderNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val actionId = ComposeDslParser.extractActionId(props["onValueChange"])
    val min = props.floatOrNull("min") ?: 0f
    val max = props.floatOrNull("max") ?: 1f
    val rawValue = props.floatOrNull("value") ?: min
    val divisions = props.floatOrNull("divisions")?.toInt()?.takeIf { it > 0 } ?: 0
    val enabled = props.bool("enabled", true)
    Slider(
        value = rawValue.coerceIn(min, max),
        onValueChange = { next -> if (!actionId.isNullOrBlank()) onAction(actionId, next) },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = enabled,
        valueRange = min..max,
        steps = (divisions - 1).coerceAtLeast(0)
    )
}

/**
 * GlowBorder 是插件的装饰边框。
 *
 * 不做流光动画（需要逐帧重绘），按首个颜色画一圈静态描边，保留圆角与粗细，
 * 让布局尺寸与视觉层级和插件预期一致。
 */
@Composable
internal fun renderGlowBorderNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val strokeWidth = props.dp("strokeWidth", 2.dp)
    val cornerRadius = props.dp("cornerRadius", 0.dp)
    val color = parseColorToken(props["colors"]) ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
            .border(strokeWidth, SolidColor(color), androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
    ) {
        renderSlotChildren(
            node = node,
            slotName = "content",
            onAction = onAction,
            nodePath = nodePath,
            modifierResolver = modifierResolver,
            fallbackToChildren = true
        )
    }
}

/** colors 可能是数组或单个字符串；取第一个能解析的。 */
private fun parseColorToken(raw: Any?): Color? {
    val candidate = when (raw) {
        is List<*> -> raw.firstOrNull()
        else -> raw
    }?.toString()?.trim().orEmpty()
    if (candidate.isBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(candidate)) }.getOrNull()
}
