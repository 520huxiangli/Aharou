package com.aharou.feature.workspace.presentation.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.workspace.domain.model.RemoteConnection
import com.aharou.feature.workspace.domain.model.RemoteMount
import com.aharou.feature.workspace.domain.model.RemoteProtocol
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.SshLoginKey
import com.aharou.feature.agent.domain.container.SshLoginKeyStore
import com.aharou.feature.agent.domain.container.SshPrivateKeyStore
import com.aharou.feature.workspace.domain.repository.RemoteRepository
import com.aharou.feature.workspace.domain.repository.HostKeyConfirmationRequiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aharou.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.settings.data.repository.SyncSettingsRepository
import com.aharou.feature.workspace.domain.remote.ftp.FtpServerManager

@HiltViewModel
class RemoteServerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: RemoteRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val loginKeyStore: SshLoginKeyStore,
    private val privateKeyStore: SshPrivateKeyStore,
    val ftpServerManager: FtpServerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RemoteServerUiState(
            hostKeys = remoteSshConnection.savedHostKeys(),
            loginKeys = loginKeyStore.entries()
        )
    )
    val uiState: StateFlow<RemoteServerUiState> = _uiState.asStateFlow()

    val syncUseGitIgnore = syncSettingsRepository.useGitIgnore
    val maxSyncBatchSize = syncSettingsRepository.maxSyncBatchSize

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            launch {
                repository.getConnections()
                    .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                    .collect { connections ->
                        _uiState.value = _uiState.value.copy(connections = connections)
                    }
            }
            launch {
                repository.getMounts()
                    .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                    .collect { mounts ->
                        _uiState.value = _uiState.value.copy(mounts = mounts)
                    }
            }
            launch {
                workspaceRepository.workspaces.collect { workspaces ->
                    _uiState.value = _uiState.value.copy(workspaces = workspaces)
                }
            }
        }
    }

    fun connectMount(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.connectMount(id)
            val updatedFailed = if (result.isFailure) _uiState.value.failedMountIds + id else _uiState.value.failedMountIds - id
            _uiState.value = _uiState.value.copy(isLoading = false, failedMountIds = updatedFailed)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(error = result.exceptionOrNull()?.message)
            }
        }
    }

    fun disconnectMount(id: String) {
        viewModelScope.launch {
            repository.disconnectMount(id)
            _uiState.value = _uiState.value.copy(failedMountIds = _uiState.value.failedMountIds - id)
        }
    }

    fun forceUploadMount(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.forceUploadMount(id)
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.remote_upload_all_failed, result.exceptionOrNull()?.message))
            } else {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.remote_upload_all_success))
            }
        }
    }

    fun forceDownloadMount(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.forceDownloadMount(id)
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.remote_download_all_failed, result.exceptionOrNull()?.message))
            } else {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.remote_download_all_success))
            }
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch {
            repository.deleteConnection(id)
        }
    }
    
    fun deleteMount(id: String) {
        viewModelScope.launch {
            repository.deleteMount(id)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun removeHostKey(host: String, port: Int) {
        remoteSshConnection.removeHostKey(host, port)
        _uiState.value = _uiState.value.copy(hostKeys = remoteSshConnection.savedHostKeys())
    }

    /** 添加登录密钥（文件导入）：读取所选私钥文件复制到应用私有目录，解析公钥指纹后入库。 */
    fun addLoginKey(uri: Uri) {
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                if (bytes.isEmpty()) return@withContext null
                val displayName = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx) else null
                        } else null
                    }
                }.getOrNull() ?: "ssh_key"
                stageLoginKey(displayName, bytes)
            } ?: return@launch
            registerLoginKey(staged)
        }
    }

    /** 添加登录密钥（粘贴内容）：将私钥文本加密写入应用私有目录，解析公钥指纹后入库。 */
    fun addLoginKeyFromContent(name: String, content: String, passphrase: String) {
        val bytes = content.trim().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                stageLoginKey(name, bytes)
            } ?: return@launch
            registerLoginKey(staged, passphrase.ifBlank { null })
        }
    }

    /** 将私钥字节加密写入应用私有目录，文件名冲突时自动加序号；写入失败返回 null。 */
    private fun stageLoginKey(preferredName: String, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val dir = File(context.filesDir, "ssh_keys").apply { mkdirs() }
        val safeName = preferredName.trim().ifBlank { "ssh_key" }.replace(Regex("[/\\\\]"), "_")
        var target = File(dir, safeName)
        var n = 1
        while (target.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ""
            target = File(dir, "${base}_$n$ext")
            n++
        }
        runCatching { privateKeyStore.write(target.absolutePath, bytes) }.getOrNull() ?: return null
        return target
    }

    /** 解析私钥指纹并写入登录密钥库，随后刷新列表。 */
    private suspend fun registerLoginKey(file: File, passphrase: String? = null) {
        val fingerprint = withContext(Dispatchers.IO) {
            privateKeyStore.fingerprint(privateKeyStore.readPem(file.absolutePath))
        }
        loginKeyStore.add(
            SshLoginKey(
                id = UUID.randomUUID().toString(),
                name = file.name,
                path = file.absolutePath,
                fingerprint = fingerprint,
                passphrase = passphrase
            )
        )
        _uiState.value = _uiState.value.copy(loginKeys = loginKeyStore.entries())
    }

    /** 编辑登录密钥：更新显示名与口令（私钥内容不可改）。 */
    fun updateLoginKey(id: String, name: String, passphrase: String) {
        val existing = loginKeyStore.entries().firstOrNull { it.id == id } ?: return
        loginKeyStore.add(
            existing.copy(
                name = name.trim().ifBlank { existing.name },
                passphrase = passphrase.ifBlank { null }
            )
        )
        _uiState.value = _uiState.value.copy(loginKeys = loginKeyStore.entries())
    }

    /** 读取登录密钥的私钥明文 PEM（供编辑页只读查看）；失败返回 null。 */
    fun readLoginKeyPem(id: String, onResult: (String?) -> Unit) {
        val key = loginKeyStore.entries().firstOrNull { it.id == id } ?: return onResult(null)
        viewModelScope.launch {
            val pem = withContext(Dispatchers.IO) {
                runCatching { privateKeyStore.readPem(key.path) }.getOrNull()
            }
            onResult(pem)
        }
    }

    fun removeLoginKey(id: String) {
        loginKeyStore.remove(id)
        _uiState.value = _uiState.value.copy(loginKeys = loginKeyStore.entries())
    }

    fun addConnection(
        name: String,
        host: String,
        port: String,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol
    ) {
        viewModelScope.launch {
            val p = port.toIntOrNull() ?: defaultPort(protocol)
            val conn = RemoteConnection(
                id = UUID.randomUUID().toString(),
                name = name,
                protocol = protocol,
                host = host,
                port = p,
                username = username.ifBlank { "local" }
            )
            repository.addConnection(conn, auth)
        }
    }

    fun updateConnection(
        id: String,
        name: String,
        host: String,
        port: String,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol
    ) {
        viewModelScope.launch {
            val p = port.toIntOrNull() ?: defaultPort(protocol)
            val conn = RemoteConnection(
                id = id,
                name = name,
                protocol = protocol,
                host = host,
                port = p,
                username = username.ifBlank { "local" }
            )
            repository.updateConnection(conn, auth)
        }
    }

    fun addMount(connectionId: String, remotePath: String, localWorkspacePath: String, autoConnect: Boolean) {
        viewModelScope.launch {
            val mount = RemoteMount(
                id = UUID.randomUUID().toString(),
                connectionId = connectionId,
                remotePath = remotePath,
                localMountPath = localWorkspacePath,
                autoConnect = autoConnect
            )
            repository.addMount(mount)
            if (autoConnect) {
                connectMount(mount.id)
            }
        }
    }

    fun updateMount(id: String, connectionId: String, remotePath: String, localWorkspacePath: String, autoConnect: Boolean) {
        viewModelScope.launch {
            val mount = RemoteMount(
                id = id,
                connectionId = connectionId,
                remotePath = remotePath,
                localMountPath = localWorkspacePath,
                autoConnect = autoConnect
            )
            repository.updateMount(mount)
            if (autoConnect) {
                connectMount(mount.id)
            } else {
                disconnectMount(mount.id)
            }
        }
    }

    fun testConnection(
        host: String,
        port: String,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val p = port.toIntOrNull() ?: defaultPort(protocol)
            try {
                val result = repository.testConnection(host, p, username, auth, protocol)
                if (result.isSuccess) {
                    onResult(true, context.getString(R.string.remote_connect_success))
                } else {
                    onResult(false, context.getString(R.string.remote_connect_failed, result.exceptionOrNull()?.message))
                }
            } catch (e: HostKeyConfirmationRequiredException) {
                // 首次连接/指纹变化：进入确认状态，由连接配置弹窗展示指纹确认区
                _uiState.value = _uiState.value.copy(
                    pendingHostKey = PendingHostKeyConfirmation(
                        host = e.host, port = e.port, keyType = e.keyType,
                        fingerprint = e.fingerprint, changed = e.changed
                    )
                )
            }
        }
    }

    /** 确认主机密钥：保存指纹并清除确认状态；调用方随后重测即可直接连通。 */
    fun confirmHostKey() {
        val pending = _uiState.value.pendingHostKey ?: return
        repository.confirmHostKey(pending.host, pending.port, pending.fingerprint)
        _uiState.value = _uiState.value.copy(
            pendingHostKey = null,
            hostKeys = remoteSshConnection.savedHostKeys()
        )
    }

    /** 拒绝主机密钥：清除确认状态，不保存指纹。 */
    fun rejectHostKey() {
        _uiState.value = _uiState.value.copy(pendingHostKey = null)
    }

    fun listRemoteDirectories(
        connectionId: String,
        path: String,
        onResult: (Boolean, List<String>, String) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.listRemoteDirectories(connectionId, path)
            if (result.isSuccess) {
                onResult(true, result.getOrNull() ?: emptyList(), "")
            } else {
                onResult(false, emptyList(), result.exceptionOrNull()?.message ?: context.getString(R.string.remote_unknown_error))
            }
        }
    }

    fun setSyncUseGitIgnore(use: Boolean) {
        syncSettingsRepository.setUseGitIgnore(use)
    }

    fun setMaxSyncBatchSize(size: Int) {
        syncSettingsRepository.setMaxSyncBatchSize(size)
    }

    fun toggleFtpServer() {
        viewModelScope.launch {
            ftpServerManager.toggleServer()
        }
    }

    fun saveFtpServerConfig(port: Int, username: String, password: String, isAnonymous: Boolean, autoStart: Boolean) {
        viewModelScope.launch {
            ftpServerManager.saveConfig(port, username, password, isAnonymous, autoStart)
        }
    }

    private fun defaultPort(protocol: RemoteProtocol): Int = when (protocol) {
        RemoteProtocol.SFTP -> 22
        RemoteProtocol.FTP -> 21
    }
}

data class RemoteServerUiState(
    val connections: List<RemoteConnection> = emptyList(),
    val mounts: List<RemoteMount> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val failedMountIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hostKeys: Map<String, String> = emptyMap(),
    val loginKeys: List<SshLoginKey> = emptyList(),
    val pendingHostKey: PendingHostKeyConfirmation? = null
)

/** 待确认的主机密钥（首次连接或指纹变化），由连接配置弹窗展示。 */
data class PendingHostKeyConfirmation(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
)
