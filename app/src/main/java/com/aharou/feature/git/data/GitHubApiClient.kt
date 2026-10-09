package com.aharou.feature.git.data

import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.aharou.feature.git.domain.pr.CiStatus
import com.aharou.feature.git.domain.pr.FailedCheck
import com.aharou.feature.git.domain.pr.GitHubRepo
import com.aharou.feature.git.domain.pr.PrChecks
import com.aharou.feature.git.domain.pr.PrState
import com.aharou.feature.git.domain.pr.PullRequestInfo
import com.aharou.feature.git.domain.pr.aggregateCi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub REST 调用的失败类别，供 UI 映射为人话提示。 */
enum class PrErrorKind { UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, NOT_FOUND, SERVER, NETWORK, UNKNOWN }

/**
 * GitHub API 调用失败。[rateLimitResetEpochSec] 仅限流场景有值（取自 `X-RateLimit-Reset`）。
 * 消息本身是英文技术串，面向用户展示时由 UI 层按 [kind] 映射成中文/英文文案。
 */
class PrApiException(
    val kind: PrErrorKind,
    val httpCode: Int? = null,
    val rateLimitResetEpochSec: Long? = null,
    cause: Throwable? = null
) : Exception("GitHub API 请求失败：${kind.name} (code=${httpCode ?: "-"})", cause)

/**
 * GitHub 只读 REST 客户端：列出分支关联 PR、取 PR head 提交的 CI 结论。
 *
 * 仅用 OkHttp + org.json（与 core/net 既有 GitHub 相关实现一致），沿用全局代理与统一超时。
 * 401/403/404/429 等按 [PrErrorKind] 分类抛出，调用方据类别给出人话提示。
 */
@Singleton
class GitHubApiClient @Inject constructor() {

    private companion object {
        const val TAG = "GitHubApiClient"
        const val API_BASE = "https://api.github.com"
        const val ACCEPT = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"

        /** 每次最多取多少条 PR（够挑出与分支关联的那条，避免大仓库一次拉几百条）。 */
        const val PR_PAGE_SIZE = 10
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    /** 与 [branch] 关联的 PR（含已关闭/已合并，取最近更新的在前）。token 为空时匿名请求（仅公开仓库可用）。 */
    suspend fun listPullRequests(repo: GitHubRepo, branch: String, token: String?): List<PullRequestInfo> =
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/repos/${repo.owner}/${repo.name}/pulls" +
                "?head=${enc(repo.owner)}:${enc(branch)}&state=all&sort=updated&direction=desc" +
                "&per_page=$PR_PAGE_SIZE"
            parsePullRequests(get(url, token))
        }

