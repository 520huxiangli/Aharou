package com.aharou.feature.agent.domain.container

import android.content.Context
import android.os.Build
import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可下载的镜像条目：[path] 是相对路径，{abi} 占位符由 [ContainerImageCatalog.urlFor] 按架构替换。 */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    /** 常规支持结束日（`yyyy-MM-dd`，endoflife.date 口径）；缺失、空串、格式非法一律按「无数据」处理。 */
    val eol: String? = null,
    /** 延伸支持结束日（Ubuntu 的 ESM、Debian 的 LTS）；只有两段式支持周期的发行版才有，其余为 null。 */
    val supportEnds: String? = null,
    val sizeBytes: Long = 0,
    /** 所属发行版（对应全局 sources 表的键，如 alpine/ubuntu）。 */
    val distro: String = "",
    val path: String,
    /** 按源覆盖的 [path]：源 id → 该源下的相对路径（缺省用 [path]）。用于结构不同的自建源。 */
    val paths: Map<String, String> = emptyMap(),
    /** 标准架构键（arm64/x86_64）→ 该发行版实际目录名（如 aarch64/amd64）。 */
    val abiNames: Map<String, String> = emptyMap(),
    /**
     * 标准架构键（arm64/x86_64）→ 官方公布的 SHA-256，下载完据此校验完整性。
     * 缺该 ABI 的键表示上游不提供校验和（如 Debian 的 Jenkins artifact），此时跳过校验。
     */
    val sha256ByAbi: Map<String, String> = emptyMap()
) {
    /**
     * 名称 + 版本的统一展示串（UI 各处曾各自裸拼 `"$name $version"`）。
     * [version] 传入时以它为准：容器名要用**下载时**记录的版本，而不是目录里已刷新的版本。
     */
    fun displayLabel(version: String? = null): String {
        val shown = version?.takeIf { it.isNotBlank() } ?: this.version
        return listOf(name, shown).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * 按 [now] 判定支持状态，边界口径见 [eolStatusOf]。
     * [now] 可注入：单测用固定日期构造，结果不随运行当天漂移。
     */
    fun eolStatus(now: LocalDate = LocalDate.now()): ContainerImageEolStatus = eolStatusOf(this, now)
}

/**
 * 该条目在 [abi] 下的官方 SHA-256；未提供或为空白返回 null，调用方据此跳过校验而不是当成失败。
 */
fun ContainerImageEntry.sha256For(abi: String): String? =
    sha256ByAbi[abi]?.takeIf { it.isNotBlank() }

/** 常规支持结束前 [EOL_SOON_DAYS] 天以内算「即将停止支持」（含结束当天）。 */
internal const val EOL_SOON_DAYS = 30L

/** 镜像分支的支持状态，供 UI 打标。 */
enum class ContainerImageEolStatus {
    /** 清单没给可用的常规支持结束日，无从判断。 */
    NO_DATA,

    /** 距常规支持结束还早。 */
    SUPPORTED,

    /** 常规支持将在 [EOL_SOON_DAYS] 天内结束（含结束当天）。 */
    ENDING_SOON,

    /** 常规支持已结束，但延伸支持（Ubuntu ESM / Debian LTS）还在。 */
    EXTENDED_ONLY,

    /** 常规支持与延伸支持都已结束。 */
    END_OF_LIFE
}

/**
 * 解析清单里的日期串（`yyyy-MM-dd`）：null、空白、格式不对、日期本身不存在（如 2026-02-30）
 * 一律返回 null，绝不抛异常。
 */
internal fun parseCatalogDate(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

/**
 * 判定 [entry] 在 [now] 当天的支持状态。
 *
 * 边界口径：
 * - `now <= eol` 仍在常规支持期内；距常规支持结束 ≤ [EOL_SOON_DAYS] 天即「即将停止支持」，
 *   **含 eol 当天**（当天算 0 天，仍归即将到期，而不是已停止）；
 * - `now > eol` 且清单给了延伸支持结束日且 `now <= supportEnds` → 仅延伸支持（同样含 supportEnds 当天）；
 * - `now > eol` 而没有延伸支持结束日 → 直接算已停止支持（Alpine、非 LTS Ubuntu 本就没有延伸支持期，
 *   不是「缺数据」）；
 * - 常规支持结束日缺失/非法 → 无数据（此时即便有延伸支持结束日也判不了，因为定不了处在哪一段）。
 */
internal fun eolStatusOf(entry: ContainerImageEntry, now: LocalDate): ContainerImageEolStatus {
    val eol = parseCatalogDate(entry.eol) ?: return ContainerImageEolStatus.NO_DATA
    if (!now.isAfter(eol)) {
        return if (ChronoUnit.DAYS.between(now, eol) <= EOL_SOON_DAYS) {
            ContainerImageEolStatus.ENDING_SOON
        } else {
            ContainerImageEolStatus.SUPPORTED
        }
    }
    val supportEnds = parseCatalogDate(entry.supportEnds)
    return if (supportEnds != null && !now.isAfter(supportEnds)) {
        ContainerImageEolStatus.EXTENDED_ONLY
    } else {
        ContainerImageEolStatus.END_OF_LIFE
    }
}

/**
 * 已下载的文件是否落后于目录当前版本。
 * [downloaded] 为空（升级前留下的旧记录）时返回 false——宁可漏提示，也不能把刚下好的东西误报成「有新版」。
 */
fun hasNewerVersion(downloaded: String?, current: String): Boolean =
    !downloaded.isNullOrBlank() && current.isNotBlank() && downloaded != current

/** 一个下载源：中英文显示名 + 各发行版的前缀（缺失/为空表示该源不提供此发行版）。 */
@Serializable
data class ContainerImageSource(
    val name: Map<String, String> = emptyMap(),
    val distros: Map<String, String> = emptyMap()
)

/** 全局镜像目录：sources（源 id → 定义）+ 镜像列表。 */
@Serializable
data class ContainerImageCatalogData(
    val sources: Map<String, ContainerImageSource> = emptyMap(),
    val images: List<ContainerImageEntry> = emptyList()
)

/**
 * 可下载镜像目录：读取顺序「内存缓存 -> 磁盘已下载文件 -> 内置 assets」。
 *
 * 网络预热由 [refreshFromNetworkIfStale] 在 App 启动阶段后台执行（失败静默），
 * 走 [RepoDataFetcher] 拉本仓库的 `data/container-images.json`（多 CDN 降级 + 磁盘缓存）。
 * 这样加新版本、换源、改地址都只需改仓库里那个 JSON，不必发版。
 *
 * 源与镜像分离存储：URL = sources[源][发行版] 前缀 + 条目 path（{abi} 按设备架构替换）。
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var data = ContainerImageCatalogData()

    @Volatile
    private var cached: ContainerImageCatalogData? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 镜像列表的可观察副本：启动时的刷新是异步的，设置页若先读一次会定格在旧缓存上，
     *  刷新成功后需要有人推它一把才会重绘。 */
    private val _images = MutableStateFlow<List<ContainerImageEntry>>(emptyList())
    val images: StateFlow<List<ContainerImageEntry>> = _images.asStateFlow()

    /**
     * 仅供 App 启动阶段后台协程调用：拉取仓库里的最新清单并更新内存缓存。
     * 解析成功且镜像列表非空才采纳；任何失败都静默，UI 仍走磁盘缓存或内置 assets。
     */
    suspend fun refreshFromNetworkIfStale() = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        // maxAgeMs = 0：跳过 12 小时保鲜期，每次启动都做一次条件请求对账。
        // 清单改动（加新版本、换源）应当立即生效，否则最长滞后 12 小时；
        // 带 ETag，内容未变时服务端回 304，开销仅几十字节。
        val remote = when (val result = runCatching {
            RepoDataFetcher(context).fetch(REMOTE_CATALOG_PATH, maxAgeMs = 0L)
        }.getOrNull()) {
            is RepoDataFetcher.FetchResult.Success -> result.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
            else -> null
        }
        if (remote.isNullOrBlank()) return@withContext

        // 远端清单条目为空时不采纳：空目录会让设置页一个镜像都没有，继续走本地回退。
        val parsed = parseContainerImageCatalog(remote) ?: return@withContext
        if (parsed.images.isEmpty()) return@withContext

        cached = parsed
        _images.value = parsed.images
        FileLogger.d(TAG, "远端镜像目录已更新：${parsed.images.size} 条、${parsed.sources.size} 个源")
    }

    /**
     * UI 专用快速同步读取：内存缓存 -> 磁盘下载文件 -> assets 内置文件。
     * 纯本地 IO，不发任何网络请求。
     */
    fun load(): List<ContainerImageEntry> {
        cached?.let {
            data = it
            _images.value = it.images
            return it.images
        }

        // 磁盘缓存由 RepoDataFetcher 写入，目录与它统一
        val diskRaw = runCatching {
            RepoDataFetcher(context).readLocalCache(REMOTE_CATALOG_PATH)
        }.getOrNull()
        adoptUsable(parseContainerImageCatalog(diskRaw))?.let { return it }

        // 兜底内置 assets（必定存在）
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return adoptAssetFallback(parseContainerImageCatalog(raw))
    }

    /** 网络/磁盘路径的采纳口径：解析成功且清单非空才采纳并置为当前目录，否则返回 null 交给下一级回退。 */
    private fun adoptUsable(parsed: ContainerImageCatalogData?): List<ContainerImageEntry>? {
        if (parsed == null || parsed.images.isEmpty()) return null
        cached = parsed
        data = parsed
        _images.value = parsed.images
        return parsed.images
    }

    /** 内置 assets 兜底：解析失败退回空目录（assets 是最后一档，不再往下让）。 */
    private fun adoptAssetFallback(parsed: ContainerImageCatalogData?): List<ContainerImageEntry> {
        val adopted = parsed ?: ContainerImageCatalogData()
        data = adopted
        _images.value = adopted.images
        return adopted.images
    }

    /** 全局源 id 列表（保持 JSON 顺序），供右上角源切换展示。 */
    val sourceIds: List<String>
        get() = data.sources.keys.toList()

    /** 源显示名（按语言键 zh/en 取，缺省回退任意可用名）；源不存在返回 null。 */
    fun sourceName(sourceId: String, lang: String): String? {
        val names = data.sources[sourceId]?.name ?: return null
        return names[lang] ?: names.values.firstOrNull()
    }

    /** 当前设备架构下，[entry] 指定源的完整下载 URL；源/发行版/架构缺失返回 null。 */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? {
        val prefix = data.sources[sourceId]?.distros?.get(entry.distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = entry.abiNames[abi] ?: return null
        val path = entry.paths[sourceId] ?: entry.path
        return "$prefix/$path".replace("{abi}", abiName)
    }

    companion object {
        private const val TAG = "ContainerImageCatalog"
        private const val ASSET_FILE = "container-images.json"

        /** 统一拉取器中的仓库相对路径：本仓库维护的可下载镜像目录。 */
        private const val REMOTE_CATALOG_PATH = "data/container-images.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析一份清单 JSON：非法 JSON / 结构不符返回 null，解析成功则返回目录对象
         * （空清单也解析成功，images 为空，是否采纳由调用方按各级回退的现状决定）。
         * [load] 的三级回退与 [refreshFromNetworkIfStale] 共用这一处。
         */
        internal fun parseContainerImageCatalog(raw: String?): ContainerImageCatalogData? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                json.decodeFromString<ContainerImageCatalogData>(raw)
            }.getOrNull()
        }

        /** 与 [com.aharou.feature.agent.domain.container.ContainerInstaller] 一致的架构判定：x86 设备走 x86_64，其余走 arm64。 */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
