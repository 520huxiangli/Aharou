package com.aharou.feature.plugin.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.data.import.OperitImportOutcome
import com.aharou.feature.plugin.data.import.OperitPluginImporter
import com.aharou.feature.plugin.data.market.OperitMarketCatalog
import com.aharou.feature.plugin.data.market.OperitMarketCategoryOption
import com.aharou.feature.plugin.data.market.OperitMarketChecksumException
import com.aharou.feature.plugin.data.market.OperitMarketClient
import com.aharou.feature.plugin.data.market.OperitMarketException
import com.aharou.feature.plugin.data.market.OperitMarketListScope
import com.aharou.feature.plugin.data.market.OperitMarketListing
import com.aharou.feature.plugin.data.market.OperitMarketNoAssetException
import com.aharou.feature.plugin.data.market.OperitMarketPageResult
import com.aharou.feature.plugin.data.market.OperitMarketTooLargeException
import com.aharou.feature.plugin.domain.PluginError
import com.aharou.feature.plugin.domain.PluginInstallOrigin
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.PluginPreviewResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OperitMarketErrorCode {
    NETWORK,
    INVALID_DATA,
    NO_ASSET,
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    PACKAGE_TOO_LARGE,
    CONVERT_FAILED,
    PREVIEW_FAILED
}

data class OperitMarketError(
    val code: OperitMarketErrorCode,
    /** 语言无关的定位信息（HTTP 状态、哈希值等），原样展示。 */
    val detail: String? = null,
    val pluginErrors: List<PluginError> = emptyList(),
    /** 下载/转换错误所属的条目；整页加载错误为 null。 */
    val itemId: String? = null
)

data class OperitMarketUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<OperitMarketListing> = emptyList(),
    val categories: List<OperitMarketCategoryOption> = emptyList(),
    val filtered: List<OperitMarketListing> = emptyList(),
    val scope: OperitMarketListScope = OperitMarketListScope(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val hasMore: Boolean = false,
    /** 当前列表来自本地缓存（网络不可用或未过期）。 */
    val stale: Boolean = false,
    val generatedAt: String? = null,
    val error: OperitMarketError? = null,
    val query: String = "",
    val installingId: String? = null,
    val installError: OperitMarketError? = null,
    val preview: PluginStorePreview? = null,
    /** 当前浏览的是哪个来源；切换会清空列表重新加载。 */
    val source: OperitMarketSource = OperitMarketSource.OPERIT
)

/**
 * 插件市场来源。
 *
 * [OPERIT] 是社区市场（上游 1283 条，服务端分页）；[OPERIT2] 是 Operit2 官方与第三方收录包
 * （我们自己的 CNB 镜像，一次取全量）。两者条目结构不同，所以分开取而不是混在一个列表里。
 */
enum class OperitMarketSource { OPERIT, OPERIT2 }

/**
 * Operit 市场：分页拉取 + 本地过滤 + 下载校验后交给同一个导入器转换。
 * 市场没有搜索接口，所以搜索框只过滤已加载的条目，且搜索期间不再继续拉页
 * （与 Operit 的浏览逻辑一致），避免为一次本地筛选多下载几百条。
 */
