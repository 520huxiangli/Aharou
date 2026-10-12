package com.aharou.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.backup.domain.BackupCryptoError
import com.aharou.feature.backup.domain.BackupCryptoException
import com.aharou.feature.backup.domain.BackupManager
import com.aharou.feature.backup.domain.BackupOptions
import com.aharou.feature.backup.domain.ProviderConflict
import com.aharou.feature.backup.domain.ProviderConflictStrategy
import com.aharou.feature.backup.domain.RestoreStats
import com.aharou.feature.backup.domain.WorkspaceBackupMeta
import com.aharou.feature.backup.data.BackupTargetRepository
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.RemoteBackup
import com.aharou.feature.backup.domain.remote.RemoteStorageErrorKind
import com.aharou.feature.backup.domain.remote.RemoteStorageException
import com.aharou.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aharou.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    /** 导入预览完成，需用户确认：勾选要恢复的工作区 + 选择供应商冲突处置方式（暂存 uri；解密产物另存于 [preparedImport]）。 */
    data class WorkspaceSelection(
        val workspaces: List<WorkspaceBackupMeta>,
        val providerConflicts: List<ProviderConflict>,
        /** 本地导入的源 uri；网络恢复无 uri（源是已暂存文件），为 null。 */
        val uri: Uri?
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

/** 网络备份（WebDAV / S3）区独立于本地导入导出的状态机。 */
sealed interface NetworkBackupState {
    data object Idle : NetworkBackupState
    data object Working : NetworkBackupState

    /** 一次性提示（成功 / 失败），由 UI 展示后调 [BackupViewModel.dismissNetworkState] 复位。 */
    data class Notice(val messageRes: Int, val isError: Boolean) : NetworkBackupState

    /** 远端备份列表，展示远端管理弹窗。 */
    data class RemoteList(
        val targetId: String,
        val targetName: String,
        val items: List<RemoteBackup>
    ) : NetworkBackupState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val targetRepository: BackupTargetRepository
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _networkState = MutableStateFlow<NetworkBackupState>(NetworkBackupState.Idle)
    val networkState: StateFlow<NetworkBackupState> = _networkState.asStateFlow()

    /** 已配置的网络备份目标列表。 */
    val targets: StateFlow<List<BackupTarget>> = targetRepository.targets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 加密备份解密后的缓存文件：预览生成，恢复复用，用后即删（见 [clearPreparedImport]）。 */
    @Volatile
    private var preparedImport: File? = null

    /** [preparedImport] 是否已解密（true=解密产物，false=明文）；供网络恢复复用同一套导入语义。 */
    @Volatile
    private var preparedIsDecrypted = false

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

    init {
        // 上次进程被杀时来不及删的解密明文暂存（cacheDir/backup*.tmp），进入备份页时兜底清理。
        viewModelScope.launch { backupManager.cleanupStaleStagingFiles() }
    }

    private val _exportOptions = MutableStateFlow(loadExportOptions())
    val exportOptions: StateFlow<BackupOptions> = _exportOptions.asStateFlow()

    /** 导出数据范围：勾选即持久化，下次进入页面沿用。 */
    fun updateExportOptions(options: BackupOptions) {
        _exportOptions.value = options
        prefs.edit()
            .putBoolean(KEY_PROVIDERS, options.providers)
            .putBoolean(KEY_REMOTE_CONNECTIONS, options.remoteConnections)
            .putBoolean(KEY_CHAT_HISTORY, options.chatHistory)
            .putBoolean(KEY_MCP_SERVERS, options.mcpServers)
            .putBoolean(KEY_PERMISSION_RULES, options.permissionRules)
            .putBoolean(KEY_APP_SETTINGS, options.appSettings)
            .putBoolean(KEY_WORKSPACE_FILES, options.workspaceFiles)
            .putBoolean(KEY_MEMORY_AND_SOUL, options.memoryAndSoul)
            .apply()
    }

    private fun loadExportOptions(): BackupOptions = BackupOptions(
        providers = prefs.getBoolean(KEY_PROVIDERS, true),
        remoteConnections = prefs.getBoolean(KEY_REMOTE_CONNECTIONS, true),
        chatHistory = prefs.getBoolean(KEY_CHAT_HISTORY, true),
        mcpServers = prefs.getBoolean(KEY_MCP_SERVERS, true),
        permissionRules = prefs.getBoolean(KEY_PERMISSION_RULES, true),
        appSettings = prefs.getBoolean(KEY_APP_SETTINGS, true),
        workspaceFiles = prefs.getBoolean(KEY_WORKSPACE_FILES, false),
        memoryAndSoul = prefs.getBoolean(KEY_MEMORY_AND_SOUL, true)
    )

    /** 流式导出到 [output]（调用方打开，本方法负责关闭）。 */
    fun export(password: String, options: BackupOptions, output: OutputStream) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                backupManager.export(pw, options, output)
                _state.value = BackupState.ExportDone
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeExportError(e))
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /** 从 SAF Uri 流式导入：先预览，需确认时进入勾选弹窗，否则直接全量还原。加密备份预览时解密一次，恢复复用该产物。 */
    fun import(uri: Uri, password: String) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            FileLogger.i(TAG, "导入请求：${describeUri(uri)}（${if (pw != null) "加密" else "明文"}）")
            try {
                val preview = withContext(Dispatchers.IO + NonCancellable) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                    input.use {
                        if (pw == null) {
                            backupManager.previewImport(it, null)
                        } else {
                            val file = backupManager.prepareImport(it, pw)
                            preparedImport = file
                            file.inputStream().use { source -> backupManager.previewImport(source, null, sourceAlreadyDecrypted = true) }
                        }
                    }
                }
                preview.onSuccess { preview ->
                    if (preview.hasWorkspaceData || preview.hasProviderConflicts) {
                        _state.value = BackupState.WorkspaceSelection(
                            preview.workspaces,
                            preview.providerConflicts,
                            uri
                        )
                    } else {
                        restoreFromUri(uri, null, ProviderConflictStrategy.OVERWRITE)
                    }
                }.onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            } finally {
                pw?.fill('\u0000')
                // 进入勾选弹窗时保留解密产物供确认后复用；其余（失败/直接还原/协程已取消）一律清理。
                if (_state.value !is BackupState.WorkspaceSelection || !isActive) {
                    clearPreparedImport()
                }
            }
        }
    }

    /** 确认后按所选工作区与冲突处置方式恢复，复用预览阶段已解密校验的产物。 */
    fun confirmImportSelection(selected: Set<String>, providerConflict: ProviderConflictStrategy) {
        val current = _state.value as? BackupState.WorkspaceSelection ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            val uri = current.uri
            if (uri != null) {
                restoreFromUri(uri, selected, providerConflict)
            } else {
                val staged = preparedImport
                if (staged == null) {
                    _state.value = BackupState.Error(context.getString(R.string.backup_read_failed))
                } else {
                    restoreFromStaged(staged, selected, providerConflict)
                }
            }
        }
    }

    /** 取消工作区勾选，中止导入并清理解密产物。 */
    fun cancelImportSelection() {
        if (_state.value is BackupState.WorkspaceSelection) {
            _state.value = BackupState.Idle
            val file = preparedImport
            preparedImport = null
            viewModelScope.launch(Dispatchers.IO + NonCancellable) { file?.delete() }
        }
    }

    private suspend fun restoreFromUri(
        uri: Uri,
        selected: Set<String>?,
        providerConflict: ProviderConflictStrategy
    ) {
        try {
            FileLogger.i(TAG, "开始还原：${describeUri(uri)}${if (selected != null) "，勾选工作区=${selected.size}个" else "，全量"}")
            val result = withContext(Dispatchers.IO) {
                // 加密备份用预览留下的解密产物；明文备份（preparedImport 为空）才重新打开 uri。
                val input = preparedImport?.inputStream() ?: context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                input.use { backupManager.import(it, null, selected, providerConflict, sourceAlreadyDecrypted = preparedImport != null) }
            }
            result.onSuccess {
                FileLogger.i(TAG, "还原成功：$it")
                _state.value = BackupState.ImportSuccess(it)
            }.onFailure {
                FileLogger.e(TAG, "还原失败", it)
                _state.value = BackupState.Error(describeImportError(it))
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "还原异常", e)
            _state.value = BackupState.Error(describeImportError(e))
        } finally {
            clearPreparedImport()
        }
    }

    /** 从已暂存（已解密或明文）的本地文件恢复：网络备份下载/解密后复用同一套导入语义。 */
    private suspend fun restoreFromStaged(
        source: File,
        selected: Set<String>?,
        providerConflict: ProviderConflictStrategy
    ) {
        try {
            val result = withContext(Dispatchers.IO) {
                source.inputStream().use {
                    backupManager.import(it, null, selected, providerConflict, sourceAlreadyDecrypted = preparedIsDecrypted)
                }
            }
            result.onSuccess {
                FileLogger.i(TAG, "还原成功（网络源）：$it")
                _state.value = BackupState.ImportSuccess(it)
            }.onFailure {
                FileLogger.e(TAG, "还原失败（网络源）", it)
                _state.value = BackupState.Error(describeImportError(it))
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "还原异常（网络源）", e)
            _state.value = BackupState.Error(describeImportError(e))
        } finally {
            clearPreparedImport()
        }
    }

    private suspend fun clearPreparedImport() {
        val file = preparedImport
        preparedImport = null
        preparedIsDecrypted = false
        withContext(Dispatchers.IO + NonCancellable) { file?.delete() }
    }

    override fun onCleared() {
        preparedImport?.delete()
        preparedImport = null
        super.onCleared()
    }

    /** 日志用 URI 摘要：只保留 scheme://authority/最后一段，避免记录完整路径。 */
    private fun describeUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: ""
        return "${uri.scheme}://${uri.authority}/…/$last"
    }

    /** 沿 cause 链找解密/解析失败的错误码；异常 message 是日志用的中文，不能直接展示给英文界面。 */
    private fun findCryptoError(e: Throwable): BackupCryptoError? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 4) {
            (current as? BackupCryptoException)?.let { return it.error }
            current = current.cause
            depth++
        }
        return null
    }

    private fun describeExportError(e: Throwable): String {
        return when (findCryptoError(e)) {
            BackupCryptoError.FILE_TOO_LARGE -> context.getString(R.string.backup_error_file_too_large)
            else -> e.message ?: context.getString(R.string.backup_export_failed)
        }
    }

    private fun describeImportError(e: Throwable): String {
        findCryptoError(e)?.let { error ->
            return context.getString(
                when (error) {
                    BackupCryptoError.WRONG_PASSWORD_OR_CORRUPTED -> R.string.backup_wrong_password
                    BackupCryptoError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
                    BackupCryptoError.TRUNCATED -> R.string.backup_error_truncated
                    BackupCryptoError.CORRUPTED -> R.string.backup_error_corrupted
                    BackupCryptoError.NOT_A_BACKUP_FILE -> R.string.backup_error_not_a_backup_file
                    BackupCryptoError.FILE_TOO_LARGE -> R.string.backup_error_file_too_large
                }
            )
        }
        return buildString {
            append(e.message ?: e::class.simpleName ?: context.getString(R.string.backup_import_failed))
            // 诊断期附上 cause 链（最多两层），方便定位真实异常
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by: ")
                append(cause::class.simpleName ?: context.getString(R.string.backup_error_unknown_exception))
                append(": ")
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
    }

    // ---- 网络备份（WebDAV / S3）----

    /** 保存目标：失败时给一次性提示（成功由表单自身关闭体现，不额外弹窗）。 */
    fun saveTarget(target: BackupTarget) {
        viewModelScope.launch {
            runCatching { targetRepository.save(target) }
                .onFailure {
                    FileLogger.e(TAG, "保存网络备份目标失败", it)
                    _networkState.value = NetworkBackupState.Notice(R.string.backup_net_save_failed, true)
                }
        }
    }

    fun deleteTarget(id: String) {
        viewModelScope.launch { runCatching { targetRepository.delete(id) } }
    }

    fun testTarget(id: String) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val target = targetRepository.getTarget(id)
            if (target == null) {
                _networkState.value = NetworkBackupState.Notice(R.string.backup_net_target_missing, true)
                return@launch
            }
            withContext(Dispatchers.IO) { targetRepository.storageFor(target).testConnection() }
                .onSuccess { _networkState.value = NetworkBackupState.Notice(R.string.backup_net_test_ok, false) }
                .onFailure { _networkState.value = NetworkBackupState.Notice(describeRemoteError(it), true) }
        }
    }

    /** 先导出到本地临时文件再上传到目标；口令仅用于加密导出，可留空（明文）。 */
    fun uploadToTarget(id: String, password: String) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val target = targetRepository.getTarget(id)
            if (target == null) {
                _networkState.value = NetworkBackupState.Notice(R.string.backup_net_target_missing, true)
                return@launch
            }
            val storage = targetRepository.storageFor(target)
            var temp: File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("netbackup", ".tar.gz", context.cacheDir)
                    file.outputStream().buffered().use { out ->
                        val pw = password.toCharArray().takeIf { it.isNotEmpty() }
                        try {
                            backupManager.export(pw, _exportOptions.value, out)
                        } finally {
                            pw?.fill('\u0000')
                        }
                    }
                    file
                }
                val uploaded = temp
                withContext(Dispatchers.IO) {
                    storage.upload("aharou-backup-${System.currentTimeMillis()}.tar.gz", uploaded)
                }
                    .onSuccess { _networkState.value = NetworkBackupState.Notice(R.string.backup_net_upload_ok, false) }
                    .onFailure { _networkState.value = NetworkBackupState.Notice(describeRemoteError(it), true) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "网络备份上传失败", e)
                _networkState.value = NetworkBackupState.Notice(describeRemoteError(e), true)
            } finally {
                val file = temp
                if (file != null) withContext(Dispatchers.IO + NonCancellable) { runCatching { file.delete() } }
            }
        }
    }

    fun loadRemoteBackups(id: String) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val target = targetRepository.getTarget(id)
            if (target == null) {
                _networkState.value = NetworkBackupState.Notice(R.string.backup_net_target_missing, true)
                return@launch
            }
            withContext(Dispatchers.IO) { targetRepository.storageFor(target).list() }
                .onSuccess { _networkState.value = NetworkBackupState.RemoteList(target.id, target.name, it) }
                .onFailure { _networkState.value = NetworkBackupState.Notice(describeRemoteError(it), true) }
        }
    }

    fun deleteRemoteBackup(targetId: String, name: String) {
        viewModelScope.launch {
            val target = targetRepository.getTarget(targetId) ?: return@launch
            val storage = targetRepository.storageFor(target)
            withContext(Dispatchers.IO) { storage.delete(name) }
                .onSuccess { loadRemoteBackups(targetId) }
                .onFailure { _networkState.value = NetworkBackupState.Notice(describeRemoteError(it), true) }
        }
    }

    /**
     * 从远端恢复：下载到 cacheDir 临时文件 → 加密则先解密暂存 → 预览 → 有工作区/冲突走勾选弹窗，
     * 否则直接还原。全程复用 [preparedImport] 暂存产物；口令在 [prepareImport] 消费完之后才清零，
     * 避免异步导入读到已清零口令（上游 0745b573 的坑）。
     */
    fun restoreFromRemote(targetId: String, name: String, password: String) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            var raw: File? = null
            try {
                val target = targetRepository.getTarget(targetId)
                    ?: throw RemoteStorageException(RemoteStorageErrorKind.UNKNOWN, "target missing")
                val storage = targetRepository.storageFor(target)
                val downloaded: File = withContext(Dispatchers.IO + NonCancellable) {
                    val file = File.createTempFile("netrestore", ".tar.gz", context.cacheDir)
                    val result = storage.download(name, file)
                    if (result.isFailure) {
                        runCatching { file.delete() }
                        result.getOrThrow()
                    }
                    file
                }
                raw = downloaded
                val preview = withContext(Dispatchers.IO + NonCancellable) {
                    if (pw == null) {
                        preparedImport = downloaded
                        preparedIsDecrypted = false
                        downloaded.inputStream().use { backupManager.previewImport(it, null) }
                    } else {
                        val decrypted = backupManager.prepareImport(downloaded.inputStream(), pw)
                        preparedImport = decrypted
                        preparedIsDecrypted = true
                        decrypted.inputStream().use {
                            backupManager.previewImport(it, null, sourceAlreadyDecrypted = true)
                        }
                    }
                }
                // 暂存已就绪：preparedImport 拥有最终文件；若与下载件不同（加密场景）则单独删掉下载件。
                if (downloaded !== preparedImport) {
                    withContext(Dispatchers.IO + NonCancellable) { runCatching { downloaded.delete() } }
                }
                raw = null
                preview.onSuccess { p ->
                    if (p.hasWorkspaceData || p.hasProviderConflicts) {
                        _state.value = BackupState.WorkspaceSelection(p.workspaces, p.providerConflicts, null)
                        _networkState.value = NetworkBackupState.Idle
                    } else {
                        restoreFromStaged(preparedImport!!, null, ProviderConflictStrategy.OVERWRITE)
                        _networkState.value = NetworkBackupState.Idle
                    }
                }.onFailure {
                    FileLogger.e(TAG, "远端备份预览失败", it)
                    _networkState.value = NetworkBackupState.Notice(describeRemoteError(it), true)
                    clearPreparedImport()
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "远端备份恢复失败", e)
                _networkState.value = NetworkBackupState.Notice(describeRemoteError(e), true)
                clearPreparedImport()
            } finally {
                // 口令只在 prepareImport 消费完之后（本协程末尾）清零。
                pw?.fill('\u0000')
                val pending = raw
                if (pending != null) withContext(Dispatchers.IO + NonCancellable) { runCatching { pending.delete() } }
            }
        }
    }

    fun dismissNetworkState() {
        _networkState.value = NetworkBackupState.Idle
    }

    /** 网络层异常按分类映射到字符串资源；解密/格式类错误沿用本地导入的文案。 */
    private fun describeRemoteError(e: Throwable): Int {
        findCryptoError(e)?.let { error ->
            return when (error) {
                BackupCryptoError.WRONG_PASSWORD_OR_CORRUPTED -> R.string.backup_wrong_password
                BackupCryptoError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
                BackupCryptoError.TRUNCATED -> R.string.backup_error_truncated
                BackupCryptoError.CORRUPTED -> R.string.backup_error_corrupted
                BackupCryptoError.NOT_A_BACKUP_FILE -> R.string.backup_error_not_a_backup_file
                BackupCryptoError.FILE_TOO_LARGE -> R.string.backup_error_file_too_large
            }
        }
        return when ((e as? RemoteStorageException)?.kind) {
            RemoteStorageErrorKind.CONFIG -> R.string.backup_net_error_config
            RemoteStorageErrorKind.AUTH -> R.string.backup_net_error_auth
            RemoteStorageErrorKind.NOT_FOUND -> R.string.backup_net_error_not_found
            RemoteStorageErrorKind.HTTP -> R.string.backup_net_error_http
            RemoteStorageErrorKind.NETWORK -> R.string.backup_net_error_network
            RemoteStorageErrorKind.UNKNOWN, null -> R.string.backup_net_error_unknown
        }
    }

    fun reset() {
        if (_state.value is BackupState.WorkspaceSelection) {
            cancelImportSelection()
        } else {
            _state.value = BackupState.Idle
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_PERMISSION_RULES = "permission_rules"
        private const val KEY_APP_SETTINGS = "app_settings"
        private const val KEY_WORKSPACE_FILES = "workspace_files"
        private const val KEY_MEMORY_AND_SOUL = "memory_and_soul"
    }
}
