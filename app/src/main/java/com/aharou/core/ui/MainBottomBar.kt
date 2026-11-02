package com.aharou.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Home
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Sliders

/** 悬浮底栏高度：聊天页输入栏按它上抬，避免被底栏压住。 */
val MainBottomBarHeight = 60.dp

/** 当前需要给底栏让出的高度；0 表示没有底栏。聊天输入栏从这里取，不用逐层传参。 */
val LocalMainBottomBarReserve = compositionLocalOf { 0.dp }

/**
 * 一级页面悬浮底栏：首页 / 设置 / 配置。
 *
 * 只判断「当前停在哪一页」并回调目标路由，跳转方式交给调用方——core/ui 不引导航依赖。
 * 二级页（终端、编辑器、详情等）传进来的 route 不匹配，直接不渲染。
 */
@Composable
fun MainBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    maskColor: Color,
    modifier: Modifier = Modifier
) {
    val selectedIndex = when {
        currentRoute == "chat" -> 0
        // 设置页路由带可选查询参数（settings?section=xxx），按前缀判断
        currentRoute?.startsWith("settings") == true -> 1
        currentRoute == "config" -> 2
        else -> return
    }
    FloatingTabBar(
        selected = selectedIndex,
        onSelect = { index ->
            val route = when (index) {
                0 -> "chat"
                1 -> "settings"
                else -> "config"
            }
            if (route != currentRoute) onNavigate(route)
        },
        items = listOf(
            FloatingTabItem(FeatherIcons.Home, stringResource(R.string.nav_home)),
            FloatingTabItem(FeatherIcons.Settings, stringResource(R.string.settings_title)),
            FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.config_title))
        ),
        maskColor = maskColor,
        modifier = modifier
    )
}
