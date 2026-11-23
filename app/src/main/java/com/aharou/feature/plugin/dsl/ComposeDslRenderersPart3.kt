package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt（行 2010-2877），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 *
 * 本分片（导航栏/导航轨/侧栏/TabRow/Scaffold/列表项/PullToRefresh 等）无 API 降级：
 * PullToRefreshBox、ShortNavigationBar、WideNavigationRail、ModalWideNavigationRail 在
 * material3 1.4.0 中均已存在，按原 API 使用。
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun renderModalDrawerSheetNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ModalDrawerSheet(
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

@Composable
internal fun renderModalNavigationDrawerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ModalNavigationDrawer(
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
        scrimColor = props.colorOrNull("scrimColor") ?: Color.Unspecified,
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
internal fun renderModalWideNavigationRailNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val spacing = props.dp("spacing")
    androidx.compose.material3.ModalWideNavigationRail(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        hideOnCollapse = props.bool("hideOnCollapse", false),
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
        expandedHeaderTopPadding = props.dp("expandedHeaderTopPadding"),
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
internal fun renderNavigationBarNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.NavigationBar(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        tonalElevation = props.dp("tonalElevation"),
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
internal fun renderNavigationDrawerItemNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.NavigationDrawerItem(
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
        selected = props.bool("selected", false),
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
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
        badge = {
            renderSlotChildren(
                node = node,
                slotName = "badge",
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
internal fun renderNavigationRailNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.NavigationRail(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        header = {
            renderSlotChildren(
                node = node,
                slotName = "header",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> columnComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
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
internal fun renderNavigationRailItemNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.NavigationRailItem(
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
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
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
        alwaysShowLabel = props.bool("alwaysShowLabel", false)
    )
}

@Composable
internal fun renderOutlinedButtonNode(
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
            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor ?: Color.Unspecified,
                contentColor = contentColor ?: Color.Unspecified,
                disabledContainerColor = disabledContainerColor ?: Color.Unspecified,
                disabledContentColor = disabledContentColor ?: Color.Unspecified
            )
        } else {
            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
        }
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.OutlinedButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.ButtonDefaults.outlinedShape,
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
                Text(props.string("text", "OutlinedButton"))
            }
        }
    )
}

@Composable
internal fun renderOutlinedCardNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.OutlinedCard(
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
internal fun renderOutlinedIconButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.OutlinedIconButton(
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.outlinedShape,
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
internal fun renderOutlinedIconToggleButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onCheckedChangeActionId = ComposeDslParser.extractActionId(props["onCheckedChange"])
    androidx.compose.material3.OutlinedIconToggleButton(
        checked = props.bool("checked", false),
        onCheckedChange = { checked ->
            if (!onCheckedChangeActionId.isNullOrBlank()) {
                onAction(onCheckedChangeActionId, checked)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true),
        shape = props.shapeOrNull() ?: androidx.compose.material3.IconButtonDefaults.outlinedShape,
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
internal fun renderPermanentDrawerSheetNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.PermanentDrawerSheet(
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

@Composable
internal fun renderPermanentNavigationDrawerNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.PermanentNavigationDrawer(
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
internal fun renderPrimaryScrollableTabRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.PrimaryScrollableTabRow(
        selectedTabIndex = props.int("selectedTabIndex", 0),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        edgePadding = props.dp("edgePadding"),
        indicator = {
            renderSlotChildren(
                node = node,
                slotName = "indicator",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        divider = {
            renderSlotChildren(
                node = node,
                slotName = "divider",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        tabs = {
            renderSlotChildren(
                node = node,
                slotName = "tabs",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        }
    )
}

@Composable
internal fun renderPrimaryTabRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.PrimaryTabRow(
        selectedTabIndex = props.int("selectedTabIndex", 0),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        indicator = {
            renderSlotChildren(
                node = node,
                slotName = "indicator",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        divider = {
            renderSlotChildren(
                node = node,
                slotName = "divider",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        tabs = {
            renderSlotChildren(
                node = node,
                slotName = "tabs",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        }
    )
}

@Composable
internal fun renderProvideTextStyleNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ProvideTextStyle(
        value = props.textStyle("style"),
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
internal fun renderPullToRefreshBoxNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onRefreshActionId = ComposeDslParser.extractActionId(props["onRefresh"])
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = props.bool("isRefreshing", false),
        onRefresh = {
            if (!onRefreshActionId.isNullOrBlank()) {
                onAction(onRefreshActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        contentAlignment = props.boxAlignment("contentAlignment"),
        indicator = {
            renderSlotChildren(
                node = node,
                slotName = "indicator",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> boxComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
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
internal fun renderRadioButtonNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.RadioButton(
        selected = props.bool("selected", false),
        onClick = {
            if (!onClickActionId.isNullOrBlank()) {
                onAction(onClickActionId, null)
            }
        },
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true)
    )
}

@Composable
internal fun renderScaffoldNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.Scaffold(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        topBar = {
            renderSlotChildren(
                node = node,
                slotName = "topBar",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        bottomBar = {
            renderSlotChildren(
                node = node,
                slotName = "bottomBar",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        snackbarHost = {
            renderSlotChildren(
                node = node,
                slotName = "snackbarHost",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        floatingActionButton = {
            renderSlotChildren(
                node = node,
                slotName = "floatingActionButton",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        content = { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
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
    )
}

@Composable
internal fun renderSecondaryScrollableTabRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.SecondaryScrollableTabRow(
        selectedTabIndex = props.int("selectedTabIndex", 0),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        edgePadding = props.dp("edgePadding"),
        indicator = {
            renderSlotChildren(
                node = node,
                slotName = "indicator",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        divider = {
            renderSlotChildren(
                node = node,
                slotName = "divider",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        tabs = {
            renderSlotChildren(
                node = node,
                slotName = "tabs",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        }
    )
}

@Composable
internal fun renderSecondaryTabRowNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.SecondaryTabRow(
        selectedTabIndex = props.int("selectedTabIndex", 0),
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        containerColor = props.colorOrNull("containerColor") ?: Color.Unspecified,
        contentColor = props.colorOrNull("contentColor") ?: Color.Unspecified,
        indicator = {
            renderSlotChildren(
                node = node,
                slotName = "indicator",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        divider = {
            renderSlotChildren(
                node = node,
                slotName = "divider",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        },
        tabs = {
            renderSlotChildren(
                node = node,
                slotName = "tabs",
                onAction = onAction,
                nodePath = nodePath,
                modifierResolver = { base, slotProps -> defaultComposeDslModifierResolver(base, slotProps) },
                fallbackToChildren = false
            )
        }
    )
}

@Composable
internal fun renderShortNavigationBarNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    androidx.compose.material3.ShortNavigationBar(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
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
internal fun renderShortNavigationBarItemNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val onClickActionId = ComposeDslParser.extractActionId(props["onClick"])
    androidx.compose.material3.ShortNavigationBarItem(
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
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver),
        enabled = props.bool("enabled", true)
    )
}
