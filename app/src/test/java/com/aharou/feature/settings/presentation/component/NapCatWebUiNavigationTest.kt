package com.aharou.feature.settings.presentation.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatWebUiNavigationTest {
    @Test
    fun `http 与 https 交给 WebView 自己加载`() {
        assertTrue(NapCatWebUiNavigation.isWebScheme("http"))
        assertTrue(NapCatWebUiNavigation.isWebScheme("https"))
        assertTrue(NapCatWebUiNavigation.isWebScheme("HTTPS"))
    }

    @Test
    fun `自定义 scheme 必须交给系统处理`() {
        listOf("jsbridge", "mqqapi", "mqq", "weixin", "intent").forEach {
            assertFalse(it, NapCatWebUiNavigation.isWebScheme(it))
        }
    }

    @Test
    fun `缺少 scheme 时按网页处理不误拦`() {
        assertTrue(NapCatWebUiNavigation.isWebScheme(null))
    }
}
