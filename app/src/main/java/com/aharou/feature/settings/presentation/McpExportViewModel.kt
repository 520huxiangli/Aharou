package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP 配置导出的状态：把当前生效的 server 列表序列化为标准 `mcpServers` JSON。
 *
 * 独立于 [SettingsViewModel]（后者已顶在架构门禁行数上限）；导出是纯读操作，
 * 复用 [McpConfigRepository.serialize]，与导入端同一份格式契约。
 */
@HiltViewModel
class McpExportViewModel @Inject constructor(
    private val repository: McpConfigRepository
) : ViewModel() {

    private val _json = MutableStateFlow("")
    val json: StateFlow<String> = _json.asStateFlow()

    fun load() {
        viewModelScope.launch {
            // 与「实际会连哪些 server」同源：项目级同名覆盖全局，导出与运行一致。
            val servers = withContext(Dispatchers.IO) { repository.getEffectiveServers() }
            _json.value = withContext(Dispatchers.IO) { repository.serialize(servers) }
        }
    }
}
