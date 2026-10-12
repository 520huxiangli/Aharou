package com.aharou.feature.account.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aharou.core.theme.Spacing

/**
 * 「个人中心」页：只承载本地身份（头像 / 昵称 / 头图），数据全在设备上，不依赖任何后端账号。
 */
@Composable
fun AccountCenterScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
    ) {
        UserIdentityCard()
        Spacer(Modifier.height(Spacing.lg))
    }
}
