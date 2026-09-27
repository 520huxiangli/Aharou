package com.aharou.feature.settings.data.remote

/**
 * 更新包下载源解析：把 tag + 资产名展开成**有序候选 URL**。
 *
 * 优先国内可达的来源（GitCode 镜像 → GitHub 文件反代），最后回退 GitHub 原链；
 * 调用方逐个探测，第一个可达的即用于下载——任一来源失效都会自动降级，
 * 不需要联网失败重试逻辑。
 *
 * 纯字符串运算，无 Android 依赖，便于单测。
 */
object UpdateDownloadSource {

    const val GITHUB_OWNER = "520huxiangli"
    const val GITHUB_REPO = "Aharou"

    /** 本仓库发版资产名的统一前缀。 */
    const val ASSET_PREFIX = "Aharou-"

    private const val GITHUB_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /**
     * GitCode 镜像仓库。注意它是**另一个账号**下的同名仓库（2026-09-27 建仓），
     * 路径与 GitHub 不同名，别把两者混用。代码/tag/Release 资产均已同步过去。
     */
    private const val GITCODE_OWNER = "Aharou"
    private const val GITCODE_BASE = "https://gitcode.com/$GITCODE_OWNER/$GITHUB_REPO"

    /**
     * GitHub 文件反代，用法是「前缀 + 完整 GitHub URL」。
     *
     * 顺序按 2026-09-27 本机实测排：gh-proxy.com ≈ 4 MB/s、v6.gh-proxy.org ≈ 1 MB/s、
     * ghfast.top 仅 ≈ 0.04 MB/s（与直连 GitHub 相当，已接近不可用，故放最后兜底）。
     */
    private val PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://v6.gh-proxy.org/",
        "https://ghfast.top/",
    )

    /**
     * 从 release 的资产名里挑要下载的 APK。
     *
     * universal 包含 arm + x86 两套容器镜像、兼容所有设备，优先；只有单架构包时
     * 选 arm64（真机主流）；再不行取第一个 APK，保证「有新版本就下得到东西」。
     */
    fun pickApkAsset(names: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { it.contains("arm64", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    /** 供下载的候选地址，按优先级从高到低。 */
    fun candidates(tag: String, assetName: String): List<String> {
        val github = "$GITHUB_BASE/releases/download/$tag/$assetName"
        return buildList {
            add("$GITCODE_BASE/releases/download/$tag/$assetName")
            PROXIES.forEach { add(it + github) }
            add(github)
        }
    }

    /** release 页面地址：所有来源都失败时的兜底出口。 */
    fun releasePage(tag: String?): String =
        if (tag.isNullOrBlank()) "$GITHUB_BASE/releases/latest" else "$GITHUB_BASE/releases/tag/$tag"
}
