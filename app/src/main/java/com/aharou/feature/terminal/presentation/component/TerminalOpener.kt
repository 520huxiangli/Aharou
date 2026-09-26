package com.aharou.feature.terminal.presentation.component

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「在终端中运行命令」（Aharou 定制）。
 *
 * 由聊天宿主（MainActivity）提供；工具消息行上的终端按钮经此呼出：
 * 打开终端并把命令**预填到提示符**（不自动回车，供过目；对齐 原版 的
 * `onOpenTerminalWithCommand` 语义）。
 */
val LocalTerminalOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }
