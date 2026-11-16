package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.extension.domain.InstalledExtensionAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 「扩展」统一入口页的状态。
 *
 * 只做发现与展示：技能 / 知识库 / 连接器各自的安装机制差别太大（见
 * [InstalledExtensionAggregator] 的说明），本页不碰安装，只让 UI 跳回它们各自的原页。
 * 插件自有一套完整面板（列表 / 商店 / 开发者侧载），不再在这里重复一份。
 */
@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val aggregator: InstalledExtensionAggregator,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<ExtensionEntry>>(emptyList())
    val entries: StateFlow<List<ExtensionEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { withContext(Dispatchers.IO) { aggregator.installed() } }
                .onSuccess { _entries.value = it }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
            _loading.value = false
        }
    }

    fun search(query: String) {
        _query.value = query
    }

    fun setKindFilter(kind: ExtensionKind?) {
        _kindFilter.value = kind
    }
}
