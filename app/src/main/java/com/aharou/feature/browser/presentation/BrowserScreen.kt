package com.aharou.feature.browser.presentation

import androidx.compose.runtime.Composable
import com.aharou.feature.browser.BrowserTabPool

/**
 * 浏览器入口（Aharou 移植版）。
 *
 * 内部直接复用自 上游项目 移植过来的 [BrowserSheet]；"返回"即关闭。
 * [embedded] 预留：宽屏工作台侧栏与全屏路由共用同一实现（v1 暂作语义标记）。
 */
@Composable
fun BrowserScreen(
    tabPool: BrowserTabPool,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false,
    onTakeoverAgent: () -> Unit = {},
) {
    BrowserSheet(
        tabPool = tabPool,
        onDismiss = onNavigateBack,
        onTakeoverAgent = onTakeoverAgent,
    )
}
