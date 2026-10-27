package com.aharou.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新包下载源解析：资产选择与候选地址顺序。 */
class UpdateDownloadSourceTest {

    @Test
    fun pickApkAsset_prefersArm64Package() {
        val names = listOf(
            "Aharou-arm64-v1.13.5.apk",
            "Aharou-universal-v1.13.5.apk",
            "Aharou-universal-v1.13.5-mapping.txt.gz",
        )
        // 正式版发两个单架构包；arm64 设备拿 arm64 包，不拿体积翻倍的 universal
        assertEquals("Aharou-arm64-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_x86DeviceTakesX86Package() {
        val names = listOf("Aharou-arm64-v1.13.5.apk", "Aharou-x86_64-v1.13.5.apk")
        assertEquals(
            "Aharou-x86_64-v1.13.5.apk",
            UpdateDownloadSource.pickApkAsset(names, supportsArm64 = false)
        )
    }

    @Test
    fun pickApkAsset_skipsOtherArchitectureOnly() {
        // 只有其他架构的包：下下来也装不上，宁可返回 null 不上报更新
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-arm64-v1.13.5.apk"), supportsArm64 = false))
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-x86_64-v1.13.5.apk"), supportsArm64 = true))
    }

    @Test
    fun pickApkAsset_fallsBackToUniversal() {
        val names = listOf("Aharou-x86_64-v1.13.5.apk", "Aharou-universal-v1.13.5.apk")
        // universal 兼容所有设备，作为旧版本的兜底
        assertEquals("Aharou-universal-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
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
    fun pickApkAsset_x86DeviceReturnsNullWhenNoUsablePackage() {
        // 列表里根本没有可用的 apk
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("README.md"), supportsArm64 = false))
    }

    @Test
    fun pickApkAsset_returnsNullForEmptyList() {
        assertNull(UpdateDownloadSource.pickApkAsset(emptyList()))
    }

    @Test
    fun candidates_areOrderedCnbThenProxiesThenGitHub() {
        val list = UpdateDownloadSource.candidates("v1.13.5", "Aharou-universal-v1.13.5.apk")
        val github = "https://github.com/520huxiangli/Aharou/releases/download/" +
            "v1.13.5/Aharou-universal-v1.13.5.apk"

        // cnb 的附件路由比 GitHub 多一截 `-`
        assertTrue("cnb 应排第一", list.first().startsWith("https://cnb.cool/huxiangli/aharou-releases/-/releases/download/"))
        assertEquals("GitHub 原链应排最后", github, list.last())
        // 除首个国内源与末尾原链外，剩下的都是「反代前缀 + GitHub 原链」
        list.drop(1).dropLast(1).forEach { assertTrue(it.endsWith(github)) }
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
