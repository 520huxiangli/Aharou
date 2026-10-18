package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.extension.domain.ExtensionState
import com.aharou.feature.extension.domain.InstalledExtensionAggregator
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.market.PluginMarketEntry
import com.aharou.feature.plugin.domain.market.PluginMarketRepository
import com.aharou.feature.plugin.presentation.PluginEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「扩展」统一入口页的状态与动作。
 *
 * 这里只做「发现 + 转发」：插件的启停与卸载转发给 [PluginManager]，市场安装转发给
 * [PluginMarketRepository]。技能 / 知识库 / 连接器各自的安装机制差别太大（见
 * [InstalledExtensionAggregator] 的说明），本页不碰，只让 UI 跳回它们各自的原页。
 */
@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val aggregator: InstalledExtensionAggregator,
    private val market: PluginMarketRepository,
    private val pluginManager: PluginManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<ExtensionEntry>>(emptyList())
    val entries: StateFlow<List<ExtensionEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 正在处理的条目 id（插件动作或市场安装）；null 表示空闲。 */
    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    private val _marketPlugins = MutableStateFlow<List<PluginMarketEntry>>(emptyList())
    val marketPlugins: StateFlow<List<PluginMarketEntry>> = _marketPlugins.asStateFlow()

    private val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    /** 市场索引是否已拉过一次。用于区分「源里确实没插件」与「这次没取到」。 */
    private val _marketLoaded = MutableStateFlow(false)
    val marketLoaded: StateFlow<Boolean> = _marketLoaded.asStateFlow()

    private val _marketFailed = MutableStateFlow(false)
    val marketFailed: StateFlow<Boolean> = _marketFailed.asStateFlow()

    /** 当前市场源的显示名，展示在标题旁。 */
    private val _marketSourceLabel = MutableStateFlow("")
    val marketSourceLabel: StateFlow<String> = _marketSourceLabel.asStateFlow()

    private val _installedVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val installedVersions: StateFlow<Map<String, String>> = _installedVersions.asStateFlow()

    private val _event = MutableStateFlow<PluginEvent?>(null)
    val event: StateFlow<PluginEvent?> = _event.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null 表示「全部」。 */
    private val _kindFilter = MutableStateFlow<ExtensionKind?>(null)
    val kindFilter: StateFlow<ExtensionKind?> = _kindFilter.asStateFlow()

    /** 过滤后的列表：先按类型、再按关键词（名称 + 描述，忽略大小写）。 */
    val visibleEntries: StateFlow<List<ExtensionEntry>> =
        combine(_entries, _query, _kindFilter) { entries, query, kind ->
            val keyword = query.trim()
            entries.filter { entry ->
                (kind == null || entry.kind == kind) &&
                    (
                        keyword.isEmpty() ||
                            entry.name.contains(keyword, ignoreCase = true) ||
                            entry.description.contains(keyword, ignoreCase = true)
                        )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh()
    }

    /** 重新聚合已装扩展，并刷新插件版本表（市场条目据此标「已安装 / 可更新」）。 */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { withContext(Dispatchers.IO) { aggregator.installed() } }
                .onSuccess { _entries.value = it }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
            runCatching { withContext(Dispatchers.IO) { market.installedVersions() } }
                .onSuccess { _installedVersions.value = it }
            _loading.value = false
        }
    }

    fun search(query: String) {
        _query.value = query
    }

    fun setKindFilter(kind: ExtensionKind?) {
        _kindFilter.value = kind
    }

    /** 拉插件市场索引。源取清单里的第一个（目前只有官方一个源）。 */
    fun loadMarket() {
        if (_marketLoading.value) return
        viewModelScope.launch {
            _marketLoading.value = true
            val sources = withContext(Dispatchers.IO) { market.sources() }
            val sourceId = sources.keys.firstOrNull()
            val listing = withContext(Dispatchers.IO) {
                // 源清单为空与取不到是两回事，但对用户都没得点，统一记「没取到」
                sourceId?.let { market.listPlugins(it) }
            }
            _marketSourceLabel.value = sourceId?.let { sources[it]?.displayName(language) } ?: sourceId.orEmpty()
            _marketPlugins.value = listing?.plugins.orEmpty()
            _marketFailed.value = listing == null || listing.failed
            _marketLoaded.value = true
            _marketLoading.value = false
        }
    }

    /** 安装市场里的一个插件；装完刷新已装列表与版本表。 */
    fun installMarketPlugin(entry: PluginMarketEntry) {
        if (_busyId.value != null) return
        viewModelScope.launch {
            _busyId.value = MARKET_BUSY_PREFIX + entry.id
            val result = withContext(Dispatchers.IO) { market.install(entry) }
            _busyId.value = null
            _event.value = result.fold(
                onSuccess = { PluginEvent.Installed(it.name, it.version) },
                onFailure = { PluginEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            refresh()
        }
    }

    /** 插件启停：运行中 → 停止，其余（已停止 / 启动失败）→ 启动。 */
    fun togglePlugin(entry: ExtensionEntry) {
        val id = pluginIdOf(entry) ?: return
        operate(entry.id) {
            if (entry.state == ExtensionState.RUNNING) pluginManager.stop(id) else pluginManager.launch(id)
        }
    }

    fun uninstallPlugin(entry: ExtensionEntry) {
        val id = pluginIdOf(entry) ?: return
        operate(entry.id) { pluginManager.uninstall(id) }
    }

    fun consumeEvent() {
        _event.value = null
    }

    private fun operate(entryId: String, block: suspend () -> Result<Unit>) {
        if (_busyId.value != null) return
        viewModelScope.launch {
            _busyId.value = entryId
            val result = block()
            _busyId.value = null
            _event.value = result.fold(
                onSuccess = { PluginEvent.Completed },
                onFailure = { PluginEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            refresh()
        }
    }

    /** 聚合 id 形如 `plugin:<manifest id>`，取回插件自己的 id；非插件返回 null。 */
    private fun pluginIdOf(entry: ExtensionEntry): String? =
        if (entry.kind == ExtensionKind.PLUGIN) entry.id.removePrefix(PLUGIN_ID_PREFIX) else null

    /**
     * 界面语言。不能用 `Locale.getDefault()`：per-app 语言下进程 Locale 未必跟着 App 走
     * （聚合器与技能市场踩过同一个坑）。
     */
    private val language: String
        get() = context.resources.configuration.locales[0].language

    companion object {
        /** 市场安装的占位忙 id 前缀，不与任何条目 id 冲突。 */
        const val MARKET_BUSY_PREFIX = "market:"

        /** 聚合器给插件条目加的前缀。 */
        const val PLUGIN_ID_PREFIX = "plugin:"
    }
}