package com.aharou.feature.browser.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.browser.BrowserTabPool
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 浏览器界面 ViewModel（Aharou 移植版）：暴露应用级 [BrowserTabPool]。 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    val tabPool: BrowserTabPool
) : ViewModel()
