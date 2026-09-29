package com.aharou.feature.agent.domain.skill.market

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 技能仓库的平台访问适配：把「取单个文件」「列整个文件树」按平台翻译成各自的地址与响应格式，
 * 使技能市场不绑定 GitHub。
 *
 * 已实测可用的平台（2026-09-30，容器内直连）：
 * - `github`：经第三方反代访问 GitHub 的 raw 与 API（见 [MIRRORS]），国内可直连；
 * - `gitee`：文件走 `/raw/`（302 重定向，OkHttp 自动跟随），目录走官方 v5 API，国内最快；
 * - `gitlab`：文件走 `/-/raw/`，目录走 v4 API；
 * - Gitea/Forgejo/Codeberg 系：文件走 `/raw/branch/`，目录走 v1 API。
 *
 * CNB（cnb.cool）不在此列——它的文件地址返回单页应用 HTML、OpenAPI 需令牌，无法作为公开文件源。
 */
object SkillRepoAccess {

    const val GITHUB = "github"
    const val GITEE = "gitee"
    const val GITLAB = "gitlab"

    /** 默认分支：用户只给仓库地址、没指定分支时用它，由服务端重定向到真正的默认分支。 */
    const val HEAD = "HEAD"

    /**
     * 能同时转发 `api.github.com` 与 raw 的反代，按优先级排。
     *
     * 不用 jsDelivr 做主通道：它对超过 50MB 的仓库直接 403（技能仓库动辄上百 MB），
     * 且比反代慢。但它的目录接口不占 GitHub 额度，留着当小仓库的兜底。
     */
    private val MIRRORS = listOf(
        // 带 token 转发，实测额度 5000/小时，主力
        "https://gh-proxy.com/",
        // 匿名转发，额度只有 60/小时且与他人共享——只在上面那个挂了时才轮得到
        "https://git.yylx.win/"
    )

    /** 只转发 raw、不转发 API 的镜像（转发 api.github.com 一律 403）。
     *  raw 走的是 raw.githubusercontent.com，不占 GitHub API 额度，所以可以多挂几个。 */
    private val RAW_ONLY_MIRRORS = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    /**
     * jsDelivr 的目录接口：与 GitHub API 额度体系无关，专门当所有反代都挂时的最后一道。
     * 代价是仓库超过 50MB 会 403、响应也更慢，所以排在反代后面。
     */
    private fun jsDelivrTree(coord: Coord): String =
        "https://data.jsdelivr.com/v1/package/gh/${coord.repo}@${coord.ref}/flat"

    /** 一个仓库坐标。 */
    data class Coord(val host: String, val repo: String, val ref: String)

