package com.aharou.feature.agent.domain.vdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `am stack list` 解析：关影子屏前结束屏上任务、落屏校验、取影子屏前台应用都靠它按屏号取任务。
 * 2026-10-30 真机上按 dumpsys 分段抓认错了屏，把主屏上的 Aharou 自己结束掉了。
 */
class VdControllerStackListTest {

    private val sample = """
        RootTask id=19498 bounds=[0,0][1272,2772] displayId=0 userId=0
         configuration={1.0 winConfig={ mBounds=Rect(0, 0 - 1272, 2772) }}
          taskId=19498: com.aharou.agent/com.aharou.MainActivity bounds=[0,0][1272,2772] userId=0 visible=true topActivity=ComponentInfo{com.aharou.agent/com.aharou.MainActivity}
        RootTask id=3 bounds=[0,0][1272,2772] displayId=0 userId=0
         configuration={1.0}
          taskId=0: unknown bounds=[0,0][1272,2772] userId=0 visible=false
        RootTask id=19480 bounds=[0,0][1080,1920] displayId=3 userId=0
         configuration={1.0}
          taskId=19480: com.android.settings/com.android.settings.Settings bounds=[0,0][1080,1920] userId=0 visible=true topActivity=ComponentInfo{com.android.settings/com.android.settings.Settings}
    """.trimIndent()

    @Test
    fun rootTasks_onlyFromTargetDisplay() {
        assertEquals(listOf("19480"), VdController.rootTasksOnDisplay(sample, 3))
    }

    @Test
    fun rootTasks_neverMainDisplay() {
        assertTrue(VdController.rootTasksOnDisplay(sample, 0).isEmpty())
    }

    @Test
    fun rootTasks_emptyWhenDisplayHasNoTask() {
        // 影子屏编号恰好与主屏上某个 RootTask 号相同（这里都是 3）时，也不能把那个任务算进来。
        val mainOnly = sample.substringBefore("RootTask id=19480")
        assertTrue(VdController.rootTasksOnDisplay(mainOnly, 3).isEmpty())
    }

    @Test
    fun activities_onTargetDisplayOnly() {
        assertEquals(
            listOf("com.android.settings/com.android.settings.Settings"),
            VdController.activitiesOnDisplay(sample, 3),
        )
        assertTrue(VdController.activitiesOnDisplay(sample, 0).isEmpty())
    }
}
