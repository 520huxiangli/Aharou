package com.aharou.feature.agent.domain.container

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 从内置 assets/container-images.json 加载可下载镜像目录。
 * 源与镜像分离存储：URL = sources[源][发行版] 前缀 + 条目 path（{abi} 按设备架构替换）。
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var data = ContainerImageCatalogData()

    fun load(): List<ContainerImageEntry> {
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(raw)
        }.getOrNull()
        data = parsed ?: ContainerImageCatalogData()
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
        return "$prefix/${entry.path}".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** 与 [com.aharou.feature.agent.domain.container.ContainerInstaller] 一致的架构判定：x86 设备走 x86_64，其余走 arm64。 */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
