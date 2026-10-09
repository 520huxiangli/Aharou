package com.aharou.feature.git.domain.pr

/**
 * Git / CI 面板的领域模型与纯解析逻辑（无 Android / 网络依赖）。
 *
 * remote url 解析为 [GitHubRepo] 坐标；GitHub REST 返回的 PR / 检查结果在此归约为
 * [PullRequestInfo] 与 [PrChecks]，供 UI 直接展示与「让 AI 分析」拼接摘要。
 */

/** GitHub 托管域名；当前只支持公有 github.com（含 HTTPS 与 SSH 两种 remote 写法）。 */
const val GITHUB_HOST = "github.com"

/** 从 remote url 解析出的仓库坐标（host 一律小写，不含 www）。 */
data class GitHubRepo(val host: String, val owner: String, val name: String) {
    /** `owner/name`，用于拼 REST 路径。 */
    val slug: String get() = "$owner/$name"
}

private val URL_RE = Regex("""^[a-z][a-z0-9+.-]*://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(.+)$""", RegexOption.IGNORE_CASE)
private val SCP_RE = Regex("""^(?:[^@/]+@)?([^/:]+):(.+)$""")

/**
 * 从 git remote url 解析 GitHub 仓库坐标，非 github.com 或格式不符返回 null。
 * 支持 `https://github.com/o/r.git`、`git@github.com:o/r.git`、`ssh://git@github.com/o/r.git`、
 * 带端口与带 userinfo 的写法。解析失败一律返回 null，由调用方隐藏整块区域。
 */
fun parseGitHubRemote(rawUrl: String): GitHubRepo? {
    val url = rawUrl.trim()
    if (url.isEmpty()) return null
    val (host, path) = if ("://" in url) {
        val m = URL_RE.find(url) ?: return null
        m.groupValues[1] to m.groupValues[2]
    } else {
        val m = SCP_RE.find(url) ?: return null
        m.groupValues[1] to m.groupValues[2]
    }
    if (host.lowercase().removePrefix("www.") != GITHUB_HOST) return null
    val segments = path.trim('/').removeSuffix(".git").split('/')
    if (segments.size < 2) return null
    val owner = segments[0]
    val name = segments[1]
    if (owner.isBlank() || name.isBlank()) return null
    return GitHubRepo(GITHUB_HOST, owner, name)
}

/** PR 生命周期状态。MERGED 与 CLOSED 都来自 `state=closed`，据 `merged_at` 区分。 */
enum class PrState { OPEN, DRAFT, MERGED, CLOSED }

/** CI 整体结论。[NONE] 表示既无 check-run 也无 commit status。 */
enum class CiStatus { SUCCESS, FAILURE, PENDING, NEUTRAL, NONE }

/** 一条失败的检查/状态项，[url] 为其详情页（可能为空）。 */
data class FailedCheck(val name: String, val url: String?)

/** 某次提交的 CI 聚合结果。 */
data class PrChecks(
    val status: CiStatus,
    val total: Int,
    val failed: List<FailedCheck>
)

/** 与当前分支关联的 Pull Request。 */
data class PullRequestInfo(
    val number: Int,
    val title: String,
    val url: String,
    val state: PrState,
    val headSha: String = "",
    val checks: PrChecks = PrChecks(CiStatus.NONE, 0, emptyList())
) {
    val isOpen: Boolean get() = state == PrState.OPEN || state == PrState.DRAFT
}

/**
 * 把多个检查项的结论归约为整体结论。优先级：失败 > 进行中 > 成功 > 中性——
 * 只要有一项已失败就先按失败呈现（用户需要尽快处置），不因其它项还在跑而显示「进行中」。
 */
fun aggregateCi(statuses: List<CiStatus>): CiStatus = when {
    statuses.isEmpty() -> CiStatus.NONE
    statuses.any { it == CiStatus.FAILURE } -> CiStatus.FAILURE
    statuses.any { it == CiStatus.PENDING } -> CiStatus.PENDING
    statuses.any { it == CiStatus.SUCCESS } -> CiStatus.SUCCESS
    else -> CiStatus.NEUTRAL
}