    /** 解析结果：[coord] 定位仓库，[subPath] 是链接里带的子目录（如 `skills/docx`），可为空。 */
    data class Parsed(val coord: Coord, val subPath: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析用户粘贴的仓库地址。接受以下形式：
     * - `https://github.com/owner/repo`
     * - `https://github.com/owner/repo/tree/main/skills/docx`
     * - `https://gitee.com/owner/repo`、`https://gitlab.com/group/repo`
     * - 简写 `owner/repo`（按 GitHub 处理）
     *
     * 认不出来返回 null，由调用方提示用户。
     */
    fun parse(input: String): Parsed? {
        val raw = input.trim().removeSuffix("/")
        if (raw.isEmpty()) return null

        // 简写 owner/repo
        if (!raw.contains("://")) {
            val parts = raw.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return Parsed(Coord(GITHUB, "${parts[0]}/${parts[1]}", HEAD), parts.drop(2).joinToString("/"))
        }

        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val hostRaw = uri.host?.lowercase() ?: return null
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val host = when {
            hostRaw.contains("github") -> GITHUB
            hostRaw.contains("gitee") -> GITEE
            hostRaw.contains("gitlab") -> GITLAB
            // Gitea / Forgejo 及各类自建实例：域名本身就是平台标识，取文件时直接拼它
            else -> hostRaw
        }
        val repo = "${segments[0]}/${segments[1].removeSuffix(".git")}"

        // 分支与子路径：GitHub/Gitee 用 /tree/<ref>/...，GitLab 用 /-/tree/<ref>/...
        val sepIndex = segments.indexOfFirst { it == "tree" }
        if (sepIndex < 0 || sepIndex + 1 >= segments.size) {
            return Parsed(Coord(host, repo, HEAD), "")
        }
        val ref = segments[sepIndex + 1]
        val subPath = segments.drop(sepIndex + 2).joinToString("/")
        return Parsed(Coord(host, repo, ref), subPath)
    }

    /** 取单个文件的候选地址，按优先级排列（前一个失败换下一个）。 */
    fun fileUrls(coord: Coord, path: String): List<String> {
        val clean = path.trim('/')
        return when (coord.host) {
            GITEE -> listOf(
                "https://gitee.com/${coord.repo}/raw/${coord.ref}/$clean"
            )
            GITLAB -> listOf(
                "https://gitlab.com/${coord.repo}/-/raw/${coord.ref}/$clean"
            )
            GITHUB -> {
                val upstream = "https://raw.githubusercontent.com/${coord.repo}/${coord.ref}/$clean"
                (MIRRORS + RAW_ONLY_MIRRORS).map { it + upstream }
            }
            else -> {
                val host = coord.host
                listOf("https://$host/${coord.repo}/raw/branch/${coord.ref}/$clean")
            }
        }
    }

    /** 列整棵文件树（递归）的候选地址。 */
    fun treeUrls(coord: Coord): List<String> = when (coord.host) {
        GITEE -> listOf(
            "https://gitee.com/api/v5/repos/${coord.repo}/git/trees/${coord.ref}?recursive=1"
        )
        GITLAB -> {
            val project = java.net.URLEncoder.encode(coord.repo, "UTF-8")
            listOf(
                "https://gitlab.com/api/v4/projects/$project/repository/tree" +
                    "?recursive=true&per_page=100&ref=${coord.ref}"
            )
        }
        GITHUB -> {
            val upstream = "https://api.github.com/repos/${coord.repo}/git/trees/${coord.ref}?recursive=1"
            // 直连排第一：实测国内 raw 被墙但 api.github.com 是通的，直连不依赖任何第三方；
            // 额度 60/小时看着少，但一个源 12 小时才拉一次列表，绰绰有余。
            // 在 api.github.com 被墙的网络下由并发抢答兜掉，不会白等连接超时。
            listOf(upstream) + MIRRORS.map { it + upstream } + jsDelivrTree(coord)
        }
        else -> listOf(
            "https://${coord.host}/api/v1/repos/${coord.repo}/git/trees/${coord.ref}?recursive=true&per_page=1000"
        )
    }

    /**
     * 解析文件树响应，返回相对仓库根的路径列表（去掉前导 `/`）。
     * 两种响应形状：反代透传的 GitHub trees / Gitee / Gitea / GitLab 都是 `tree` 数组，
     * jsDelivr 的 flat 则是 `files` 数组；都靠 `type` 区分目录与文件（jsDelivr 只列文件、无 type）。
     */
    fun parseTree(host: String, body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()

        val items: List<JsonObject> = if (host == GITLAB) {
            objectsIn(root)
        } else {
            val obj = runCatching { root.jsonObject }.getOrNull() ?: return emptyList()
            objectsIn(obj["tree"]).ifEmpty { objectsIn(obj["files"]) }
        }

        return items.mapNotNull { item ->
            val type = item["type"]?.jsonPrimitive?.content
            if (type != null && type != "blob") return@mapNotNull null
            val name = item["path"]?.jsonPrimitive?.content
                ?: item["name"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            name.removePrefix("/").takeIf { it.isNotBlank() }
        }
    }

    private fun objectsIn(elem: JsonElement?): List<JsonObject> =
        (elem as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
