/*
 * aichat / webview 两个节点的宿主上下文与入口说明。
 *
 * 移植自 AAswordman/Operit（ui/common/composedsl 下的 ToolPkgComposeDslScreen.kt 与
 * ToolPkgComposeDslWebView.kt，LGPL-3.0），本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 实现拆在两处：
 * - [renderAiChatNode]（ComposeDslAiChatNode.kt）：只读会话视图 + 输入框，复用聊天页气泡与发送队列。
 * - [renderWebViewNode]（ComposeDslWebViewNode.kt）：https / 插件包内页面 + 脚本执行 + 页面回话。
 */

package com.aharou.feature.plugin.dsl

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/**
 * 面板网页的宿主上下文。
 *
 * DSL 渲染层拿不到插件安装目录（桥里只有 pluginId，渲染层不依赖它），所以插件包内相对路径
 * 需要宿主把目录传进来；没传时相对路径会被拦下并给出明确提示，不会退化成读宿主文件。
 */
data class ComposeDslWebViewHost(
    val pluginBaseDir: File? = null,
    /** 网页里的 JS 接口调用要回到插件进程执行；为空时接口调用直接失败，不做假成功。 */
    val jsBridge: ComposeDslJsBridge? = null,
    /**
     * 派发一个 action 并等插件返回结果。
     *
     * WebView 的决策型回调（onInterceptRequest / onShouldOverrideUrlLoading）要求同步给结论，
     * 只能靠它把插件的返回值取回来；为空时按「不处理」放行，绝不阻断页面加载。
     */
    val dispatchAwait: (suspend (actionId: String, payload: Any?) -> Any?)? = null
)

/** 页面 JS → 插件函数的通道。返回插件函数的结果（已序列化成文本）。 */
fun interface ComposeDslJsBridge {
    suspend fun call(
        controllerId: String,
        interfaceName: String,
        method: String,
        argsJson: String
    ): String
}

internal val LocalComposeDslWebViewHost = staticCompositionLocalOf<ComposeDslWebViewHost?> {
    null
}
