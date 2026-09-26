package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.domain.model.CleanupKind
import com.aharou.feature.settings.domain.model.DeviceSpace
import com.aharou.feature.settings.domain.model.StorageCategory
import com.aharou.feature.settings.domain.model.StorageDetailKey
import com.aharou.feature.settings.domain.model.StorageEntry
import com.aharou.feature.settings.domain.service.StorageUsageScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    /** 已算出的分类；扫描中时逐项填充，缺项界面显示「统计中」。 */
    val entries: Map<StorageCategory, StorageEntry> = emptyMap(),
    val scanning: Boolean = false,
    val deviceSpace: DeviceSpace = DeviceSpace(0, 0),
    val expanded: Set<StorageCategory> = emptySet(),
    val cleaning: Boolean = false,
    /** 上一次清理释放的字节数，非空时界面给出提示。 */
    val freedBytes: Long? = null
) {
    val totalBytes: Long get() = entries.values.sumOf { it.bytes }

    /** 各可清理项当前大小；未统计完的项按 0 计，界面据此禁用按钮。 */
    val cleanableSizes: Map<CleanupKind, Long>
        get() = mapOf(
            CleanupKind.Caches to (entries[StorageCategory.Caches]?.bytes ?: 0L),
            CleanupKind.Logs to (entries[StorageCategory.Logs]?.bytes ?: 0L),
            CleanupKind.ToolOutput to aiConfigDetail(StorageDetailKey.TOOL_OUTPUT),
            CleanupKind.VisionSessions to aiConfigDetail(StorageDetailKey.VISION_SESSIONS)
        )

    val reclaimableBytes: Long get() = cleanableSizes.values.sum()

    private fun aiConfigDetail(key: String): Long =
        entries[StorageCategory.AiConfig]?.details?.firstOrNull { it.key == key }?.bytes ?: 0L
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val scanner: StorageUsageScanner
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    /** 单独持有扫描协程：清理完成后触发的重扫不能把清理自身也取消掉。 */
    private var scanJob: Job? = null

    init {
        refresh()
    }

    fun refresh(clearMessage: Boolean = true) {
        scanJob?.cancel()
        _state.update {
            it.copy(
                entries = emptyMap(),
                scanning = true,
                deviceSpace = scanner.deviceSpace(),
                freedBytes = if (clearMessage) null else it.freedBytes
            )
        }
        scanJob = viewModelScope.launch {
            scanner.scan().collect { entry ->
                _state.update { s -> s.copy(entries = s.entries + (entry.category to entry)) }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun toggleExpanded(category: StorageCategory) {
        _state.update {
            val expanded = if (category in it.expanded) it.expanded - category else it.expanded + category
            it.copy(expanded = expanded)
        }
    }

    /** 执行清理（[kinds] 为空表示全部可清理项），完成后自动重新统计。 */
    fun clean(vararg kinds: CleanupKind) {
        if (_state.value.cleaning) return
        val targets = if (kinds.isEmpty()) CleanupKind.entries else kinds.toList()
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            val freed = targets.sumOf { scanner.clean(it) }
            _state.update { it.copy(cleaning = false, freedBytes = freed) }
            refresh(clearMessage = false)
        }
    }
}
