package com.aharou.feature.browser

import android.content.Context
import android.webkit.WebView
import com.aharou.core.util.FileLogger

/**
 * Eruda 页内开发者工具的注入/移除与开关持久化。
 *
 * 脚本约 500KB，读一次缓存复用；开关状态存 `browser_prefs`，页面加载完成时据此重新注入
 * （页面重载会清掉页内脚本，需要在每次 onPageFinished 补一次）。
 */
object BrowserEruda {
    private const val TAG = "BrowserEruda"
    private const val PREF_FILE = "browser_prefs"
    private const val PREF_KEY = "browser_devtools_enabled"
    private const val ASSET_PATH = "scripts/eruda.min.js"

    @Volatile
    private var scriptCache: String? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)

    /** 持久化开关并立即作用于给定的页标签。 */
    fun setEnabled(context: Context, enabled: Boolean, webViews: List<WebView>) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, enabled).apply()
        if (enabled) {
            // 同时打开远程调试，便于开发者工具里的元素审查行为与 Chrome 一致。
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
            webViews.forEach { inject(it, autoShow = true) }
        } else {
            webViews.forEach { remove(it) }
        }
    }

    /** onPageFinished 钩子：开启状态下把 eruda 重新注入当前页面。 */
    fun onPageFinished(webView: WebView) {
        if (isEnabled(webView.context)) inject(webView, autoShow = false)
    }

    private fun loadScript(context: Context): String {
        scriptCache?.let { return it }
        val script = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrElse { e ->
            FileLogger.e(TAG, "读取 eruda.min.js 失败", e)
            ""
        }
        scriptCache = script
        return script
    }

    private fun inject(webView: WebView, autoShow: Boolean) {
        val script = loadScript(webView.context)
        if (script.isBlank()) return
        val showCall = if (autoShow) "try{window.eruda.show();}catch(e){}" else ""
        val js = """
            (function(){
                if(window.eruda){ $showCall return; }
                try{ $script window.eruda.init(); $showCall }catch(e){ console.error('eruda init failed', e); }
            })()
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun remove(webView: WebView) {
        val js = "(function(){if(window.eruda){" +
            "try{window.eruda.destroy();}catch(e){try{window.eruda.hide();}catch(_){}}}})()"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
