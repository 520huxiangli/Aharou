package com.aharou.feature.settings.presentation

import android.content.Context
import android.net.Uri
import com.aharou.R
import com.aharou.feature.agent.domain.container.ContainerImageCatalog
import com.aharou.feature.agent.domain.container.ContainerImageEntry
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.agent.domain.container.sha256For
import com.aharou.feature.agent.domain.container.sha256For
import com.aharou.feature.agent.domain.container.sha256For
import com.aharou.feature.settings.data.remote.ContainerImageDownloader
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.DownloadedImageRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 容器镜像的目录、下载与已下载记录。
 *
 * 从 `SettingsViewModel` 整块搬出：这一块自己带一串 Room / DataStore 订阅、下载 Job 和
 * 六个界面状态，与设置页其余开关没有耦合。搬走后主 ViewModel 不再持有镜像相关符号。
 *
 * 生命周期：单例常驻，[ensureStarted] 后才开始收集（设置页首次建 ViewModel 时调用）。
 */
@Singleton
class ContainerImageController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: ContainerImageCatalog,
    private val containerSettings: ContainerSettingsRepository,
    private val downloader: ContainerImageDownloader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _imageCatalog = MutableStateFlow<List<ContainerImageEntry>>(emptyList())
    val imageCatalog: StateFlow<List<ContainerImageEntry>> = _imageCatalog.asStateFlow()

    /** 全局下载源 id 列表（来自目录 JSON 的 sources 键，保持顺序），供右上角切换。 */
    private val _sourceOptions = MutableStateFlow<List<String>>(emptyList())
    val sourceOptions: StateFlow<List<String>> = _sourceOptions.asStateFlow()

    /** 当前选中的下载源（默认 Aharou 自建源；清单里没有该源时自动兜底到其他可用源）。 */
    private val _selectedSource = MutableStateFlow("aharou")
    val selectedSource: StateFlow<String> = _selectedSource.asStateFlow()

    /** 已下载/已安装的镜像记录（entryId → 记录），跨重启保留。 */
    private val _downloaded = MutableStateFlow<Map<String, DownloadedImageRecord>>(emptyMap())
    val downloaded: StateFlow<Map<String, DownloadedImageRecord>> = _downloaded.asStateFlow()

    /** 当前选中源下无下载 URL 的镜像 id（如腾讯云不提供 Ubuntu），UI 据此禁用下载按钮。 */
    private val _unavailableIds = MutableStateFlow<Set<String>>(emptySet())
    val unavailableIds: StateFlow<Set<String>> = _unavailableIds.asStateFlow()

    private val _download = MutableStateFlow<ContainerImageDownloadUiState>(ContainerImageDownloadUiState.Idle)
    val download: StateFlow<ContainerImageDownloadUiState> = _download.asStateFlow()

    /** 当前下载任务句柄，取消下载时 cancel 它（底层 OkHttp call 同步中断）。 */
    private var downloadJob: Job? = null

    fun ensureStarted() {
        if (started) return
        started = true
        _imageCatalog.value = catalog.load()
        // 启动时的远端刷新是异步的：上面的 load() 可能先读到旧缓存并定格，
        // 刷新成功后需要跟随更新，否则列表一直显示旧内容（含空初值，故忽略空表）。
        scope.launch {
            catalog.images.collect { list ->
                if (list.isNotEmpty()) _imageCatalog.value = list
            }
        }
        _sourceOptions.value = catalog.sourceIds
        _selectedSource.value = catalog.sourceIds.firstOrNull { it == "aharou" }
            ?: catalog.sourceIds.firstOrNull { it == "official" }
            ?: catalog.sourceIds.firstOrNull() ?: "aharou"
        // 已下载/已安装记录跨重启保留
        scope.launch {
            containerSettings.downloadedImagesFlow.collect { records ->
                _downloaded.value = records.associateBy { it.entryId }
            }
        }
        // 当前源下不可用的镜像集合（源切换后自动更新）
        scope.launch {
            combine(_imageCatalog, _selectedSource) { entries, sourceId ->
                entries.filter { catalog.urlFor(it, sourceId, ContainerImageCatalog.CURRENT_ABI) == null }
                    .map { it.id }.toSet()
            }.collect { _unavailableIds.value = it }
        }
    }

    /** 切换全局下载源（官方/华为云/阿里云/腾讯云等）。 */
    fun setSource(sourceId: String) {
        _selectedSource.value = sourceId
    }

    /** 源显示名：按界面语言取目录 JSON 里定义的 name，跟随系统语言时按系统语言。 */
    fun sourceDisplayName(sourceId: String, languageTag: String?): String {
        val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
        val key = if (lang.startsWith("zh", ignoreCase = true)) "zh" else "en"
        return catalog.sourceName(sourceId, key) ?: sourceId
    }

    /** 开始下载镜像：按当前架构与全局源拼 URL，进度实时写入 [download]。 */
    fun startDownload(entry: ContainerImageEntry, sourceId: String) {
        if (_download.value is ContainerImageDownloadUiState.Downloading) return
        val url = catalog.urlFor(entry, sourceId, ContainerImageCatalog.CURRENT_ABI) ?: return
        val suffix = when {
            url.endsWith(".tar.gz") -> "tar.gz"
            url.endsWith(".tar.xz") || url.endsWith(".txz") -> "tar.xz"
            url.endsWith(".tgz") -> "tgz"
            else -> "tar.gz"
        }
        val fileName = "download_${entry.id}_${entry.version}_${System.currentTimeMillis()}.$suffix"
        // 官方/自建源拿清单里的哈希强制校验；第三方源与无哈希的条目（Debian）传 null 跳过
        val expectedSha256 = entry.sha256For(ContainerImageCatalog.CURRENT_ABI)
            .takeIf { sourceId in CHECKSUM_SOURCES }
        downloadJob = scope.launch {
            _download.value =
                ContainerImageDownloadUiState.Downloading(entry.id, entry.name, entry.version, sourceId, 0, 0)
            try {
                val uri = downloader.download(url, fileName, expectedSha256) { read, total ->
                    _download.value = ContainerImageDownloadUiState.Downloading(
                        entry.id, entry.name, entry.version, sourceId, read, total
                    )
                }
                // 下载完成即持久化记录，安装与否都保留，跨重启可见。版本一并记下：目录版本会随上游刷新，
                // 磁盘上的文件不会跟着变，靠它才能提示「有新版」而不是一直显示「已下载」。
                containerSettings.upsertDownloadedImage(
                    DownloadedImageRecord(entry.id, uri, version = entry.version)
                )
                _download.value =
                    ContainerImageDownloadUiState.Done(entry.id, entry.name, entry.version, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = ContainerImageDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                _download.value = ContainerImageDownloadUiState.Error(
                    entry.id,
                    e.message ?: context.getString(R.string.container_download_failed, "")
                )
            }
        }
    }

    /** 取消正在进行的下载（半成品文件由下载器清理）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * 把已下载的镜像作为自定义 profile 导入容器列表（可重复导入，每次新建 profile），
     * 后续流程与手动导入一致。
     */
    fun importDownloaded(entryId: String, fileUri: String) {
        val entry = _imageCatalog.value.firstOrNull { it.id == entryId } ?: return
        val record = _downloaded.value[entryId]
        scope.launch {
            val profile = ContainerProfile(
                id = "custom-${System.currentTimeMillis()}",
                // 用**下载时**记录的版本命名：目录版本可能已被上游刷新，而磁盘上的文件不会跟着变
                name = entry.displayLabel(record?.version?.takeIf { it.isNotBlank() }),
                rootfsSource = RootfsSource.LocalFile(fileUri),
                shellPath = null,
                extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS,
                isBuiltin = false
            )
            containerSettings.upsertCustomProfile(profile)
            // 保留已下载记录（含下载时的版本号，供「有新版」判定），仅标记已导入；UI 状态不消失。
            containerSettings.upsertDownloadedImage(
                DownloadedImageRecord(entryId, fileUri, version = record?.version.orEmpty(), installed = true)
            )
            _download.value = ContainerImageDownloadUiState.Idle
        }
    }

    /** 删除已下载的镜像：清记录 + 删下载文件（仅限 rootfs_images 目录，防误删）；若正在下载该条目先取消。 */
    fun deleteDownloaded(entryId: String) {
        val record = _downloaded.value[entryId] ?: return
        if ((_download.value as? ContainerImageDownloadUiState.Downloading)?.entryId == entryId) {
            cancelDownload()
        }
        scope.launch {
            runCatching {
                val file = File(Uri.parse(record.fileUri).path ?: return@runCatching)
                if (file.parentFile?.name == "rootfs_images") file.delete()
            }
            containerSettings.removeDownloadedImage(entryId)
        }
    }

    /**
     * 批量删除已下载的镜像：逐个删磁盘文件（同上仅限 rootfs_images 目录），
     * 记录只写回一次——逐条删除会让 DataStore 每条发射一次，列表跟着刷新 N 遍。
     * 正在下载的目标条目先取消。
     */
    fun deleteDownloaded(entryIds: Collection<String>) {
        val targets = entryIds.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> _downloaded.value[id]?.let { id to it } }
        if (targets.isEmpty()) return
        val downloading = (_download.value as? ContainerImageDownloadUiState.Downloading)?.entryId
        if (downloading != null && targets.any { it.first == downloading }) cancelDownload()
        scope.launch {
            targets.forEach { (_, record) ->
                runCatching {
                    val file = File(Uri.parse(record.fileUri).path ?: return@runCatching)
                    if (file.parentFile?.name == "rootfs_images") file.delete()
                }
            }
            containerSettings.removeDownloadedImages(targets.map { it.first })
        }
    }

    private companion object {
        /** 目录清单里带哈希的源：只有这两个源会强制校验下载文件。 */
        val CHECKSUM_SOURCES = setOf("official", "aharou")
    }
}
