package com.aharou.feature.browser.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「用内置浏览器打开某个地址」的全局入口。
 *
 * 插件桥与工具都在非 Compose 层，拿不到 [LocalBrowserOpener]，通过这条总线
 * 把请求交给 MainActivity 的宿主作用域执行。
 */
object BrowserLaunchBus {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: SharedFlow<String> = _requests

    /** 有宿主在监听时才投递，返回 false 表示当前没人接（调用方退回系统浏览器）。 */
    fun launchIfCollected(url: String): Boolean =
        _requests.subscriptionCount.value > 0 && _requests.tryEmit(url)
}
