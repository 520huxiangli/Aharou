/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt 的分发部分与
 * ui/common/composedsl/ToolPkgComposeDslGeneratedRegistry.kt，LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：
 * - 去掉调试快照（布局上报），改成本仓的节点分发；
 * - aichat / webview 两个节点在本仓重新实现（见 ComposeDslAiChatNode.kt / ComposeDslWebViewNode.kt），
 *   不再降级为占位提示；
 * - 节点类型注册表由生成文件摊平成字面量 key，查找口径仍是 normalizeToken 后的小写形式；
 * - 新增 Aharou 侧入口 RenderComposeDsl：一次性注入输入回传、弹窗关闭、滚动状态 store 与网页宿主目录；
 * - 不支持的节点不再沉默或抛异常，渲染一行「不支持的面板组件」提示。
 */

package com.aharou.feature.plugin.dsl

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.aharou.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier

internal const val TAG = "ComposeDsl"

/** 输入框把编辑后的文本回传给 JS，宿主可据此走独立的派发队列。 */
internal val LocalComposeDslTextInputActionHandler =
    staticCompositionLocalOf<(String, String) -> Unit> {
        { _, _ -> }
    }

/** 输入框失焦时把尚未派发的输入刷出去。 */
internal val LocalComposeDslFlushTextInputHandler = staticCompositionLocalOf<() -> Unit> {
    { }
}

/** AlertDialog 自行关闭时的回调；宿主没提供就只回传 onDismissRequest 的 action。 */
internal val LocalComposeDslDialogDismissHandler = staticCompositionLocalOf<(() -> Unit)?> {
    null
}

/**
 * modifier 代理操作（combinedClickable / tap / drag 等）在渲染期取这个 handler 派发点击。
 * 与节点 onClick 的 action 同一条通路，入口会把 onAction 注入进来。
 */
internal val LocalComposeDslActionHandler =
    staticCompositionLocalOf<(String, Any?) -> Unit> { { _, _ -> } }

internal val LocalComposeDslSuspendingActionHandler =
    staticCompositionLocalOf<suspend (String, Any?) -> Unit> { { _, _ -> } }

/** 节点路径信息，只用于交互日志定位，不影响渲染。 */
internal data class ComposeDslDebugNodeInfo(
    val routeInstanceId: String,
    val nodePath: String,
    val nodeType: String,
    val nodeKey: String?
)

internal val LocalComposeDslDebugNodeInfo = staticCompositionLocalOf<ComposeDslDebugNodeInfo?> {
    null
}

internal typealias ComposeDslModifierResolver =
    @Composable (Modifier, Map<String, Any?>) -> Modifier

internal typealias ComposeDslNodeRenderer =
    @Composable (ComposeDslNode, (String, Any?) -> Unit, String, ComposeDslModifierResolver) -> Unit

