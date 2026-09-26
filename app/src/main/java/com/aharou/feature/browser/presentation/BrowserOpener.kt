package com.aharou.feature.browser.presentation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「打开浏览器面板并定位到 URL」（Aharou 定制）。
 *
 * 由聊天宿主（MainActivity）提供；工具消息行上的地球按钮经此呼出：
 * `selectOrCreateTabForURL(url)` 复用/新建标签后弹出面板（对齐 原版 的
 * `openBrowserSheetForUrl` 语义）。
 */
val LocalBrowserOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }
