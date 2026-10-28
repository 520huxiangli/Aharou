package com.aharou.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 baseline profile：把冷启动到会话页出现这段真实调用链采样下来。
 *
 * 只跑启动路径：Aharou 冷启动要做 Hilt 注入、25 个 config 字段注册、数据库打开、
 * 会话列表渲染，这些都在首帧之前，是用户能感知到的卡顿来源。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = "com.aharou.agent",
            includeInStartupProfile = true,
        ) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            pressHome()
            startActivityAndWait()
            // 等首帧后的主内容（会话列表或空态提示）出来，别只采样到闪屏。
            device.wait(Until.hasObject(By.pkg("com.aharou.agent").depth(0)), 5_000)
            device.waitForIdle()
        }
    }
}
