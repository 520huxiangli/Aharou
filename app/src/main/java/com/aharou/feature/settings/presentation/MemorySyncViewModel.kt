package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.memory.MemorySyncManager
import com.aharou.feature.agent.domain.memory.MemorySyncOutcome
import com.aharou.feature.agent.domain.memory.MemorySyncScheduler
import com.aharou.feature.agent.domain.memory.MemorySyncSettings
import com.aharou.feature.credentials.data.repository.FileCredentialRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆同步的界面状态：仓库地址、是否正在同步、上次结果。
 *
 * 单独成一个 ViewModel 而不是并进 [SettingsViewModel]：后者已经是超大文件（行数棘轮在盯），
 * 同步这块与设置页其它内容没有耦合，独立更好维护。
 */
@HiltViewModel
class MemorySyncViewModel @Inject constructor(
    private val syncManager: MemorySyncManager,
    private val settings: MemorySyncSettings,
    private val scheduler: MemorySyncScheduler,
    private val credentials: FileCredentialRepository,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _outcome = MutableStateFlow<MemorySyncOutcome?>(null)
    val outcome: StateFlow<MemorySyncOutcome?> = _outcome.asStateFlow()

    fun repoUrl(): String = settings.repoUrl

    fun saveRepoUrl(url: String) {
        settings.repoUrl = url
        // 排不排周期任务取决于「开关 + 地址」两者，地址改了要重排；顺手推一次，省得等下一个周期。
        scheduler.apply(force = true)
        if (settings.autoSync) syncNow()
    }

    fun autoSyncEnabled(): Boolean = settings.autoSync

    /** 后台同步的真实间隔（分钟），用于界面提示。 */
    fun autoSyncIntervalMinutes(): Int = settings.autoSyncIntervalMinutes

    fun setAutoSync(enabled: Boolean) {
        settings.autoSync = enabled
        scheduler.apply(force = true)
        // 刚打开就立刻跑一次：不必等第一个周期，界面上也就不必再留一个手动按钮。
        if (enabled) syncNow()
    }

    /** 上次同步的结果文案；从未同步过为空串。 */
    fun lastResult(): String = settings.lastResult

    /**
     * 上次同步中「疑似被其他设备删除」的条目（仓库相对路径）；无则空列表。
     *
     * 从持久化的设置读：同步结果对象是一次性的，界面在结果被消费后也要能列出这份清单。仅是报告，
     * 删不删由用户决定，同步本身不会执行删除。
     */
    fun pendingDeletions(): List<String> =
        settings.lastPendingDeletions.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val result = runCatching { syncManager.sync() }
                .getOrElse { MemorySyncOutcome(false, it.message.orEmpty().ifBlank { "同步失败" }) }
            _syncing.value = false
            _outcome.value = result
        }
    }

    fun consumeOutcome() {
        _outcome.value = null
    }

    /**
     * 是否已为当前仓库主机配过访问令牌。
     *
     * 只看「有没有」不看值：[FileCredentialRepository] 在内存里缓存了整份凭据，这里不会读盘。
     */
    suspend fun hasToken(): Boolean = repoHost()?.let { credentials.findForHost(it) != null } ?: false

    /**
     * 保存访问令牌。
     *
     * 容器里的 git **只读** `~/.aharou/git-credentials`（credential helper 直读该文件），**不读凭据库**，
     * 所以直接写 git 凭据；主机从仓库地址解析，用户名按 cnb 平台惯例固定。空串表示清除该主机凭据。
     * 写后立刻同步一次，好让用户马上看到凭据到底能不能用。
     */
    fun saveToken(token: String) {
        val host = repoHost() ?: return
        viewModelScope.launch {
            val existing = runCatching { credentials.findForHost(host) }.getOrNull()
            if (token.isBlank()) {
                existing?.let { runCatching { credentials.delete(it.id) } }
            } else {
                runCatching {
                    credentials.save(
                        GitCredential(id = host, host = host, username = GIT_USERNAME, token = token),
                    )
                }
            }
            if (token.isNotBlank() && settings.autoSync) syncNow()
        }
    }

    /** 仓库地址的主机名；没填或解析不出来时返回 null。 */
    private fun repoHost(): String? = runCatching {
        java.net.URI(settings.repoUrl.trim()).host?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        /** cnb 平台的 git 用户名。 */
        const val GIT_USERNAME = "cnb"
    }
}
