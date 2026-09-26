package com.aicode.feature.browser.di

import android.content.Context
import com.aicode.feature.browser.BrowserTabPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 浏览器模块 Hilt 绑定（Aharou 移植版）。
 *
 * [BrowserTabPool] 全应用单例：Agent 工具侧与界面侧共用同一个池
 * （对齐 Minis 的单一池模型；池内 WebView 为离屏持有，截图走
 * measure/layout + draw，不依赖窗口挂载）。
 */
@Module
@InstallIn(SingletonComponent::class)
object BrowserModule {

    @Provides
    @Singleton
    fun provideBrowserTabPool(@ApplicationContext context: Context): BrowserTabPool =
        BrowserTabPool(context)
}
