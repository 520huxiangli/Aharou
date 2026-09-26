package com.aharou.feature.browser.presentation

import androidx.compose.runtime.Composable
import com.aharou.feature.browser.BrowserTabPool

/**
 * 浏览器入口（Aharou 移植版）。
 *
 * 内部直接复用自 OpenMinis 移植过来的 [BrowserSheet]；"返回"即关闭。
 * [embedded] 预留：宽屏工作台侧栏与全屏路由共用同一实现（v1 暂作语义标记）。
 *
 * TODO（后续）：① 全屏沉浸版式；② 聊天侧一键呼出 + Agent 执行 browser
 * 工具时自动弹出；③ 暗色主题联动。
 */
@Composable
fun BrowserScreen(
    tabPool: BrowserTabPool,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false,
) {
    BrowserSheet(
        tabPool = tabPool,
        onDismiss = onNavigateBack,
    )
}
