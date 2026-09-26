package com.aharou.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.backup.domain.BackupDecryptionException
import com.aharou.feature.backup.domain.BackupManager
import com.aharou.feature.backup.domain.BackupOptions
import com.aharou.feature.backup.domain.RestoreStats
import com.aharou.feature.backup.domain.WorkspaceBackupMeta
import com.aharou.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aharou.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    /** 导入预览完成，备份含工作区数据：等待用户勾选要恢复的工作区（暂存 uri 与口令）。 */
    data class WorkspaceSelection(
        val workspaces: List<WorkspaceBackupMeta>,
        val uri: Uri,
        val password: String
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

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
            .apply()
    }

    private fun loadExportOptions(): BackupOptions = BackupOptions(
        providers = prefs.getBoolean(KEY_PROVIDERS, true),
        remoteConnections = prefs.getBoolean(KEY_REMOTE_CONNECTIONS, true),
        chatHistory = prefs.getBoolean(KEY_CHAT_HISTORY, true),
        mcpServers = prefs.getBoolean(KEY_MCP_SERVERS, true),
        permissionRules = prefs.getBoolean(KEY_PERMISSION_RULES, true),
        appSettings = prefs.getBoolean(KEY_APP_SETTINGS, true),
        workspaceFiles = prefs.getBoolean(KEY_WORKSPACE_FILES, false)
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
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /** 从 SAF Uri 流式导入：先预览，备份含工作区数据时进入勾选确认，否则全量还原。 */
    fun import(uri: Uri, password: String) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            FileLogger.i(TAG, "导入请求：${describeUri(uri)}（${if (pw != null) "加密" else "明文"}）")
            try {
                val input = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                }
                input.use { backupManager.previewImport(it, pw) }
                    .onSuccess { preview ->
                        if (preview.hasWorkspaceData) {
                            _state.value = BackupState.WorkspaceSelection(preview.workspaces, uri, password)
                        } else {
                            restoreFromUri(uri, pw, null)
                        }
                    }
                    .onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            }
        }
    }

    /** 勾选确认后按所选工作区恢复（重新打开输入流执行真正还原）。 */
    fun confirmImportSelection(selected: Set<String>) {
        val current = _state.value as? BackupState.WorkspaceSelection ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = current.password.toCharArray().takeIf { it.isNotEmpty() }
            restoreFromUri(current.uri, pw, selected)
        }
    }

    /** 取消工作区勾选，中止导入。 */
    fun cancelImportSelection() {
        if (_state.value is BackupState.WorkspaceSelection) {
            _state.value = BackupState.Idle
        }
    }

    private suspend fun restoreFromUri(uri: Uri, pw: CharArray?, selected: Set<String>?) {
        try {
            FileLogger.i(TAG, "开始还原：${describeUri(uri)}${if (selected != null) "，勾选工作区=${selected.size}个" else "，全量"}")
            val input = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
            }
            input.use { backupManager.import(it, pw, selected) }
                .onSuccess {
                    FileLogger.i(TAG, "还原成功：$it")
                    _state.value = BackupState.ImportSuccess(it)
                }
                .onFailure {
                    FileLogger.e(TAG, "还原失败", it)
                    _state.value = BackupState.Error(describeImportError(it))
                }
        } catch (e: Exception) {
            FileLogger.e(TAG, "还原异常", e)
            _state.value = BackupState.Error(describeImportError(e))
        }
    }

    /** 日志用 URI 摘要：只保留 scheme://authority/最后一段，避免记录完整路径。 */
    private fun describeUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: ""
        return "${uri.scheme}://${uri.authority}/…/$last"
    }

    private fun describeImportError(e: Throwable): String = when (e) {
        is BackupDecryptionException -> e.message ?: context.getString(R.string.backup_wrong_password)
        else -> buildString {
            append(e.message ?: e::class.simpleName ?: context.getString(R.string.backup_import_failed))
            // 诊断期附上 cause 链（最多两层），方便定位真实异常
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by: ")
                append(cause::class.simpleName ?: "未知异常")
                append(": ")
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
    }

    fun reset() {
        _state.value = BackupState.Idle
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
    }
}
