package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.mcp.McpImportOutcome
import com.aharou.feature.agent.domain.mcp.McpImportPreview
import com.aharou.feature.agent.domain.mcp.McpJsonImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MCP JSON 导入弹窗的状态与动作。独立于 [SettingsViewModel]（后者体量已到架构门禁上限），
 * 导入完成后由宿主回调刷新列表。
 */
@HiltViewModel
class McpImportViewModel @Inject constructor(
    private val importer: McpJsonImporter
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _outcome = MutableStateFlow<McpImportOutcome?>(null)
    val outcome: StateFlow<McpImportOutcome?> = _outcome.asStateFlow()

    fun onTextChange(value: String) {
        _text.value = value
        _outcome.value = null
    }

    /** 解析预览（纯解析，不写盘）；输入为空时返回 null。 */
    fun preview(): McpImportPreview? = _text.value.takeIf { it.isNotBlank() }?.let { importer.preview(it) }

    fun import() {
        val raw = _text.value
        if (raw.isBlank() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            _outcome.value = importer.import(raw)
            _importing.value = false
        }
    }
}
