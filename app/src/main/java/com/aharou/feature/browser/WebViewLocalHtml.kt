package com.aharou.feature.browser

import android.webkit.WebSettings

/**
 * 允许 WebView 加载 `file://` 及其中引用的本地资源——聊天里的本地 `.html` 链接走内置浏览器渲染预览。
 * WebView 默认关这两项，不开则 `file://` 直接 ERR_ACCESS_DENIED。开了之后本地页面能读本机文件。
 */
@Suppress("DEPRECATION")
internal fun WebSettings.enableLocalHtmlPreview() {
    allowFileAccess = true
    allowFileAccessFromFileURLs = true
}