internal val composeDslNodeRendererRegistry: Map<String, ComposeDslNodeRenderer> = mapOf(
    "column" to { node, onAction, nodePath, modifierResolver -> renderColumnNode(node, onAction, nodePath, modifierResolver) },
    "row" to { node, onAction, nodePath, modifierResolver -> renderRowNode(node, onAction, nodePath, modifierResolver) },
    "box" to { node, onAction, nodePath, modifierResolver -> renderBoxNode(node, onAction, nodePath, modifierResolver) },
    "spacer" to { node, onAction, nodePath, modifierResolver -> renderSpacerNode(node, onAction, nodePath, modifierResolver) },
    "lazycolumn" to { node, onAction, nodePath, modifierResolver -> renderLazyColumnNode(node, onAction, nodePath, modifierResolver) },
    "scrollarea" to { node, onAction, nodePath, modifierResolver -> renderScrollAreaNode(node, onAction, nodePath, modifierResolver) },
    "flowrow" to { node, onAction, nodePath, modifierResolver -> renderFlowRowNode(node, onAction, nodePath, modifierResolver) },
    "inkwell" to { node, onAction, nodePath, modifierResolver -> renderInkWellNode(node, onAction, nodePath, modifierResolver) },
    "tooltip" to { node, onAction, nodePath, modifierResolver -> renderTooltipNode(node, onAction, nodePath, modifierResolver) },
    "svg" to { node, onAction, nodePath, modifierResolver -> renderSvgNode(node, onAction, nodePath, modifierResolver) },
    "menuanchor" to { node, onAction, nodePath, modifierResolver -> renderMenuAnchorNode(node, onAction, nodePath, modifierResolver) },
    "slider" to { node, onAction, nodePath, modifierResolver -> renderSliderNode(node, onAction, nodePath, modifierResolver) },
    "glowborder" to { node, onAction, nodePath, modifierResolver -> renderGlowBorderNode(node, onAction, nodePath, modifierResolver) },
    "lazyrow" to { node, onAction, nodePath, modifierResolver -> renderLazyRowNode(node, onAction, nodePath, modifierResolver) },
    "text" to { node, onAction, nodePath, modifierResolver -> renderTextNode(node, onAction, nodePath, modifierResolver) },
    "textfield" to { node, onAction, nodePath, modifierResolver -> renderTextFieldNode(node, onAction, nodePath, modifierResolver) },
    "switch" to { node, onAction, nodePath, modifierResolver -> renderSwitchNode(node, onAction, nodePath, modifierResolver) },
    "checkbox" to { node, onAction, nodePath, modifierResolver -> renderCheckboxNode(node, onAction, nodePath, modifierResolver) },
    "button" to { node, onAction, nodePath, modifierResolver -> renderButtonNode(node, onAction, nodePath, modifierResolver) },
    "iconbutton" to { node, onAction, nodePath, modifierResolver -> renderIconButtonNode(node, onAction, nodePath, modifierResolver) },
    "card" to { node, onAction, nodePath, modifierResolver -> renderCardNode(node, onAction, nodePath, modifierResolver) },
    "materialtheme" to { node, onAction, nodePath, modifierResolver -> renderMaterialThemeNode(node, onAction, nodePath, modifierResolver) },
    "surface" to { node, onAction, nodePath, modifierResolver -> renderSurfaceNode(node, onAction, nodePath, modifierResolver) },
    "icon" to { node, onAction, nodePath, modifierResolver -> renderIconNode(node, onAction, nodePath, modifierResolver) },
    "linearprogressindicator" to { node, onAction, nodePath, modifierResolver -> renderLinearProgressIndicatorNode(node, onAction, nodePath, modifierResolver) },
    "circularprogressindicator" to { node, onAction, nodePath, modifierResolver -> renderCircularProgressIndicatorNode(node, onAction, nodePath, modifierResolver) },
    "snackbarhost" to { node, onAction, nodePath, modifierResolver -> renderSnackbarHostNode(node, onAction, nodePath, modifierResolver) },
    "assistchip" to { node, onAction, nodePath, modifierResolver -> renderAssistChipNode(node, onAction, nodePath, modifierResolver) },
    "badge" to { node, onAction, nodePath, modifierResolver -> renderBadgeNode(node, onAction, nodePath, modifierResolver) },
    "badgedbox" to { node, onAction, nodePath, modifierResolver -> renderBadgedBoxNode(node, onAction, nodePath, modifierResolver) },
    "dismissibledrawersheet" to { node, onAction, nodePath, modifierResolver -> renderDismissibleDrawerSheetNode(node, onAction, nodePath, modifierResolver) },
    "dismissiblenavigationdrawer" to { node, onAction, nodePath, modifierResolver -> renderDismissibleNavigationDrawerNode(node, onAction, nodePath, modifierResolver) },
    "divider" to { node, onAction, nodePath, modifierResolver -> renderDividerNode(node, onAction, nodePath, modifierResolver) },
    "dropdownmenu" to { node, onAction, nodePath, modifierResolver -> renderDropdownMenuNode(node, onAction, nodePath, modifierResolver) },
    "elevatedassistchip" to { node, onAction, nodePath, modifierResolver -> renderElevatedAssistChipNode(node, onAction, nodePath, modifierResolver) },
    "elevatedbutton" to { node, onAction, nodePath, modifierResolver -> renderElevatedButtonNode(node, onAction, nodePath, modifierResolver) },
    "elevatedcard" to { node, onAction, nodePath, modifierResolver -> renderElevatedCardNode(node, onAction, nodePath, modifierResolver) },
    "elevatedfilterchip" to { node, onAction, nodePath, modifierResolver -> renderElevatedFilterChipNode(node, onAction, nodePath, modifierResolver) },
    "elevatedsuggestionchip" to { node, onAction, nodePath, modifierResolver -> renderElevatedSuggestionChipNode(node, onAction, nodePath, modifierResolver) },
    "extendedfloatingactionbutton" to { node, onAction, nodePath, modifierResolver -> renderExtendedFloatingActionButtonNode(node, onAction, nodePath, modifierResolver) },
    "fillediconbutton" to { node, onAction, nodePath, modifierResolver -> renderFilledIconButtonNode(node, onAction, nodePath, modifierResolver) },
    "filledicontogglebutton" to { node, onAction, nodePath, modifierResolver -> renderFilledIconToggleButtonNode(node, onAction, nodePath, modifierResolver) },
    "filledtonalbutton" to { node, onAction, nodePath, modifierResolver -> renderFilledTonalButtonNode(node, onAction, nodePath, modifierResolver) },
    "filledtonaliconbutton" to { node, onAction, nodePath, modifierResolver -> renderFilledTonalIconButtonNode(node, onAction, nodePath, modifierResolver) },
    "filledtonalicontogglebutton" to { node, onAction, nodePath, modifierResolver -> renderFilledTonalIconToggleButtonNode(node, onAction, nodePath, modifierResolver) },
    "filterchip" to { node, onAction, nodePath, modifierResolver -> renderFilterChipNode(node, onAction, nodePath, modifierResolver) },
    "floatingactionbutton" to { node, onAction, nodePath, modifierResolver -> renderFloatingActionButtonNode(node, onAction, nodePath, modifierResolver) },
    "horizontaldivider" to { node, onAction, nodePath, modifierResolver -> renderHorizontalDividerNode(node, onAction, nodePath, modifierResolver) },
    "icontogglebutton" to { node, onAction, nodePath, modifierResolver -> renderIconToggleButtonNode(node, onAction, nodePath, modifierResolver) },
    "inputchip" to { node, onAction, nodePath, modifierResolver -> renderInputChipNode(node, onAction, nodePath, modifierResolver) },
    "largefloatingactionbutton" to { node, onAction, nodePath, modifierResolver -> renderLargeFloatingActionButtonNode(node, onAction, nodePath, modifierResolver) },
    "leadingicontab" to { node, onAction, nodePath, modifierResolver -> renderLeadingIconTabNode(node, onAction, nodePath, modifierResolver) },
    "listitem" to { node, onAction, nodePath, modifierResolver -> renderListItemNode(node, onAction, nodePath, modifierResolver) },
    "modaldrawersheet" to { node, onAction, nodePath, modifierResolver -> renderModalDrawerSheetNode(node, onAction, nodePath, modifierResolver) },
    "modalnavigationdrawer" to { node, onAction, nodePath, modifierResolver -> renderModalNavigationDrawerNode(node, onAction, nodePath, modifierResolver) },
    "modalwidenavigationrail" to { node, onAction, nodePath, modifierResolver -> renderModalWideNavigationRailNode(node, onAction, nodePath, modifierResolver) },
    "navigationbar" to { node, onAction, nodePath, modifierResolver -> renderNavigationBarNode(node, onAction, nodePath, modifierResolver) },
    "navigationdraweritem" to { node, onAction, nodePath, modifierResolver -> renderNavigationDrawerItemNode(node, onAction, nodePath, modifierResolver) },
    "navigationrail" to { node, onAction, nodePath, modifierResolver -> renderNavigationRailNode(node, onAction, nodePath, modifierResolver) },
    "navigationrailitem" to { node, onAction, nodePath, modifierResolver -> renderNavigationRailItemNode(node, onAction, nodePath, modifierResolver) },
    "outlinedbutton" to { node, onAction, nodePath, modifierResolver -> renderOutlinedButtonNode(node, onAction, nodePath, modifierResolver) },
    "outlinedcard" to { node, onAction, nodePath, modifierResolver -> renderOutlinedCardNode(node, onAction, nodePath, modifierResolver) },
    "outlinediconbutton" to { node, onAction, nodePath, modifierResolver -> renderOutlinedIconButtonNode(node, onAction, nodePath, modifierResolver) },
    "outlinedicontogglebutton" to { node, onAction, nodePath, modifierResolver -> renderOutlinedIconToggleButtonNode(node, onAction, nodePath, modifierResolver) },
    "permanentdrawersheet" to { node, onAction, nodePath, modifierResolver -> renderPermanentDrawerSheetNode(node, onAction, nodePath, modifierResolver) },
    "permanentnavigationdrawer" to { node, onAction, nodePath, modifierResolver -> renderPermanentNavigationDrawerNode(node, onAction, nodePath, modifierResolver) },
    "primaryscrollabletabrow" to { node, onAction, nodePath, modifierResolver -> renderPrimaryScrollableTabRowNode(node, onAction, nodePath, modifierResolver) },
    "primarytabrow" to { node, onAction, nodePath, modifierResolver -> renderPrimaryTabRowNode(node, onAction, nodePath, modifierResolver) },
    "providetextstyle" to { node, onAction, nodePath, modifierResolver -> renderProvideTextStyleNode(node, onAction, nodePath, modifierResolver) },
    "pulltorefreshbox" to { node, onAction, nodePath, modifierResolver -> renderPullToRefreshBoxNode(node, onAction, nodePath, modifierResolver) },
    "radiobutton" to { node, onAction, nodePath, modifierResolver -> renderRadioButtonNode(node, onAction, nodePath, modifierResolver) },
    "scaffold" to { node, onAction, nodePath, modifierResolver -> renderScaffoldNode(node, onAction, nodePath, modifierResolver) },
    "secondaryscrollabletabrow" to { node, onAction, nodePath, modifierResolver -> renderSecondaryScrollableTabRowNode(node, onAction, nodePath, modifierResolver) },
    "secondarytabrow" to { node, onAction, nodePath, modifierResolver -> renderSecondaryTabRowNode(node, onAction, nodePath, modifierResolver) },
    "shortnavigationbar" to { node, onAction, nodePath, modifierResolver -> renderShortNavigationBarNode(node, onAction, nodePath, modifierResolver) },
    "shortnavigationbaritem" to { node, onAction, nodePath, modifierResolver -> renderShortNavigationBarItemNode(node, onAction, nodePath, modifierResolver) },
    "smallfloatingactionbutton" to { node, onAction, nodePath, modifierResolver -> renderSmallFloatingActionButtonNode(node, onAction, nodePath, modifierResolver) },
    "snackbar" to { node, onAction, nodePath, modifierResolver -> renderSnackbarNode(node, onAction, nodePath, modifierResolver) },
    "suggestionchip" to { node, onAction, nodePath, modifierResolver -> renderSuggestionChipNode(node, onAction, nodePath, modifierResolver) },
    "tab" to { node, onAction, nodePath, modifierResolver -> renderTabNode(node, onAction, nodePath, modifierResolver) },
    "textbutton" to { node, onAction, nodePath, modifierResolver -> renderTextButtonNode(node, onAction, nodePath, modifierResolver) },
    "timepickerdialog" to { node, onAction, nodePath, modifierResolver -> renderTimePickerDialogNode(node, onAction, nodePath, modifierResolver) },
    "verticaldivider" to { node, onAction, nodePath, modifierResolver -> renderVerticalDividerNode(node, onAction, nodePath, modifierResolver) },
    "verticaldraghandle" to { node, onAction, nodePath, modifierResolver -> renderVerticalDragHandleNode(node, onAction, nodePath, modifierResolver) },
    "widenavigationrail" to { node, onAction, nodePath, modifierResolver -> renderWideNavigationRailNode(node, onAction, nodePath, modifierResolver) },
    "widenavigationrailitem" to { node, onAction, nodePath, modifierResolver -> renderWideNavigationRailItemNode(node, onAction, nodePath, modifierResolver) },
    "boxwithconstraints" to { node, onAction, nodePath, modifierResolver -> renderBoxWithConstraintsNode(node, onAction, nodePath, modifierResolver) },
    "basictext" to { node, onAction, nodePath, modifierResolver -> renderBasicTextNode(node, onAction, nodePath, modifierResolver) },
    "disableselection" to { node, onAction, nodePath, modifierResolver -> renderDisableSelectionNode(node, onAction, nodePath, modifierResolver) },
    "image" to { node, onAction, nodePath, modifierResolver -> renderImageNode(node, onAction, nodePath, modifierResolver) },
    "selectioncontainer" to { node, onAction, nodePath, modifierResolver -> renderSelectionContainerNode(node, onAction, nodePath, modifierResolver) },
)

