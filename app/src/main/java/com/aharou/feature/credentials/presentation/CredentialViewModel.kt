package com.aharou.feature.credentials.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.credentials.data.CredentialInjectSettingsRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import com.aharou.feature.git.domain.GitRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 凭据页 UI 编排：凭据 CRUD + 提交署名(user.name/email) 配置。
 *
 * 提交署名读写走 `git config`（[GitRepository.setUserIdentity] / [getUserName] / [getUserEmail]）：
 * **优先项目级**（当前工作区 ~/workspace/.git/config），无则退全局（持久挂载 /root/.aicode/.gitconfig）。
 * UI 与终端敲 `git config user.name` 读到的是同一份署名——优先项目级、无则退全局，无两套源头竞争。
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object { const val TAG = "CredentialViewModel" }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        /** 容器 git config 当前 user.name，作编辑框初值。 */
        val userName: String = "",
        /** 容器 git config 当前 user.email，作编辑框初值。 */
        val userEmail: String = "",
        /** 容器 `git config --global` 实际 user.name，回显「git 实际值」（与 userName 同源）。 */
        val globalUserName: String = "",
        /** 容器 git config 当前 remote.origin.url，作仓库地址编辑框初值。 */
        val repoUrl: String = "",
        /** 远程模式下「自动注入凭证到远程服务器」开关状态（按服务器独立记忆）。 */
        val autoInjectEnabled: Boolean = false,
        /** 远程模式下该服务器是否首次进入（未询问过），UI 据此弹确认框。 */
        val showAutoInjectPrompt: Boolean = false,
        /** 当前是否远程模式（决定开关是否显示）。 */
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    // 非反应式来源的状态：git config 读出的署名、仓库地址、注入开关、toast。credentials 走文件仓储 Flow。
    private val _extra = MutableStateFlow(Extra())
    private data class Extra(
        val userName: String = "",
        val userEmail: String = "",
        val globalUserName: String = "",
        val repoUrl: String = "",
        val autoInjectEnabled: Boolean = false,
        val showAutoInjectPrompt: Boolean = false,
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    val state: StateFlow<UiState> = combine(credentialRepository.getAll(), _extra) { creds, extra ->
        UiState(
            credentials = creds,
            userName = extra.userName,
            userEmail = extra.userEmail,
            globalUserName = extra.globalUserName,
            repoUrl = extra.repoUrl,
            autoInjectEnabled = extra.autoInjectEnabled,
            showAutoInjectPrompt = extra.showAutoInjectPrompt,
            isRemote = extra.isRemote,
            toast = extra.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        refreshIdentity()
        refreshAutoInjectState()
    }

    /** 从容器 git config 读取当前署名与仓库地址刷新 UI（编辑框初值 + 实际值回显）。可重入，进凭据页时调一次兜住终端改动。 */
    fun refreshIdentity() {
        viewModelScope.launch {
            runCatching {
                _extra.update {
                    it.copy(
                        userName = gitRepository.getUserName(),
                        userEmail = gitRepository.getUserEmail(),
                        globalUserName = gitRepository.getUserName(),
                        repoUrl = gitRepository.getRepoUrl()
                    )
                }
            }.onFailure { FileLogger.w(TAG, "读取 git 全局署名失败: ${it.message}") }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            try {
                credentialRepository.save(credential)
                syncInjectionIfEnabled() // 凭据变更后重新同步到已开启注入的远程服务器
                toast(context.getString(R.string.credential_toast_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存凭据失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
            syncInjectionIfEnabled() // 删凭据后从远程注入文件移除，否则远程仍能用旧凭据
            toast(context.getString(R.string.credential_toast_deleted))
        }
    }

    // ---------- 远程自动注入开关 ----------

    /** 当前远程服务器标识（host:port 哈希）；本地模式/未连接时为 null。 */
    private fun currentServerKey(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return injectSettings.serverKey(cfg.host, cfg.port)
    }

    /** 按当前服务器刷新开关状态与「是否首次询问」。进入凭据页/连接变化时调用。 */
    fun refreshAutoInjectState() {
        val key = currentServerKey()
        _extra.update {
            it.copy(
                isRemote = key != null,
                autoInjectEnabled = key?.let { k -> injectSettings.isAutoInjectEnabled(k) } ?: false,
                showAutoInjectPrompt = key?.let { k -> !injectSettings.hasAsked(k) } ?: false
            )
        }
    }

    /** 用户切换开关：持久化 + 立即注入/撤销。 */
    fun setAutoInject(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** 首次询问弹窗选择后调用：标记已询问 + 应用选择。 */
    fun dismissAutoInjectPrompt(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.markAsked(key)
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** 开启时上传远程注入配置（凭据 + includeIf 限定的 gitconfig），关闭时撤销。 */
    private suspend fun applyInjection(key: String, enabled: Boolean) {
        if (!enabled) {
            remoteSshConnection.removeGitCredentialConfig()
            return
        }
        val creds = credentialRepository.getAll().first()
        val workspaceRoot = workspaceRepository.currentPath()
            .takeIf { it.isNotBlank() && it != "/" }
            ?: remoteSshConnection.config?.remoteWorkspacePath.orEmpty()
        remoteSshConnection.uploadGitCredentialConfig(creds, workspaceRoot)
    }

    /** 凭据增删后若当前服务器已开启注入，重新同步到远程。 */
    private suspend fun syncInjectionIfEnabled() {
        val key = currentServerKey() ?: return
        if (injectSettings.isAutoInjectEnabled(key)) applyInjection(key, true)
    }

    /** 保存提交署名与仓库地址：跑 `git config` 写入对应 key 真源 + 刷新回显。UI 与命令行同一文件。 */
    fun saveUserIdentity(name: String, email: String, repoUrl: String) {
        viewModelScope.launch {
            try {
                gitRepository.setUserIdentity(name, email)
                gitRepository.setRepoUrl(repoUrl)
                refreshIdentity()
                toast(context.getString(R.string.credential_toast_identity_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存署名失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun consumeToast() = _extra.update { it.copy(toast = null) }

    private fun toast(msg: String) = _extra.update { it.copy(toast = msg) }
}
