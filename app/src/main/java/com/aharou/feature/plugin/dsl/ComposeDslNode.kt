/*
 * 移植自 AAswordman/Operit（原文件 core/tools/packTool/ToolPkgComposeDslParser.kt 的数据类部分，
 * LGPL-3.0）。本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：类名去掉 ToolPkg 前缀，字段与 JSON 键保持原样（type/props/children/slots、tree/state/memo）。
 */

package com.aharou.feature.plugin.dsl

/**
 * DSL 树节点。
 *
 * props 的值是解析后的 JSON 结构（嵌套 map/list），函数型 prop 已被运行时替换成
 * `{__actionId: "action_1"}`，渲染层只负责回传 actionId。
 */
data class ComposeDslNode(
    val type: String,
    val props: Map<String, Any?>,
    val children: List<ComposeDslNode>,
    val slots: Map<String, List<ComposeDslNode>> = emptyMap()
)

/** JS 侧一次渲染的完整结果：树、状态快照与 memo（memo 目前不参与渲染）。 */
data class ComposeDslRenderResult(
    val tree: ComposeDslNode,
    val state: Map<String, Any?>,
    val memo: Map<String, Any?>,
    /** dispatch 时插件 action 的返回值；WebView 的拦截/导航决策靠它回给 WebViewClient。 */
    val actionResult: Any? = null
)