/**
 * 渲染一棵 DSL 树。
 *
 * [state] 是 JS 侧给出的状态快照，仅用于排查（各节点的值已经写在 props 里）；Kotlin 侧不持有
 * 真值，所有交互都通过 [onAction] 回传 actionId 与值，由 JS 执行 handler 后重新下发整棵树。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RenderComposeDsl(
    tree: ComposeDslNode,
    state: Map<String, Any?> = emptyMap(),
    onAction: (actionId: String, value: Any?) -> Unit,
    modifier: Modifier = Modifier,
    onTextInput: ((actionId: String, text: String) -> Unit)? = null,
    onFlushTextInput: (() -> Unit)? = null,
    onDialogDismiss: (() -> Unit)? = null,
    scrollStateKey: String = "compose_dsl",
    webViewHost: ComposeDslWebViewHost? = null
) {
    val textInputHandler: (String, String) -> Unit =
        onTextInput ?: { actionId, text -> onAction(actionId, text) }
    val flushHandler: () -> Unit = onFlushTextInput ?: {}
    val scrollStore = rememberComposeDslScrollStateStore(scrollStateKey)

    // 插件常把首屏渲染成「加载中」的空容器，靠根的 onLoad 回调去取数据再重渲染。
    // 不派发就永远停在空白；只派发一次，避免每次重渲染都重跑初始化。
    val rootOnLoadActionId = remember(tree) { ComposeDslParser.extractActionId(tree.props["onLoad"]) }
    var onLoadDispatched by remember(scrollStateKey) { mutableStateOf(false) }
    LaunchedEffect(tree, rootOnLoadActionId) {
        if (rootOnLoadActionId.isNullOrBlank() || onLoadDispatched) return@LaunchedEffect
        withFrameNanos { }
        onLoadDispatched = true
        onAction(rootOnLoadActionId, null)
    }
    CompositionLocalProvider(
        LocalComposeDslActionHandler provides onAction,
        LocalComposeDslSuspendingActionHandler provides { actionId, value ->
            onAction(actionId, value)
        },
        LocalComposeDslTextInputActionHandler provides textInputHandler,
        LocalComposeDslFlushTextInputHandler provides flushHandler,
        LocalComposeDslDialogDismissHandler provides onDialogDismiss,
        LocalComposeDslScrollStateStore provides scrollStore,
        LocalComposeDslWebViewHost provides webViewHost
    ) {
        Box(modifier = modifier) {
            renderComposeDslNode(
                node = tree,
                onAction = onAction,
                nodePath = "root"
            )
        }
    }
}

/** 只关心 actionId 的接线方用这个重载；带值回传（Switch/Checkbox/TextField）请用上面的重载。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RenderComposeDsl(
    tree: ComposeDslNode,
    state: Map<String, Any?>,
    onAction: (actionId: String) -> Unit,
    modifier: Modifier = Modifier,
    scrollStateKey: String = "compose_dsl",
    webViewHost: ComposeDslWebViewHost? = null
) {
    RenderComposeDsl(
        tree = tree,
        state = state,
        onAction = { actionId, _ -> onAction(actionId) },
        modifier = modifier,
        scrollStateKey = scrollStateKey,
        webViewHost = webViewHost
    )
}

@Composable
internal fun renderComposeDslNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver = { base, props ->
        defaultComposeDslModifierResolver(base, props)
    }
) {
    val nodeKey = node.props["key"]?.toString()?.trim()?.ifBlank { null }
    CompositionLocalProvider(
        LocalComposeDslDebugNodeInfo provides ComposeDslDebugNodeInfo(
            routeInstanceId = "",
            nodePath = nodePath,
            nodeType = node.type,
            nodeKey = nodeKey
        )
    ) {
        val normalizedType = normalizeToken(node.type)
        if (normalizedType == "aichat") {
            renderAiChatNode(node, onAction, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "adaptivesidepanel") {
            renderAdaptiveSidePanelNode(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "canvas") {
            renderCanvasNode(node, onAction, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "webview") {
            renderWebViewNode(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "markdown") {
            renderMarkdownNode(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "alertdialog") {
            renderComposeDslAlertDialogNode(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "dialog") {
            renderComposeDslDialogNode(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        val renderer = composeDslNodeRendererRegistry[normalizedType]
        if (renderer != null) {
            renderer(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        Text(
            text = stringResource(R.string.plugin_dsl_unsupported_component, node.type),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 节点调试用的布局上报在 Operit 里配合快照存储工作；本仓没有移植那套调试设施，
 * 保留同名入口让渲染器无需改动调用点。
 */
@Composable
internal fun applyComposeDslNodeDebugLayoutModifier(modifier: Modifier): Modifier = modifier
