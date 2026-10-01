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
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可下载的镜像条目：[path] 是相对路径，{abi} 占位符由 [ContainerImageCatalog.urlFor] 按架构替换。 */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val sizeBytes: Long = 0,
    /** 所属发行版（对应全局 sources 表的键，如 alpine/ubuntu）。 */
    val distro: String = "",
    val path: String,
    /** 按源覆盖的 [path]：源 id → 该源下的相对路径（缺省用 [path]）。用于结构不同的自建源。 */
    val paths: Map<String, String> = emptyMap(),
    /** 标准架构键（arm64/x86_64）→ 该发行版实际目录名（如 aarch64/amd64）。 */
    val abiNames: Map<String, String> = emptyMap()
)

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

        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(remote)
        }.getOrNull()
        if (parsed != null && parsed.images.isNotEmpty()) {
            cached = parsed
            _images.value = parsed.images
            FileLogger.d(TAG, "远端镜像目录已更新：${parsed.images.size} 条、${parsed.sources.size} 个源")
        }
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
        if (!diskRaw.isNullOrBlank()) {
            val parsed = runCatching {
                json.decodeFromString<ContainerImageCatalogData>(diskRaw)
            }.getOrNull()
            if (parsed != null && parsed.images.isNotEmpty()) {
                cached = parsed
                data = parsed
                _images.value = parsed.images
                return parsed.images
            }
        }

        // 兜底内置 assets（必定存在）
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(raw)
        }.getOrNull()
        data = parsed ?: ContainerImageCatalogData()
        _images.value = data.images
        return data.images
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

        /** 与 [com.aharou.feature.agent.domain.container.ContainerInstaller] 一致的架构判定：x86 设备走 x86_64，其余走 arm64。 */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
