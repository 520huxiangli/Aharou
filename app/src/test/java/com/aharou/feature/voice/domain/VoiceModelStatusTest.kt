package com.aharou.feature.voice.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceModelFileStatus / VoiceModelStatus：模型就绪判定的纯数据逻辑。
 *
 * 就绪 = 每个必需文件都存在**且字节数相等**。只看「在不在」会把上次下载残留的坏文件当成好文件
 * （见 VoiceModelSpec.fileSizes 的注释），所以断言的重点是「缺文件」和「字节数不符」两条路径都不能算就绪。
 */
class VoiceModelStatusTest {

    @Test
    fun fileStatusIsOkOnlyWhenActualMatchesExpected() {
        assertTrue(VoiceModelFileStatus("encoder.onnx", 100L, 100L).ok)
        assertFalse(VoiceModelFileStatus("encoder.onnx", 100L, 99L).ok)
        assertFalse(VoiceModelFileStatus("encoder.onnx", 100L, 101L).ok)
    }

    @Test
    fun fileStatusWithMissingFileIsNotOk() {
        assertFalse(VoiceModelFileStatus("encoder.onnx", 100L, null).ok)
    }

    @Test
    fun fileStatusWithUndeclaredSizeIsNeverOkWhenFileExists() {
        // expected=0 但文件存在：这是 requiredFiles 与 fileSizes 不同步的信号，绝不能算就绪
        assertFalse(VoiceModelFileStatus("encoder.onnx", 0L, 100L).ok)
    }

    @Test
    fun fileStatusWithZeroExpectedAndZeroActualIsOk() {
        assertTrue(VoiceModelFileStatus("empty.txt", 0L, 0L).ok)
    }

    @Test
    fun statusIsReadyWhenEveryFileIsOk() {
        val status = VoiceModelStatus(
            listOf(
                VoiceModelFileStatus("a.onnx", 10L, 10L),
                VoiceModelFileStatus("b.txt", 20L, 20L),
            )
        )
        assertTrue(status.ready)
        assertFalse(status.neverDownloaded)
    }

    @Test
    fun statusIsNotReadyWhenAFileIsMissing() {
        val status = VoiceModelStatus(
            listOf(
                VoiceModelFileStatus("a.onnx", 10L, 10L),
                VoiceModelFileStatus("b.txt", 20L, null),
            )
        )
        assertFalse(status.ready)
    }

    @Test
    fun statusIsNotReadyWhenAFileSizeMismatches() {
        val status = VoiceModelStatus(
            listOf(
                VoiceModelFileStatus("a.onnx", 10L, 10L),
                VoiceModelFileStatus("b.txt", 20L, 19L),
            )
        )
        assertFalse(status.ready)
    }

    @Test
    fun neverDownloadedIsTrueWhenNoFileExists() {
        val status = VoiceModelStatus(
            listOf(
                VoiceModelFileStatus("a.onnx", 10L, null),
                VoiceModelFileStatus("b.txt", 20L, null),
            )
        )
        assertTrue(status.neverDownloaded)
        assertFalse(status.ready)
    }

    @Test
    fun neverDownloadedIsFalseWhenAnyFileExists() {
        val status = VoiceModelStatus(
            listOf(
                VoiceModelFileStatus("a.onnx", 10L, null),
                VoiceModelFileStatus("b.txt", 20L, 19L),
            )
        )
        assertFalse(status.neverDownloaded)
    }

    @Test
    fun emptyStatusIsVacuouslyReadyAndNeverDownloaded() {
        // 空列表下 all{} 恒为 true——记录当前语义：真实调用不会传空，聚合状态按模型逐个拼出来
        val status = VoiceModelStatus(emptyList())
        assertTrue(status.ready)
        assertTrue(status.neverDownloaded)
    }
}
