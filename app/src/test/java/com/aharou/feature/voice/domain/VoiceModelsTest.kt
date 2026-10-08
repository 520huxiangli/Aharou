package com.aharou.feature.voice.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceModels：离线语音模型的元数据清单（目录名、下载源、必需文件与字节数）。
 *
 * 纯数据，不碰 Android。这里守的是「清单自洽」——一旦某个必需文件没登记字节数，
 * VoiceModelManager.status 会把它算成 expected=0，实体文件永远对不上，模型就永远显示未就绪。
 */
class VoiceModelsTest {

    @Test
    fun modelRootDirectoryIsVoiceModels() {
        assertEquals("voice_models", VoiceModels.MODEL_ROOT_DIR)
    }

    @Test
    fun allSpecsAreRegistered() {
        val all = VoiceModels.ALL
        assertEquals(3, all.size)
        assertTrue(VoiceModels.KWS_ZH in all)
        assertTrue(VoiceModels.ASR_ZH in all)
        assertTrue(VoiceModels.ASR_ZH_OFFLINE in all)
    }

    @Test
    fun allSpecsHaveDistinctDirectories() {
        val dirs = VoiceModels.ALL.map { it.dirName }
        assertEquals(dirs.size, dirs.distinct().size)
    }

    @Test
    fun everyRequiredFileHasADeclaredSize() {
        for (spec in VoiceModels.ALL) {
            val missing = spec.requiredFiles.filter { it !in spec.fileSizes }
            assertTrue("${spec.dirName} 缺少字节数声明：$missing", missing.isEmpty())
        }
    }

    @Test
    fun everyDeclaredSizeIsPositive() {
        for (spec in VoiceModels.ALL) {
            for ((name, size) in spec.fileSizes) {
                assertTrue("${spec.dirName}/$name 字节数应为正：$size", size > 0)
            }
        }
    }

    @Test
    fun everyRequiredFileListIsNonEmptyAndUnique() {
        for (spec in VoiceModels.ALL) {
            assertTrue("${spec.dirName} 必需文件清单为空", spec.requiredFiles.isNotEmpty())
            assertEquals(
                "${spec.dirName} 必需文件有重复",
                spec.requiredFiles.size,
                spec.requiredFiles.distinct().size,
            )
        }
    }

    @Test
    fun everySpecHasAtLeastOneDownloadUrl() {
        for (spec in VoiceModels.ALL) {
            assertTrue("${spec.dirName} 没有下载地址", spec.urls.isNotEmpty())
        }
    }

    @Test
    fun everyUrlIsHttpsTarBz2() {
        for (spec in VoiceModels.ALL) {
            for (url in spec.urls) {
                assertTrue("$url 不是 https", url.startsWith("https://"))
                assertTrue("$url 不是 tar.bz2", url.endsWith(".tar.bz2"))
            }
        }
    }

    @Test
    fun cnbMirrorIsTriedBeforeGithubFallback() {
        for (spec in VoiceModels.ALL) {
            assertTrue("${spec.dirName} 首选源不是 cnb 镜像：${spec.urls.first()}", spec.urls.first().contains("cnb.cool"))
            assertTrue(
                "${spec.dirName} 缺 GitHub 兜底源",
                spec.urls.last().contains("github.com") && !spec.urls.first().contains("github.com"),
            )
        }
    }

    @Test
    fun directoryNamesAreSinglePathSegments() {
        for (spec in VoiceModels.ALL) {
            assertTrue("${spec.dirName} 目录名含路径分隔符", '/' !in spec.dirName)
            assertTrue(spec.dirName.isNotBlank())
        }
    }

    @Test
    fun displayNamesAreNonBlank() {
        for (spec in VoiceModels.ALL) {
            assertTrue("${spec.dirName} 展示名为空", spec.displayName.isNotBlank())
        }
    }
}
