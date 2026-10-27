package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.remote.ModelMetadataService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模型目录刷新的界面状态。 */
sealed interface CatalogRefreshState {
    data object Idle : CatalogRefreshState
    data object Refreshing : CatalogRefreshState
    data object Done : CatalogRefreshState
    data object Failed : CatalogRefreshState
}

/**
 * 模型目录手动刷新。
 *
 * 单独成 ViewModel 而不是并进 [SettingsViewModel]：后者已是超大文件（行数棘轮在盯），
 * 这里只有「刷新一次、回报结果」这一件事。
 */
@HiltViewModel
class ModelCatalogViewModel @Inject constructor(
    private val modelMetadataService: ModelMetadataService
) : ViewModel() {

    private val _state = MutableStateFlow<CatalogRefreshState>(CatalogRefreshState.Idle)
    val state: StateFlow<CatalogRefreshState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value is CatalogRefreshState.Refreshing) return
        _state.value = CatalogRefreshState.Refreshing
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { modelMetadataService.refreshCatalog() }
            }.getOrElse { e ->
                FileLogger.w(TAG, "刷新模型目录失败: ${e.message}", e)
                false
            }
            _state.value = if (ok) CatalogRefreshState.Done else CatalogRefreshState.Failed
        }
    }

    private companion object {
        const val TAG = "ModelCatalogViewModel"
    }
}