@HiltViewModel
class OperitMarketViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OperitMarketClient,
    private val manager: PluginManager
) : ViewModel() {

    private val _state = MutableStateFlow(OperitMarketUiState())
    val state: StateFlow<OperitMarketUiState> = _state.asStateFlow()

    private var loadToken = 0
    private var started = false

    /** 首次切到 Operit 来源时才拉数据，避免用户从不打开这个标签也白下载一页。 */
    fun ensureLoaded() {
        if (started) return
        started = true
        loadCategories(force = false)
        loadFirstPage(force = false)
    }

    /** 切换来源：清空当前列表，按新来源重新取一次首屏。 */
    fun setSource(source: OperitMarketSource) {
        if (_state.value.source == source) return
        loadToken++
        _state.update {
            it.copy(
                source = source, items = emptyList(), filtered = emptyList(),
                // 分类是社区市场才有的，切到 Operit2 时留着会让用户点到空列表
                categories = if (source == OperitMarketSource.OPERIT2) emptyList() else it.categories,
                scope = OperitMarketListScope(),
                page = 0, hasMore = false, error = null, query = ""
            )
        }
        loadFirstPage(force = false)
    }

    fun refresh() {
        started = true
        loadCategories(force = true)
        loadFirstPage(force = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.query.isNotBlank()) return
        if (current.source == OperitMarketSource.OPERIT2) return
        if (current.loading || current.refreshing || current.loadingMore || !current.hasMore) return
        val page = current.page + 1
        val scope = current.scope
        val token = loadToken
        val expected = current.generatedAt
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                client.loadPage(scope, page, expectedGeneratedAt = expected)
            }
            if (token != loadToken) return@launch
            result.onSuccess { outcome ->
                when (outcome) {
                    is OperitMarketPageResult.End -> _state.update {
                        it.copy(loadingMore = false, hasMore = false).derived()
                    }
                    is OperitMarketPageResult.Loaded -> {
                        val batch = outcome.batch
                        _state.update { current ->
                            val seen = current.items.mapTo(HashSet()) { item -> item.id }
                            val appended = batch.listings.filter { seen.add(it.id) }
                            current.copy(
                                loadingMore = false,
                                items = current.items + appended,
                                page = batch.page,
                                totalPages = batch.totalPages,
                                hasMore = batch.page < batch.totalPages,
                                stale = current.stale || batch.stale
                            ).derived()
                        }
                    }
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "加载 Operit 市场第 $page 页失败", e)
                _state.update {
                    it.copy(loadingMore = false, error = mapError(e)).derived()
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query).derived() }
    }

    fun setCategory(id: String?) {
        if (_state.value.scope.categoryId == id) return
        _state.update {
            it.copy(
                scope = OperitMarketListScope(id),
                items = emptyList(),
                filtered = emptyList(),
                page = 0,
                totalPages = 1,
                hasMore = false,
                stale = false,
                generatedAt = null,
                error = null,
                installError = null
            ).derived()
        }
        loadFirstPage(force = false)
    }

    fun consumePreview() {
        _state.update { it.copy(preview = null) }
    }

    /**
     * 下载 → 校验 → 转换 → 预览。任何一步失败都只作废这一次下载，
     * 不改变市场条目的可用状态（下次仍可重试）。
     */
    fun install(item: OperitMarketListing) {
        if (_state.value.installingId != null) return
        val asset = item.asset
        if (asset == null) {
            _state.update {
                it.copy(
                    installError = OperitMarketError(
                        code = OperitMarketErrorCode.NO_ASSET,
                        itemId = item.id
                    )
                )
            }
            return
        }
        _state.update { it.copy(installingId = item.id, installError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val download = client.download(asset)
                when (val outcome = OperitPluginImporter.prepare(download.assetName, download.bytes)) {
                    is OperitImportOutcome.NotOperit -> fail(
                        item.id,
                        OperitMarketError(
                            code = OperitMarketErrorCode.CONVERT_FAILED,
                            detail = download.assetName
                        )
                    )
                    is OperitImportOutcome.Rejected -> fail(
                        item.id,
                        OperitMarketError(
                            code = OperitMarketErrorCode.CONVERT_FAILED,
                            pluginErrors = outcome.errors
                        )
                    )
                    is OperitImportOutcome.Importable -> {
                        val result = manager.preview(ByteArrayInputStream(outcome.zip))
                        when (result) {
                            is PluginPreviewResult.Ready -> {
                                val label = context.getString(R.string.operit_market_source_operit)
                                _state.update {
                                    it.copy(
                                        installingId = null,
                                        preview = PluginStorePreview(
                                            preview = result.preview,
                                            origin = PluginInstallOrigin(
                                                kind = PluginInstallOrigin.KIND_INDEX,
                                                sourceId = ORIGIN_SOURCE_ID,
                                                sourceName = label,
                                                indexUrl = OperitMarketClient.INDEX_URL,
                                                repository = null,
                                                official = false
                                            ),
                                            expectedRepository = null,
                                            sourceLabel = label,
                                            officialSource = false,
                                            operitReport = outcome.report,
                                            operitItemId = item.id
                                        )
                                    )
                                }
                            }
                            is PluginPreviewResult.Failed -> fail(
                                item.id,
                                OperitMarketError(
                                    code = OperitMarketErrorCode.PREVIEW_FAILED,
                                    pluginErrors = result.errors
                                )
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "安装 Operit 市场条目失败: ${item.id}", e)
                fail(item.id, mapError(e))
            }
        }
    }

    private fun loadCategories(force: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val categories = runCatching { client.loadManifest(forceRefresh = force) }
                .onFailure { FileLogger.w(TAG, "读取 Operit 市场分类失败", it) }
                .getOrNull()
            if (categories == null) return@launch
            _state.update { current ->
                // 分类清单只在用户还没翻到别的分类时生效，避免把当前选择冲掉
                val merged = if (categories.isEmpty()) OperitMarketCatalog.categories(current.items) else categories
                current.copy(categories = merged).derived()
            }
        }
    }

    private fun loadFirstPage(force: Boolean) {
        val token = ++loadToken
        val scope = _state.value.scope
        _state.update {
            it.copy(
                loading = it.items.isEmpty(),
                refreshing = it.items.isNotEmpty(),
                loadingMore = false,
                error = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val source = _state.value.source
            val result = runCatching {
                if (source == OperitMarketSource.OPERIT2) client.loadOperit2(forceRefresh = force)
                else client.loadPage(scope, 1, forceRefresh = force)
            }
            if (token != loadToken) return@launch
            result.onSuccess { outcome ->
                when (outcome) {
                    is OperitMarketPageResult.End -> _state.update {
                        it.copy(loading = false, refreshing = false, hasMore = false, page = 1)
                            .derived()
                    }
                    is OperitMarketPageResult.Loaded -> {
                        val batch = outcome.batch
                        _state.update {
                            it.copy(
                                loading = false,
                                refreshing = false,
                                items = batch.listings,
                                page = batch.page,
                                totalPages = batch.totalPages,
                                hasMore = batch.page < batch.totalPages,
                                stale = batch.stale,
                                generatedAt = batch.generatedAt,
                                error = null,
                                // manifest 失败时至少让已加载条目里的分类可选
                                categories = it.categories.ifEmpty {
                                    OperitMarketCatalog.categories(batch.listings)
                                }
                            ).derived()
                        }
                    }
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "加载 Operit 市场首页失败: ${scope.cacheKey}", e)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = mapError(e)
                    ).derived()
                }
            }
        }
    }

    private fun fail(itemId: String, error: OperitMarketError) {
        _state.update { it.copy(installingId = null, installError = error.copy(itemId = itemId)) }
    }

    private fun mapError(e: Throwable): OperitMarketError = when (e) {
        is OperitMarketChecksumException -> OperitMarketError(
            code = OperitMarketErrorCode.CHECKSUM_MISMATCH,
            detail = "SHA-256 ${e.actual.take(12)}… ≠ ${e.expected.take(12)}…"
        )
        is OperitMarketNoAssetException -> OperitMarketError(code = OperitMarketErrorCode.NO_ASSET)
        is OperitMarketTooLargeException -> OperitMarketError(code = OperitMarketErrorCode.PACKAGE_TOO_LARGE)
        is OperitMarketException -> OperitMarketError(
            code = if (e.cause is java.io.IOException) OperitMarketErrorCode.NETWORK
            else OperitMarketErrorCode.INVALID_DATA,
            detail = e.message
        )
        else -> OperitMarketError(
            code = if (e is java.io.IOException) OperitMarketErrorCode.DOWNLOAD_FAILED
            else OperitMarketErrorCode.INVALID_DATA,
            detail = e.message
        )
    }

    /** 过滤只依赖已有条目，状态每次变更后统一重算，避免组合期反复分组。 */
    private fun OperitMarketUiState.derived(): OperitMarketUiState = copy(
        filtered = OperitMarketCatalog.filter(items, scope.categoryId, query)
    )

    private companion object {
        const val TAG = "OperitMarketViewModel"
        const val ORIGIN_SOURCE_ID = "operit.market"
    }
}
