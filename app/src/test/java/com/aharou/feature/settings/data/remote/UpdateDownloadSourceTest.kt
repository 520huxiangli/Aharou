package com.aharou.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新包下载源解析：资产选择与候选地址顺序。 */
class UpdateDownloadSourceTest {

    @Test
    fun pickApkAsset_prefersUniversalPackage() {
        val names = listOf(
            "Aharou-arm64-v1.13.5.apk",
            "Aharou-universal-v1.13.5.apk",
            "Aharou-universal-v1.13.5-mapping.txt.gz",
        )
        assertEquals("Aharou-universal-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_fallsBackToArm64WhenNoUniversal() {
        val names = listOf("Aharou-x86_64-v1.13.5.apk", "Aharou-arm64-v1.13.5.apk")
        assertEquals("Aharou-arm64-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_fallsBackToAnyApk() {
        val names = listOf("Aharou-1.13.4.apk", "notes.txt")
        assertEquals("Aharou-1.13.4.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_ignoresNonApkAssets() {
        val names = listOf("Aharou-universal-v1.13.5-mapping.txt.gz", "README.md")
        assertNull(UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_returnsNullForEmptyList() {
        assertNull(UpdateDownloadSource.pickApkAsset(emptyList()))
    }

    @Test
    fun candidates_areOrderedGitCodeThenCnbThenProxiesThenGitHub() {
        val list = UpdateDownloadSource.candidates("v1.13.5", "Aharou-universal-v1.13.5.apk")
        val github = "https://github.com/520huxiangli/Aharou/releases/download/" +
            "v1.13.5/Aharou-universal-v1.13.5.apk"

        assertTrue("GitCode 应排第一", list.first().startsWith("https://gitcode.com/Aharou/Aharou/"))
        // cnb 的附件路由比 GitHub/GitCode 多一截 `-`
        assertTrue("cnb 应排第二", list[1].startsWith("https://cnb.cool/huxiangli/aharou-releases/-/releases/download/"))
        assertEquals("GitHub 原链应排最后", github, list.last())
        // 除前两个国内源与末尾原链外，剩下的都是「反代前缀 + GitHub 原链」
        list.drop(2).dropLast(1).forEach { assertTrue(it.endsWith(github)) }
        assertEquals(list.size, list.distinct().size)
    }

    @Test
    fun candidates_carryTagAndAssetName() {
        val list = UpdateDownloadSource.candidates("v1.13.5", "Aharou-arm64-v1.13.5.apk")
        assertTrue(list.all { it.contains("/v1.13.5/Aharou-arm64-v1.13.5.apk") })
    }

    @Test
    fun releasePage_pointsAtTheGivenTag() {
        assertEquals(
            "https://github.com/520huxiangli/Aharou/releases/tag/v1.13.5",
            UpdateDownloadSource.releasePage("v1.13.5")
        )
        assertEquals(
            "https://github.com/520huxiangli/Aharou/releases/latest",
            UpdateDownloadSource.releasePage(null)
        )
    }
}
