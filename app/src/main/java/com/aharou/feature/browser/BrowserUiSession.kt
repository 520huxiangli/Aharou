package com.aharou.feature.browser

import android.content.Context
import android.content.MutableContextWrapper

/**
 * [T-android-browser-ui-context] 浏览器面板可见性与池内 WebView context 基座的单一事实源。
 *
 * 池内 WebView 共用同一个 [MutableContextWrapper]：
 * - 面板可见（用户在浏览器里操作）时基座切到 Activity context，`<select>` 下拉、
 *   原生弹层等依赖窗口的能力才生效；
 * - 面板离屏（AI 后台驱动）时退回 Application context，后台页面弹不出原生 UI，
 *   也就无法把弹窗伪装成 App 自己的界面。
 *
 * 同时暴露 [isAttached]，供 [BrowserUseManager] 的网页对话框分流（面板可见时交给
 * 用户处理，离屏时维持 AI 自动应答）。面板的 [setUiAttached] 由 [BrowserTabPool] 代理转发。
 */
object BrowserUiSession {

    @Volatile
    var attachedContext: Context? = null
        private set

    @Volatile
    private var applicationContext: Context? = null

    private var wrapper: MutableContextWrapper? = null

    /** 面板是否可见（用户正在看浏览器）。 */
    val isAttached: Boolean get() = attachedContext != null

    /** 池内 WebView 的共享 context。首次调用以 [app] 的 Application context 为基座。 */
    fun webViewContext(app: Context): Context {
        applicationContext = app.applicationContext
        wrapper?.let { return it }
        val base = attachedContext ?: app.applicationContext
        return MutableContextWrapper(base).also { wrapper = it }
    }

    /** 面板可见性钩子：[uiContext] 为 null 表示离屏。 */
    fun setUiAttached(uiContext: Context?) {
        attachedContext = uiContext
        wrapper?.baseContext = uiContext ?: applicationContext
    }
}
