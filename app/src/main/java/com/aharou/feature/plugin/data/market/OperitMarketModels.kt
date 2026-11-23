/*
 * 市场数据结构对齐 AAswordman/Operit（LGPL-3.0）的
 * `app/src/main/java/com/ai/assistance/operit/data/api/MarketStatsApiService.kt`（MarketV2* DTO）。
 * 改动：只保留浏览与安装所需字段；分类清单单独建模；资产按 latestVersion 精确匹配。
 */
package com.aharou.feature.plugin.data.market

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val OperitMarketJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

/** 市场分类清单；分类的展示顺序以这里为准，不在本地猜。 */
@Serializable
internal data class OperitMarketManifest(
    val ok: Boolean = false,
    val categories: List<OperitMarketManifestCategory>? = null
)

@Serializable
internal data class OperitMarketManifestCategory(
    val id: String = "",
    val name: String? = null
)

/** 一页列表数据；`ok` 为 false 时整页都不可信，由客户端转成显式错误。 */
@Serializable
internal data class OperitMarketPage(
    val ok: Boolean = false,
    @SerialName("marketVersion") val marketVersion: Int = 0,
    @SerialName("generatedAt") val generatedAt: String? = null,
    val page: Int = 0,
    @SerialName("pageSize") val pageSize: Int = 0,
    val total: Int = 0,
    val items: List<OperitMarketEntry>? = null
)

@Serializable
internal data class OperitMarketEntry(
    val type: String? = null,
    val id: String = "",
    val title: String? = null,
    val description: String? = null,
    val detail: String? = null,
    @SerialName("categoryId") val categoryId: String? = null,
    @SerialName("downloadCount") val downloadCount: Long? = null,
    val downloads: Long? = null,
    val author: OperitMarketParty? = null,
    val publisher: OperitMarketParty? = null,
    @SerialName("latestVersion") val latestVersion: OperitMarketVersion? = null,
    val versions: List<OperitMarketVersion>? = null,
    val assets: List<OperitMarketAssetDto>? = null
)

@Serializable
internal data class OperitMarketParty(
    val id: String? = null,
    val login: String? = null
)

@Serializable
internal data class OperitMarketVersion(
    val id: String? = null,
    val version: String? = null,
    @SerialName("formatVer") val formatVer: String? = null
)

/**
 * 一个可下载资产。[kind] 实测有 `github_release_asset` 与 `github_release` 两种，
 * 两者 `url` 都是 Release 直链，所以不按 kind 过滤，只看地址是否可用。
 */
@Serializable
internal data class OperitMarketAssetDto(
    val id: String? = null,
    @SerialName("versionId") val versionId: String? = null,
    val kind: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    @SerialName("assetName") val assetName: String? = null
)

/** 市场条目类型；`UNKNOWN` 留给以后新增的类型，界面按「可能无法安装」提示。 */
enum class OperitMarketType {
    PACKAGE,
    SCRIPT,
    SKILL,
    MCP,
    UNKNOWN;

    companion object {
        fun from(raw: String?): OperitMarketType = when (raw?.trim()?.lowercase()) {
            "package" -> PACKAGE
            "script" -> SCRIPT
            "skill" -> SKILL
            "mcp" -> MCP
            else -> UNKNOWN
        }
    }
}

/** 列表范围：只有「全部」与「某个分类」；分类分页由市场服务端算好，不在本地筛。 */
data class OperitMarketListScope(val categoryId: String? = null) {
    val cacheKey: String get() = categoryId?.let { "category-$it" } ?: ALL_KEY

    private companion object {
        const val ALL_KEY = "all"
    }
}

/** 最新版本对应的下载资产；`sha256` 缺失时保留空串，由下载阶段判定失败。 */
data class OperitMarketAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val versionId: String
)

/**
 * 界面直接消费的条目：把嵌套的 author/latestVersion/assets 展开成一层，
 * 省得每个调用点各写一遍取值兜底。
 */
data class OperitMarketListing(
    val id: String,
    val title: String,
    val description: String,
    val detail: String,
    val type: OperitMarketType,
    val categoryId: String?,
    val author: String,
    val version: String?,
    val downloadCount: Long,
    val asset: OperitMarketAsset?
) {
    val installable: Boolean get() = asset != null
}

/** 分类按钮的候选；[fallbackName] 是市场自己的英文名，用来给不认识的新分类兜底。 */
data class OperitMarketCategoryOption(val id: String, val fallbackName: String)

/** 一页成功读取的结果；[stale] 表示来自本地缓存（网络不可用或缓存未过期）。 */
data class OperitMarketBatch(
    val listings: List<OperitMarketListing>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val totalPages: Int,
    val generatedAt: String?,
    val fetchedAt: Long,
    val stale: Boolean
)

sealed interface OperitMarketPageResult {
    data class Loaded(val batch: OperitMarketBatch) : OperitMarketPageResult

    /** 页码超过市场快照长度（HTTP 404）；分页到此结束，不算失败。 */
    data object End : OperitMarketPageResult
}

/** 一次下载的结果。 */
data class OperitMarketDownload(
    val bytes: ByteArray,
    val assetName: String
)

/** 市场数据不可用；消息是给用户看的可读原因。 */
class OperitMarketException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 条目没有可下载资产（技能 / MCP 条目实测都是这样）。 */
class OperitMarketNoAssetException : IOException("该条目没有可下载的安装包")

class OperitMarketChecksumException(val expected: String, val actual: String) :
    IOException("SHA-256 不一致：期望 $expected，实际 $actual")

class OperitMarketTooLargeException(val limitBytes: Long) :
    IOException("安装包超过大小上限（$limitBytes 字节）")

internal fun OperitMarketManifest.toCategories(): List<OperitMarketCategoryOption> =
    categories.orEmpty()
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .map { OperitMarketCategoryOption(it.id, it.name?.trim().orEmpty()) }

internal fun OperitMarketEntry.toListing(): OperitMarketListing? {
    if (id.isBlank()) return null
    return OperitMarketListing(
        id = id,
        title = title?.trim().orEmpty().ifEmpty { id },
        description = description?.trim().orEmpty(),
        detail = detail?.trim().orEmpty(),
        type = OperitMarketType.from(type),
        categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
        author = author?.login?.trim().orEmpty().ifEmpty { publisher?.login?.trim().orEmpty() },
        version = latestVersion?.version?.trim()?.takeIf { it.isNotEmpty() },
        downloadCount = downloadCount ?: downloads ?: 0L,
        asset = resolveLatestAsset()
    )
}

/**
 * 资产的顺序不可信（实测同一插件有倒序、也有按字符串排序的），
 * 所以先按 `latestVersion.id` 精确匹配，对不上才退回最后一条。
 */
internal fun OperitMarketEntry.resolveLatestAsset(): OperitMarketAsset? {
    val usable = assets.orEmpty().filter { !it.url.isNullOrBlank() }
    if (usable.isEmpty()) return null
    val latestId = latestVersion?.id
    val matched = if (latestId != null) usable.filter { it.versionId == latestId } else emptyList()
    val chosen = matched.lastOrNull() ?: usable.last()
    val url = chosen.url?.trim().orEmpty()
    if (url.isEmpty()) return null
    return OperitMarketAsset(
        name = chosen.assetName?.trim()?.takeIf { it.isNotEmpty() }
            ?: url.substringAfterLast('/'),
        url = url,
        sha256 = chosen.sha256?.trim().orEmpty(),
        versionId = chosen.versionId.orEmpty()
    )
}
