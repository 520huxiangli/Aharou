package com.aharou.core.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 根容器的返回键处理：抽屉开着先收起抽屉；否则把 App 退到后台，而不是结束任务。
 *
 * 结束 Activity 会把整个界面状态（当前页面、当前会话、ViewModel）一起销毁，再从最近任务
 * 打开会重新走 onCreate，用户看到的就是「像重启」。本应用后台还有终端保活与 AI 任务在跑，
 * 退到后台才符合预期；要彻底退出请从最近任务划掉（那条路径由 onTaskRemoved 处理）。
 *
 * 放在根容器最前面声明：内层页面（编辑器、浏览器、终端、抽屉）的 BackHandler 在导航之后
 * 才注册，分发时优先级更高，这里只兜住「没有任何一处接」的情况。
 */
@Composable
fun RootBackHandler(drawerOpen: Boolean, onCloseDrawer: () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler {
        if (drawerOpen) onCloseDrawer() else activity?.moveTaskToBack(true)
    }
}
