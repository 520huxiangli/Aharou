package com.aharou.feature.settings.data.remote

/**
 * 更新包下载源解析：把 tag + 资产名展开成**有序候选 URL**。
 *
 * 优先国内可达的来源（cnb 发布仓 → GitHub 文件反代），最后回退 GitHub 原链；
 * GitCode 已于 2026-10-08 按主人要求停用（同步也停了，那边不会再更新，不再作候选）。
 * 调用方逐个探测，第一个可达的即用于下载——任一来源失效都会自动降级，
 * 不需要联网失败重试逻辑。
 *
 * 纯字符串运算，无 Android 依赖，便于单测。
 */
object UpdateDownloadSource {

    const val GITHUB_OWNER = "520huxiangli"
    const val GITHUB_REPO = "Aharou"

    private const val GITHUB_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /**
     * cnb 发布仓（`huxiangli/aharou-releases`，2026-10-08 建）。
     *
     * 只放发布包、不放代码，附件由发版流水线同步（scripts/sync-cnb-releases.py）。
     * 它的附件路由是 `/-/releases/download/<tag>/<file>`（多一截 `-`），与 GitHub/GitCode 不同；
     * 实测公开仓库的附件匿名可直接下载，因此文件名与 GitHub 保持一致，URL 用同一套 tag + 资产名拼。
     */
    private const val CNB_BASE = "https://cnb.cool/huxiangli/aharou-releases"

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
     * 正式版发两个单架构包（`Aharou-arm64-*` / `Aharou-x86_64-*`），**按设备架构挑对应的那个**：
     * 两个包各只含一套容器镜像，比 universal 的双套装小一半，用户只下自己那份。
     *
     * 其他架构的包不选——下下来也装不上；universal 兼容所有设备，仅作旧版本的兜底。
     * 只有其它架构的包时返回 null，宁可不上报更新。
     */
    fun pickApkAsset(names: List<String>, supportsArm64: Boolean = true): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        val preferred = if (supportsArm64) "arm64" else "x86_64"
        val other = if (supportsArm64) "x86_64" else "arm64"
        apks.firstOrNull { it.contains(preferred, ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.contains("universal", ignoreCase = true) }?.let { return it }
        if (apks.any { it.contains(other, ignoreCase = true) }) return null
        // 没有架构标识的旧包名（如 Aharou-1.13.4.apk）：没有更可靠的判据，当作通用包
        return apks.firstOrNull()
    }

    /** 供下载的候选地址，按优先级从高到低。 */
    fun candidates(tag: String, assetName: String): List<String> {
        val github = "$GITHUB_BASE/releases/download/$tag/$assetName"
        return buildList {
            // cnb 排第一：实测匿名可下、4~5MB/s，且它只同步「当次发版」的资产，不会像
            // GitCode 那样落后好几个大版本。
            add("$CNB_BASE/-/releases/download/$tag/$assetName")
            PROXIES.forEach { add(it + github) }
            add(github)
        }
    }

    /** release 页面地址：所有来源都失败时的兜底出口。 */
    fun releasePage(tag: String?): String =
        if (tag.isNullOrBlank()) "$GITHUB_BASE/releases/latest" else "$GITHUB_BASE/releases/tag/$tag"
}
