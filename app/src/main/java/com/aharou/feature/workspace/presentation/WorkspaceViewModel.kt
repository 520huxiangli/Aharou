package com.aharou.feature.workspace.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    /** 本地执行模式（非远程 SSH）：本地模式下才可添加外部本地目录工作区。 */
    val isLocalMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it != ExecutionMode.REMOTE_SSH }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 远程工作区初始化失败提示（根路径/默认工作区创建失败）。 */
    val initError: StateFlow<String?> = repository.initError

    /** 添加外部本地工作区失败提示（目录不可写/无法解析等）。 */
    val addError: StateFlow<String?> = repository.addError
    val externalWarningDismissed: StateFlow<Boolean> = repository.externalWarningDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private companion object {
        const val TAG = "WorkspaceViewModel"
    }

    /** 移除外部本地工作区时是否一并删除其聊天记录（用于删除确认文案）。 */
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = repository.deleteExternalWorkspaceSessionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 消费初始化错误提示，避免重复弹 Toast。 */
    fun consumeInitError() {
        repository.consumeInitError()
    }

    /** 消费添加外部工作区失败提示，避免重复弹 Toast。 */
    fun consumeAddError() {
        repository.consumeAddError()
    }

    init {
        viewModelScope.launch { runCatching { repository.initialize() } }
        // 模式切换后重新加载工作区列表（本地 File.listFiles ↔ 远程 SFTP ls）。
        // drop(1) 跳过首帧（init 已调 initialize），仅响应后续切换。
        viewModelScope.launch {
            executionModeHolder.mode.drop(1).distinctUntilChanged().collect {
                runCatching { repository.initialize() }
            }
        }
    }

    fun selectWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(name) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        onResult(ws)
    }

    /** 打开面板前刷新外部工作区可用性（存储卡拔插后列表与当前选中及时更新）。 */
    fun refreshAvailability() = viewModelScope.launch {
        runCatching { repository.refreshAvailability() }
    }

    fun setExternalWarningDismissed(dismissed: Boolean) = viewModelScope.launch {
        runCatching { repository.setExternalWarningDismissed(dismissed) }
    }

    /** 注册用户所选设备目录为外部本地工作区，不自动切换；切换由调用方走确认流程。 */
    fun addExternalWorkspace(uri: Uri, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.addExternalWorkspace(uri) }.getOrNull()
        onResult(ws)
    }

    fun deleteWorkspace(name: String) = viewModelScope.launch {
        // 失败不能静默：目录没删掉却看不见任何提示时，用户会以为删了，
        // 下次启动扫描又把它列出来。提示文案由 repository 通过 addError 给出。
        runCatching { repository.deleteWorkspace(name) }
            .onFailure { FileLogger.w(TAG, "删除工作区失败: $name", it) }
    }

    /** 重命名工作区；[onResult] 收到改名后的工作区（失败为 null）。 */
    fun renameWorkspace(oldName: String, newName: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.renameWorkspace(oldName, newName) }.getOrNull()
        onResult(ws)
    }
}
