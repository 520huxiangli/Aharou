package com.aharou.feature.git.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import com.aharou.feature.git.data.GitHubApiClient
import com.aharou.feature.git.data.GitHubTokenStore
import com.aharou.feature.git.data.PrApiException
import com.aharou.feature.git.data.PrErrorKind
import com.aharou.feature.git.domain.GitRepository
import com.aharou.feature.git.domain.pr.CiStatus
import com.aharou.feature.git.domain.pr.GitHubRepo
import com.aharou.feature.git.domain.pr.PullRequestInfo
import com.aharou.feature.git.domain.pr.parseGitHubRemote
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Git 页「CI / Pull Request」区域的状态与数据加载。
 *
 * 只读展示：解析 remote 为 GitHub 仓库 → 取令牌（git 凭据优先，其次本模块单独存的 API token）
 * → 查当前分支关联 PR 与其 head 提交的 CI 结论。远端非 GitHub / 无 remote 时整块隐藏。
 */
@HiltViewModel
class PullRequestViewModel @Inject constructor(
    private val repository: GitRepository,
    private val client: GitHubApiClient,
    private val tokenStore: GitHubTokenStore,
    private val credentials: CredentialRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object {
        const val TAG = "PullRequestViewModel"
    }

    data class PullRequestUiState(
        /** 是否处于「可展示」状态（GitHub remote 解析成功）。false 时 UI 整块隐藏。 */
        val visible: Boolean = false,
        val loading: Boolean = false,
        val repo: GitHubRepo? = null,
        val branch: String? = null,
        val pullRequest: PullRequestInfo? = null,
        /** 非 null 时的错误类别，UI 据此给人话提示。 */
        val error: PrErrorKind? = null,
        /** 限流场景下 `X-RateLimit-Reset` 的 Unix 秒；UI 提示何时可重试。 */
        val rateLimitResetEpochSec: Long? = null,
        /** 是否建议用户填 GitHub token（无 token 且请求被拒/限流）。 */
        val needsToken: Boolean = false,
        val tokenDialogVisible: Boolean = false
    )

    private val _state = MutableStateFlow(PullRequestUiState())
    val state: StateFlow<PullRequestUiState> = _state.asStateFlow()

    /** 缓存本次解析出的令牌，避免每次刷新都读凭据。 */
    private var cachedToken: String? = null

    /** 重新拉取 PR 与 CI。branch 由 Git 页传入（当前分支），null 时退化为按 HEAD 提交关联查询。 */
    fun refresh(branch: String?) {
        if (_state.value.loading) return
        viewModelScope.launch {
            val repoUrl = runCatching { repository.getRepoUrl() }.getOrDefault("")
            val repo = parseGitHubRemote(repoUrl)
            if (repo == null) {
                _state.update { it.copy(visible = false, loading = false, pullRequest = null, error = null, repo = null) }
                return@launch
            }
            val branchName = branch?.takeIf { it.isNotBlank() && it != "HEAD" }
            _state.update { it.copy(visible = true, loading = true, repo = repo, branch = branchName, error = null, needsToken = false) }
            cachedToken = resolveToken(repo)
            val hasToken = !cachedToken.isNullOrBlank()
            try {
                val pr = findPullRequest(repo, branchName, cachedToken)
                val withChecks = if (pr != null && pr.headSha.isNotBlank()) {
                    pr.copy(checks = client.checks(repo, pr.headSha, cachedToken))
                } else {
                    pr
                }
                _state.update { it.copy(loading = false, pullRequest = withChecks, error = null, needsToken = false) }
            } catch (e: PrApiException) {
                FileLogger.w(TAG, "获取 PR/CI 失败：${e.kind}", e)
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.kind,
                        rateLimitResetEpochSec = e.rateLimitResetEpochSec,
                        needsToken = !hasToken && e.kind != PrErrorKind.NETWORK
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "获取 PR/CI 异常", e)
                _state.update { it.copy(loading = false, error = PrErrorKind.UNKNOWN) }
            }
        }
    }

    /** git 凭据（按 host）优先，其次本模块单独存的 GitHub API token。 */
    private suspend fun resolveToken(repo: GitHubRepo): String? =
        runCatching { credentials.findForHost(repo.host)?.token }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: tokenStore.token(repo.host)

    private suspend fun findPullRequest(repo: GitHubRepo, branch: String?, token: String?): PullRequestInfo? {
        if (branch != null) {
            client.listPullRequests(repo, branch, token).firstOrNull()?.let { return it }
        }
        // 无分支名（detached HEAD）或按 head 过滤未命中（多为 fork PR）时，退回按 HEAD 提交关联查询。
        val sha = runCatching { repository.log(limit = 1).firstOrNull()?.hash }.getOrNull() ?: return null
        return client.listPullRequestsForCommit(repo, sha, token).firstOrNull()
    }

    fun openTokenDialog() = _state.update { it.copy(tokenDialogVisible = true) }

    fun dismissTokenDialog() = _state.update { it.copy(tokenDialogVisible = false) }

    /** 保存用户手填的 GitHub token（空串清除），保存后立即重试一次加载。 */
    fun saveToken(value: String) {
        val repo = _state.value.repo ?: return
        val trimmed = value.trim()
        tokenStore.save(repo.host, trimmed)
        cachedToken = trimmed.takeIf { it.isNotEmpty() }
        _state.update { it.copy(tokenDialogVisible = false, needsToken = false) }
        if (trimmed.isNotEmpty()) refresh(_state.value.branch)
    }

    /**
     * 拼出 CI 失败摘要供 AI 分析；无 PR 或 CI 非失败时返回 null。文案模板在 strings.xml。
     */
    fun buildFailureSummary(): String? {
        val pr = _state.value.pullRequest ?: return null
        if (pr.checks.status != CiStatus.FAILURE) return null
        val header = context.getString(R.string.git_pr_ai_prompt_header, pr.number, pr.title, pr.url)
        val failed = pr.checks.failed
        if (failed.isEmpty()) return header
        return buildString {
            appendLine(header)
            appendLine(context.getString(R.string.git_pr_ai_prompt_failed))
            failed.forEach { check ->
                append("- ").append(check.name)
                check.url?.let { append(" (").append(it).append(')') }
                appendLine()
            }
        }.trimEnd()
    }
}
