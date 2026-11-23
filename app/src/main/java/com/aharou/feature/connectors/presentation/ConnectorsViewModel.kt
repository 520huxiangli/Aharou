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
import com.aharou.feature.credentials.data.remote.GitHubDeviceFlowClient
import com.aharou.feature.credentials.data.remote.GitHubTokenPollResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 连接器目录页的状态：清单（本地 assets）+ 已生效的 MCP 条目（判定「已接入」）。 */
@HiltViewModel
class ConnectorsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val installer: ConnectorInstaller,
    private val deviceFlowClient: GitHubDeviceFlowClient,
    configRepository: McpConfigRepository
) : ViewModel() {

    val catalog: List<Connector> = ConnectorLibrary.load(context).connectors

    val installed: StateFlow<List<McpServerEntry>> = configRepository.effectiveEntriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 上一次接入失败的说明；接入走的是凭据库与配置文件，两者都可能抛（如 VaultError），不能不吭声。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun consumeError() {
        _error.value = null
    }

    fun installOpen(connector: Connector) = connect {
        installer.installOpen(connector)
    }

    fun installWithKey(connector: Connector, key: String) = connect {
        installer.installWithKey(connector, key)
    }

    /** OAuth 首步：先落一条配置（授权流程按 server 名更新已有 oauth 块），再把条目交给授权面板。 */
    suspend fun prepareOAuth(connector: Connector): McpServerEntry =
        withContext(Dispatchers.IO) { installer.installOAuth(connector) }

    /**
     * 设备码登录的界面状态。
     *
     * 用 sealed 而不是几个布尔：轮询只允许停在其中一个阶段，多个布尔能拼出「既在等又已失败」这种没法渲染的组合。
     */
    sealed interface DeviceAuthUi {
        data object Idle : DeviceAuthUi
        /** [userCode] 为空表示还在向服务端申请码。 */
        data class Waiting(val userCode: String, val verificationUri: String) : DeviceAuthUi
        data object Success : DeviceAuthUi
        data class Failed(val message: String) : DeviceAuthUi
    }

    private val _deviceAuth = MutableStateFlow<DeviceAuthUi>(DeviceAuthUi.Idle)
    val deviceAuth: StateFlow<DeviceAuthUi> = _deviceAuth.asStateFlow()
    private var deviceJob: Job? = null
    private var deviceConnector: Connector? = null

    /** 开始设备码登录：申请码 → 轮询 → 成功后写配置并重连。 */
    fun beginDeviceAuth(connector: Connector) {
        deviceJob?.cancel()
        deviceConnector = connector
        _deviceAuth.value = DeviceAuthUi.Waiting("", "")
        deviceJob = viewModelScope.launch {
            val clientId = connector.auth.clientId.orEmpty()
            val code = runCatching { deviceFlowClient.requestDeviceCode(clientId) }.getOrElse { error ->
                _deviceAuth.value = DeviceAuthUi.Failed(error.message ?: "")
                return@launch
            }
            _deviceAuth.value = DeviceAuthUi.Waiting(code.userCode, code.verificationUri)
            var intervalMs = code.intervalSeconds.coerceAtLeast(1) * 1000L
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                val polled = runCatching { deviceFlowClient.pollAccessToken(code.deviceCode, clientId) }
                    .getOrNull() ?: continue
                when (polled) {
                    is GitHubTokenPollResult.Success -> {
                        val saved = runCatching { installer.installDevice(connector, polled.accessToken) }
                        _deviceAuth.value = saved.fold(
                            onSuccess = { DeviceAuthUi.Success },
                            onFailure = { DeviceAuthUi.Failed(it.message ?: "") }
                        )
                        return@launch
                    }
                    GitHubTokenPollResult.SlowDown -> intervalMs += 5_000L
                    GitHubTokenPollResult.Expired -> {
                        _deviceAuth.value = DeviceAuthUi.Failed("设备码已过期")
                        return@launch
                    }
                    GitHubTokenPollResult.Denied -> {
                        _deviceAuth.value = DeviceAuthUi.Failed("授权被拒绝")
                        return@launch
                    }
                    is GitHubTokenPollResult.Error -> {
                        _deviceAuth.value = DeviceAuthUi.Failed(polled.code)
                        return@launch
                    }
                    GitHubTokenPollResult.Pending -> Unit
                }
            }
            _deviceAuth.value = DeviceAuthUi.Failed("设备码已过期")
        }
    }

    fun cancelDeviceAuth() {
        deviceJob?.cancel()
        deviceJob = null
        deviceConnector = null
        _deviceAuth.value = DeviceAuthUi.Idle
    }

    fun setEnabled(name: String, enabled: Boolean, scope: McpScope) =
        viewModelScope.launch(Dispatchers.IO) { installer.setEnabled(name, enabled, scope) }

    fun remove(name: String) = viewModelScope.launch(Dispatchers.IO) { installer.remove(name) }

    /** 接入类动作的统一收口：失败把原因留在 [error] 上，由界面提示，不再静默吞掉异常。 */
    private fun connect(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onSuccess { _error.value = null }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
        }
    }
}
