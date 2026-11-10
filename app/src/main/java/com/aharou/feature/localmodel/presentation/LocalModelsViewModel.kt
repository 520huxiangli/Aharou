package com.aharou.feature.localmodel.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.localmodel.LocalModelEngine
import com.aharou.feature.localmodel.data.LocalModelCatalog
import com.aharou.feature.localmodel.data.LocalModelEntry
import com.aharou.feature.localmodel.data.LocalModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
internal class LocalModelsViewModel @Inject constructor(
    private val catalog: LocalModelCatalog,
    private val store: LocalModelStore,
    private val engine: LocalModelEngine,
) : ViewModel() {

    data class UiState(
        val models: List<LocalModelEntry> = emptyList(),
        val installed: Map<String, Long> = emptyMap(),
        val usedBytes: Long = 0L,
        val progress: Map<String, LocalModelStore.Progress> = emptyMap(),
        /** 原生库在不在。缺库时整页只显示一句说明，不列出可安装项。 */
        val available: Boolean = false,
        val error: String? = null,
        val testing: String? = null,
        val testOutput: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                catalog.entries() to store.installed()
            }
            _state.value = _state.value.copy(
                models = snapshot.first,
                installed = snapshot.second,
                usedBytes = withContext(Dispatchers.IO) { store.usedBytes() },
                available = engine.available,
            )
        }
    }

    fun install(id: String) {
        if (_state.value.progress.containsKey(id)) return
        viewModelScope.launch {
            val result = store.install(id) { progress ->
                _state.value = _state.value.copy(progress = _state.value.progress + (id to progress))
            }
            _state.value = _state.value.copy(
                progress = _state.value.progress - id,
                error = result.exceptionOrNull()?.message,
            )
            refresh()
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
            _state.value = _state.value.copy(testOutput = null)
            refresh()
        }
    }

    /** 试跑一句：加载模型并让它回一句话，验证速度与内存。 */
    fun test(id: String) {
        if (_state.value.testing != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = id, testOutput = null, error = null)
            engine.load(id)
                .onFailure { failure ->
                    _state.value = _state.value.copy(testing = null, error = failure.message)
                }
                .onSuccess {
                    val started = System.currentTimeMillis()
                    val reply = engine.chat(
                        system = "你是一个简洁的中文助手，回答控制在两句话以内。",
                        user = "用一句话介绍一下你自己。",
                        maxTokens = 96,
                    )
                    val elapsed = System.currentTimeMillis() - started
                    _state.value = _state.value.copy(
                        testing = null,
                        testOutput = reply?.takeIf { it.isNotBlank() }
                            ?.let { "$it\n\n（${elapsed / 1000.0} 秒）" }
                            ?: "模型没有返回内容",
                    )
                }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearOutput() {
        _state.value = _state.value.copy(testOutput = null)
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
