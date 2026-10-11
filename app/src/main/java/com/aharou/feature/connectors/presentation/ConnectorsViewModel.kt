package com.aharou.feature.connectors.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.connectors.data.ConnectorInstaller
import com.aharou.feature.connectors.domain.Connector
import com.aharou.feature.connectors.domain.ConnectorLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 连接器目录页的状态：清单（本地 assets）+ 已生效的 MCP 条目（判定「已接入」）。 */
@HiltViewModel
class ConnectorsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val installer: ConnectorInstaller,
    configRepository: McpConfigRepository
) : ViewModel() {

    val catalog: List<Connector> = ConnectorLibrary.load(context).connectors

    val installed: StateFlow<List<McpServerEntry>> = configRepository.effectiveEntriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun installOpen(connector: Connector) =
        viewModelScope.launch(Dispatchers.IO) { installer.installOpen(connector) }

    fun installWithKey(connector: Connector, key: String) =
        viewModelScope.launch(Dispatchers.IO) { installer.installWithKey(connector, key) }

    /** OAuth 首步：先落一条配置（授权流程按 server 名更新已有 oauth 块），再把条目交给授权面板。 */
    suspend fun prepareOAuth(connector: Connector): McpServerEntry =
        withContext(Dispatchers.IO) { installer.installOAuth(connector) }

    fun setEnabled(name: String, enabled: Boolean, scope: McpScope) =
        viewModelScope.launch(Dispatchers.IO) { installer.setEnabled(name, enabled, scope) }

    fun remove(name: String) = viewModelScope.launch(Dispatchers.IO) { installer.remove(name) }
}
