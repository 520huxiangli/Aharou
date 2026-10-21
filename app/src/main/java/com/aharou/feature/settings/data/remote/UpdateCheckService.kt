package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.R
import com.aharou.feature.settings.data.repository.UpdateChannel
import com.aharou.feature.settings.presentation.component.compareVersions
import com.aharou.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 单个版本的更新日志。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志 + 可下载的安装包资产名。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>,
    /** 最新版里挑出的 APK 资产名（无 apk 资产时为 null）。 */
    val apkAssetName: String? = null,
    /** 该资产的字节数，用于校验下载完整性；0 表示未知。 */
    val apkAssetSize: Long = 0L,
    /** 该资产的 sha256（已剥掉 `sha256:` 前缀）；null 表示来源不给，跳过校验。 */
    val apkAssetDigest: String? = null
)

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * 拉取版本信息并生成更新日志。
 *
 * 来源有三个，按序尝试：**cnb 发布仓**（国内直连可用、含资产哈希，且包就在那儿，版本与下载源同源）
 * → **反代转发的 GitHub API**（直连被限速时的同源兜底）→ **GitHub 直连**。全失败才报网络错误。
 *
 * 历史上还挂过 GitCode 镜像当兑底，2026-10-18 已移除：那个镜像早停更，它「成功」返回旧列表
 * 会让检查更新误判成「已是最新」（真实假阴性）。
 *
 * 两个通道各收各的：正式版通道只出正式版（且需严格高于当前版本），测试版通道只出预发布
 * （beta 与 RC）。更新日志按版本从高到低拼接（版本号 + 正文，轻量清理 markdown 标题/粗体）。
 * 结果不落盘，由调用方按需展示。
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // 源顺序：cnb（国内直连可用、含资产哈希）→ 反代转发 GitHub → GitHub 直连。
                // 不拿 GitCode 当兜底：那个镜像早已停更，它「成功」返回一份旧列表会让本函数
                // 误判成「已是最新」—— 2026-10-18 就是这么坑了一次。
                val releases = fetchCnbReleases()?.takeIf { it.isNotEmpty() }
                    ?: fetchGithubProxiedReleases()?.takeIf { it.isNotEmpty() }
                    ?: fetchGithubReleases()?.takeIf { it.isNotEmpty() }
                    ?: return@withContext UpdateCheckResult.Error(
                        context.getString(R.string.about_network_error)
                    )

                // 两个通道各收各的：正式版通道只出正式版，测试版通道只出测试版（beta 与 RC）。
                // 测试版不做版本比较 —— beta 的 tag 是固定名（beta-latest），不是版本号，
                // 比不了；而且测试包本来就该每次都装最新的。
                val updates = when (channel) {
                    UpdateChannel.STABLE -> releases
                        .filter { !it.prerelease }
                        .filter { compareVersions(it.version, currentVersion) > 0 }
                        .sortedWith { a, b -> compareVersions(b.version, a.version) }
                    // 只取最近一个：预发布里混着大量历史 dev 版，全拼进更新日志没意义。
                    UpdateChannel.LATEST -> releases.filter { it.prerelease }.take(1)
                }

                if (updates.isEmpty()) {
                    UpdateCheckResult.UpToDate
                } else {
                    val versionUpdates = updates.map { toVersionUpdate(it) }
                    val latest = updates.first()
                    var apkAsset = latest.assets.firstOrNull {
                        it.name == UpdateDownloadSource.pickApkAsset(latest.assets.map { a -> a.name })
                    }
                    // 主源（cnb）的资产可能晚于 release 创建几秒到几分钟同步到位：那一刻列表里有更新
                    // 日志却没有包，直接弹「发现新版本」会让下载必然失败（报 no apk asset）。
                    // 这里按 tag 回退到 GitHub 源再找一次。
                    if (apkAsset == null) {
                        val fallback = (fetchGithubProxiedReleases() ?: fetchGithubReleases())
                            ?.firstOrNull { it.rawTag == latest.rawTag }
                        val fallbackNames = fallback?.assets?.map { it.name }.orEmpty()
                        apkAsset = fallback?.assets?.firstOrNull {
                            it.name == UpdateDownloadSource.pickApkAsset(fallbackNames)
                        }
                    }
                    UpdateCheckResult.NewVersion(
                        UpdateInfo(
                            latestTag = versionUpdates.first().tag,
                            changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                            updates = versionUpdates,
                            apkAssetName = apkAsset?.name,
                            apkAssetSize = apkAsset?.size ?: 0L,
                            apkAssetDigest = apkAsset?.digest
                        )
                    )
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    /**
     * 主源：cnb 发布仓的 release 列表。
     *
     * 选它当主源有两个实际原因：国内直连可用（GitHub 常被限流 / DNS 污染），以及包就在 cnb，
     * 版本信息与下载源同源，不会出现「查到有新版、下载源却没有这个包」的错位。
     *
     * 用的是网页路由 `cnb.cool/{owner}/{repo}/-/releases`（匿名可读），不是 `api.cnb.cool`
     * 那套（那套一律要令牌）。
     */
    private fun fetchCnbReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url(CNB_RELEASES_API)
            .header("Accept", "application/json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { cnbRelease(it) }
        }
    }.getOrNull()

    /** 二级源：反代转发的 GitHub API——数据与直连同源，用于直连被限速（403 / 429）时兜底。 */
    private fun fetchGithubProxiedReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url(GITHUB_PROXY_API + GITHUB_RELEASES_API + "?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { githubRelease(it) }
        }
    }.getOrNull()

    /**
     * 末位兜底：GitHub Releases API 直连。
     *
     * 拿不到（网络不通、限流、解析失败）返回 null，由调用方换源；不在这里区分失败原因——
     * 所有源都失败时对用户来说都是「网络不通」。
     */
    private fun fetchGithubReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url("$GITHUB_RELEASES_API?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { githubRelease(it) }
        }
    }.getOrNull()

    /** 把响应体解析成 release 列表；不是 JSON 数组或整体解析失败时返回 null。 */
    private fun parseReleases(body: String?, mapper: (JsonObject) -> ReleaseInfo?): List<ReleaseInfo>? =
        runCatching { JsonParser.parseString(body.orEmpty()).asJsonArray }
            .getOrNull()
            ?.mapNotNull { el -> runCatching { mapper(el.asJsonObject) }.getOrNull() }

    private fun githubRelease(obj: JsonObject): ReleaseInfo? {
        val tag = obj.get("tag_name")?.asString ?: return null
        val version = parseVersionTag(tag) ?: return null
        return ReleaseInfo(
            version = version,
            rawTag = tag,
            notes = obj.get("body")?.asString.orEmpty(),
            prerelease = obj.get("prerelease")?.asBoolean ?: false,
            assets = obj.getAsJsonArray("assets")?.mapNotNull { a ->
                val asset = a.asJsonObject
                val name = asset.get("name")?.asString ?: return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    size = asset.get("size")?.asLong ?: 0L,
                    // GitHub 给每个资产带 digest（形如 sha256:xxxx），它来自 GitHub 自己的存储层，
                    // 与 cnb / 反代这些下载源不同源，所以能当下载完整性校验的信任锚。
                    digest = asset.get("digest")?.asString
                        ?.removePrefix("sha256:")
                        ?.takeIf { it.isNotBlank() }
                )
            } ?: emptyList()
        )
    }

    /**
     * cnb 的 release JSON：字段名与 GitHub 大致同名，资产另给 `hash_value` / `hash_algo`。
     *
     * 那个哈希来自 cnb 自己的存储层、与我们的下载源同源，所以**只能防传输损坏、不是信任锚**
     * （GitHub 的 digest 才是）；有就带上，没有就留空跳过校验。
     */
    private fun cnbRelease(obj: JsonObject): ReleaseInfo? {
        val tag = obj.get("tag_name")?.asString ?: return null
        val version = parseVersionTag(tag) ?: return null
        return ReleaseInfo(
            version = version,
            rawTag = tag,
            notes = obj.get("body")?.asString.orEmpty(),
            prerelease = obj.get("prerelease")?.asBoolean ?: false,
            assets = obj.getAsJsonArray("assets")?.mapNotNull { a ->
                val asset = a.asJsonObject
                val name = asset.get("name")?.asString ?: return@mapNotNull null
                val sha256 = asset.get("hash_value")?.asString
                    ?.removePrefix("sha256:")
                    ?.takeIf { it.isNotBlank() && asset.get("hash_algo")?.asString.equals("sha256", true) }
                ReleaseAsset(
                    name = name,
                    size = asset.get("size")?.asLong ?: 0L,
                    digest = sha256
                )
            } ?: emptyList()
        )
    }

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            }
        )
    }

    /** 轻量清理 markdown：去掉行首标题符与粗体标记，并删除 GitHub 自动生成的标题/链接行，保留纯文本便于直接阅读。 */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean,
        val assets: List<ReleaseAsset> = emptyList()
    )

    /** release 里的单个资产（只关心发布包本身）。 */
    private data class ReleaseAsset(val name: String, val size: Long, val digest: String? = null)

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/520huxiangli/Aharou/releases"

        /** cnb 发布仓的 release 列表（网页路由，匿名可读；`api.cnb.cool` 那套要令牌，别用混）。 */
        const val CNB_RELEASES_API = "https://cnb.cool/huxiangli/aharou-releases/-/releases"

        /** GitHub API 反代前缀：直连被限速时用它拿同一份数据。 */
        const val GITHUB_PROXY_API = "https://gh-proxy.com/"

        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aharou.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
