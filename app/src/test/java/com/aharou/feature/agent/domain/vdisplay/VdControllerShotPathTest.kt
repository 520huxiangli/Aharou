package com.aharou.feature.agent.domain.vdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ps` 命令行解析：复用别的实例留下的 runner 时，靠它把 runner 实际的截图输出路径读回来。
 * 路径是命令行里唯一带引号的参数（见 [VdController] 启动时拼的命令）。
 */
class VdControllerShotPathTest {

    @Test
    fun parse_takesQuotedPngPath() {
        val args = "app_process / com.aharou.vd.VdMain 1080 1920 440 " +
            "\"/storage/emulated/0/Android/data/com.aharou.agent/files/vd/frame-ce3c299e.png\" " +
            "/data/local/tmp/aharou-vd.shot"
        assertEquals(
            "/storage/emulated/0/Android/data/com.aharou.agent/files/vd/frame-ce3c299e.png",
            VdController.parseRunnerShotPath(args)
        )
    }

    @Test
    fun parse_toleratesExtraSpacesAndOtherArgs() {
        val args = "  app_process   /   com.aharou.vd.VdMain 1080 1920 440 " +
            "  \"/storage/emulated/0/Android/data/com.aharou.agent.debug/files/vd/frame-1a2b3c4d.png\"  " +
            "/data/local/tmp/aharou-vd.shot"
        assertEquals(
            "/storage/emulated/0/Android/data/com.aharou.agent.debug/files/vd/frame-1a2b3c4d.png",
            VdController.parseRunnerShotPath(args)
        )
    }

    @Test
    fun parse_returnsNullWhenNoRunnerLine() {
        assertNull(VdController.parseRunnerShotPath(""))
        assertNull(VdController.parseRunnerShotPath("com[.]aharou[.]vd[.]VdMain"))
    }

    @Test
    fun parse_ignoresQuotedArgsThatAreNotPng() {
        assertNull(
            VdController.parseRunnerShotPath(
                "app_process / com.aharou.vd.VdMain 1080 1920 440 /data/local/tmp/aharou-vd.shot"
            )
        )
    }
}