    /**
     * 与某次提交关联的 PR。fork 场景下 `head=` 过滤会漏（head 仓库 owner 与被查仓库不同），
     * 用提交关联接口兜底。
     */
    suspend fun listPullRequestsForCommit(repo: GitHubRepo, sha: String, token: String?): List<PullRequestInfo> =
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/repos/${repo.owner}/${repo.name}/commits/$sha/pulls"
            parsePullRequests(get(url, token))
        }

    /**
     * 某次提交的 CI 结论。优先 `check-runs`（GitHub Actions 等），其为空或权限不足时降级
     * 到 combined `status`（旧式 status API）。
     */
    suspend fun checks(repo: GitHubRepo, sha: String, token: String?): PrChecks = withContext(Dispatchers.IO) {
        val runs = runCatching { checkRuns(repo, sha, token) }.getOrElse { e ->
            if (e is PrApiException && (e.kind == PrErrorKind.FORBIDDEN || e.kind == PrErrorKind.NOT_FOUND)) {
                PrChecks(CiStatus.NONE, 0, emptyList())
            } else {
                throw e
            }
        }
        if (runs.total > 0) runs else combinedStatus(repo, sha, token)
    }

    private fun checkRuns(repo: GitHubRepo, sha: String, token: String?): PrChecks {
        val url = "$API_BASE/repos/${repo.owner}/${repo.name}/commits/$sha/check-runs?per_page=100"
        val arr = JSONObject(get(url, token)).optJSONArray("check_runs") ?: JSONArray()
        val statuses = ArrayList<CiStatus>(arr.length())
        val failed = mutableListOf<FailedCheck>()
        for (i in 0 until arr.length()) {
            val run = arr.optJSONObject(i) ?: continue
            val conclusion = run.optString("conclusion")
            val status = if (run.optString("status") != "completed") {
                CiStatus.PENDING
            } else {
                conclusionToCi(conclusion)
            }
            statuses.add(status)
            if (status == CiStatus.FAILURE) {
                failed.add(FailedCheck(run.optString("name").ifBlank { "check" }, run.optString("html_url").ifBlank { null }))
            }
        }
        return PrChecks(aggregateCi(statuses), statuses.size, failed)
    }

    private fun combinedStatus(repo: GitHubRepo, sha: String, token: String?): PrChecks {
        val url = "$API_BASE/repos/${repo.owner}/${repo.name}/commits/$sha/status?per_page=100"
        val root = JSONObject(get(url, token))
        val arr = root.optJSONArray("statuses") ?: JSONArray()
        val total = root.optInt("total_count", arr.length())
        if (total == 0) return PrChecks(CiStatus.NONE, 0, emptyList())
        val failed = mutableListOf<FailedCheck>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("state") == "failure" || s.optString("state") == "error") {
                failed.add(FailedCheck(s.optString("context").ifBlank { "status" }, s.optString("target_url").ifBlank { null }))
            }
        }
        val status = when (root.optString("state")) {
            "success" -> CiStatus.SUCCESS
            "failure", "error" -> CiStatus.FAILURE
            "pending" -> CiStatus.PENDING
            else -> CiStatus.NEUTRAL
        }
        return PrChecks(status, total, failed)
    }

    private fun conclusionToCi(conclusion: String): CiStatus = when (conclusion) {
        "success" -> CiStatus.SUCCESS
        "failure", "timed_out", "cancelled", "action_required", "startup_failure" -> CiStatus.FAILURE
        "neutral", "skipped", "stale" -> CiStatus.NEUTRAL
        else -> CiStatus.NEUTRAL
    }

    private fun parsePullRequests(body: String): List<PullRequestInfo> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toPullRequest) }
    }

    private fun toPullRequest(o: JSONObject): PullRequestInfo? {
        val number = o.optInt("number", -1)
        if (number <= 0) return null
        val merged = !o.isNull("merged_at")
        val state = when {
            merged -> PrState.MERGED
            o.optString("state") == "closed" -> PrState.CLOSED
            o.optBoolean("draft", false) -> PrState.DRAFT
            else -> PrState.OPEN
        }
        return PullRequestInfo(
            number = number,
            title = o.optString("title"),
            url = o.optString("html_url"),
            state = state,
            headSha = o.optJSONObject("head")?.optString("sha").orEmpty()
        )
    }

    /** 发一次 GET，2xx 返回正文，其余按类别抛 [PrApiException]。 */
    private fun get(url: String, token: String?): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT)
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        try {
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) return resp.body?.string().orEmpty()
                throw mapError(resp)
            }
        } catch (e: PrApiException) {
            throw e
        } catch (e: IOException) {
            FileLogger.w(TAG, "GitHub 请求异常：$url", e)
            throw PrApiException(PrErrorKind.NETWORK, cause = e)
        }
    }

    private fun mapError(resp: Response): PrApiException {
        val code = resp.code
        val remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull()
        val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()
        val kind = when {
            code == 401 -> PrErrorKind.UNAUTHORIZED
            code == 404 -> PrErrorKind.NOT_FOUND
            (code == 403 && remaining == 0) || code == 429 -> PrErrorKind.RATE_LIMITED
            code == 403 -> PrErrorKind.FORBIDDEN
            code in 500..599 -> PrErrorKind.SERVER
            else -> PrErrorKind.UNKNOWN
        }
        FileLogger.i(TAG, "GitHub 返回异常码 code=$code kind=${kind.name} remaining=$remaining")
        return PrApiException(kind, code, reset)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
