package com.aharou.feature.agent.presentation.component

import android.app.Application
import android.content.ActivityNotFoundException
import com.aharou.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChatCameraCaptureTest {
    @Test
    fun `未授权只申请权限不启动相机`() {
        var requests = 0
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(false, { requests++ }, { captures++ }, failures::add)

        assertEquals(1, requests)
        assertEquals(0, captures)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `已授权直接拍照不重复请求权限`() {
        var requests = 0
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(true, { requests++ }, { captures++ }, failures::add)

        assertEquals(0, requests)
        assertEquals(1, captures)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `收到授权结果后继续拍照`() {
        var requests = 0
        var captures = 0
        val failures = mutableListOf<Int>()
        runChatCameraAction(false, { requests++ }, { captures++ }, failures::add)
        assertEquals(0, captures)

        runChatCameraAction(true, { failures += R.string.chat_camera_permission_denied }, { captures++ }, failures::add)

        assertEquals(1, requests)
        assertEquals(1, captures)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `拒绝或永久拒绝授权只提示不拍照也不再次申请`() {
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(false, { failures += R.string.chat_camera_permission_denied }, { captures++ }, failures::add)

        assertEquals(0, captures)
        assertEquals(listOf(R.string.chat_camera_permission_denied), failures)
    }

    @Test
    fun `检查后权限被撤销时报告缺少权限而不抛出异常`() {
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(true, { error("不应申请权限") }, {
            captures++
            throw SecurityException("revoked permission android.permission.CAMERA")
        }, failures::add)

        assertEquals(1, captures)
        assertEquals(listOf(R.string.chat_camera_permission_denied), failures)
    }

    @Test
    fun `系统相机不可用时报告启动失败`() {
        val failures = mutableListOf<Int>()

        runChatCameraAction(true, { error("不应申请权限") }, {
            throw ActivityNotFoundException("No camera activity")
        }, failures::add)

        assertEquals(listOf(R.string.chat_camera_launch_failed), failures)
    }

    @Test
    fun `系统权限申请被拒绝启动时不继续拍照`() {
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(false, { throw SecurityException("Permission request rejected") }, { captures++ }, failures::add)

        assertEquals(0, captures)
        assertEquals(listOf(R.string.chat_camera_permission_denied), failures)
    }

    @Test
    fun `权限授权界面不可用时报告启动失败且不拍照`() {
        var captures = 0
        val failures = mutableListOf<Int>()

        runChatCameraAction(false, { throw ActivityNotFoundException("No permission activity") }, { captures++ }, failures::add)

        assertEquals(0, captures)
        assertEquals(listOf(R.string.chat_camera_launch_failed), failures)
    }
}
