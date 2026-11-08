package com.aharou.feature.settings.presentation.component

/**
 * 内嵌 WebUI 的导航判定。
 *
 * WebView 只负责 http(s) 页面；`jsbridge:`、`mqqapi:` 这类自定义 scheme 是页面在请求跳去
 * 手机 QQ，必须交给系统分发，否则登录的新设备验证永远走不完。
 */
internal object NapCatWebUiNavigation {

    /** true 表示这是 WebView 自己该加载的网页地址。 */
    fun isWebScheme(scheme: String?): Boolean {
        val normalized = scheme?.lowercase() ?: return true
        return normalized == "http" || normalized == "https"
    }
}
