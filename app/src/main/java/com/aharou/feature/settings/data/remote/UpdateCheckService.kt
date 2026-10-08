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
    val apkAssetSize: Long = 0L
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
 * 来源有两个，按序尝试：GitHub Releases API 为主，失败（国内常被 DNS 污染/超时）时回退
 * GitCode 镜像仓库的 API——它匿名可读、字段与 GitHub 近似，代价是**只同步了正式版**
 * （RC 在 GitCode 上标不出预发布），所以测试版通道实际只有 GitHub 一条路。
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
                val releases = fetchGithubReleases()?.takeIf { it.isNotEmpty() }
                    ?: fetchGitcodeReleases()?.takeIf { it.isNotEmpty() }
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
                    val apkAsset = latest.assets.firstOrNull {
                        it.name == UpdateDownloadSource.pickApkAsset(latest.assets.map { a -> a.name })
                    }
                    UpdateCheckResult.NewVersion(
                        UpdateInfo(
                            latestTag = versionUpdates.first().tag,
                            changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                            updates = versionUpdates,
                            apkAssetName = apkAsset?.name,
                            apkAssetSize = apkAsset?.size ?: 0L
                        )
                    )
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    /**
     * 主源：GitHub Releases API。
     *
     * 拿不到（网络不通、限流、解析失败）返回 null，由调用方换源；不在这里区分失败原因——
     * 两个源都失败时对用户来说都是「网络不通」。
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

    /** 兜底源：GitCode 镜像仓库（`Aharou/Aharou`）。 */
    private fun fetchGitcodeReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url("$GITCODE_RELEASES_API?per_page=50")
            .header("Accept", "application/json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { gitcodeRelease(it) }
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
                ReleaseAsset(name = name, size = asset.get("size")?.asLong ?: 0L)
            } ?: emptyList()
        )
    }

    /**
     * GitCode 的 release JSON：字段与 GitHub 大致同名，但附件**不给大小**（size 记 0，
     * 下载时不校验完整性），而且附件列表里混着源码包的 tar/zip——挑 APK 由
     * [UpdateDownloadSource.pickApkAsset] 按扩展名过滤，这里不额外筛。
     */
    private fun gitcodeRelease(obj: JsonObject): ReleaseInfo? {
        val tag = obj.get("tag_name")?.asString ?: return null
        val version = parseVersionTag(tag) ?: return null
        return ReleaseInfo(
            version = version,
            rawTag = tag,
            notes = obj.get("body")?.asString.orEmpty(),
            prerelease = obj.get("prerelease")?.asBoolean ?: false,
            assets = obj.getAsJsonArray("assets")?.mapNotNull { a ->
                a.asJsonObject.get("name")?.asString?.let { ReleaseAsset(name = it, size = 0L) }
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
    private data class ReleaseAsset(val name: String, val size: Long)

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/520huxiangli/Aharou/releases"

        /** GitCode 镜像仓库（另一账号下的同名仓库，见 UpdateDownloadSource 的说明）。 */
        const val GITCODE_RELEASES_API = "https://api.gitcode.com/api/v5/repos/Aharou/Aharou/releases"

        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aharou.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
