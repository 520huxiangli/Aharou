package com.aharou.feature.agent.domain.permission

/**
 * 出站（egress）策略：判定一次调用是否会「把数据发到外部」，并给出目标主机；配合白名单用于
 * 污点追踪——会话读过私密数据后，任何发往**非白名单主机或目标未知**的出站调用一律升为 ASK。
 *
 * 默认白名单覆盖常见的检索/代码托管/文档站：命中即视为「用户自己设定的出口」，读过私密数据也放行，
 * 与 nanoMuse `egress_allowlist` 同一思路（默认覆盖 search / Wikipedia / GitHub / PyPI 一类）。
 *
 * 与 nanoMuse 不同：本表默认值即「可配置」的起点，但项目当前不引入新的设置项（不新增依赖、不改
 * DB schema），故先以内置常量提供；后续如需用户自定义，可改由设置仓储注入。
 */
object EgressPolicy {

    /** 默认出站白名单（主机后缀匹配：`example.com` 同时覆盖 `www.example.com`）。 */
    val DEFAULT_ALLOWLIST: List<String> = listOf(
        "wikipedia.org",
        "github.com",
        "githubusercontent.com",
        "gitlab.com",
        "stackoverflow.com",
        "pypi.org",
        "python.org",
        "npmjs.com",
        "npmjs.org",
        "maven.org",
        "mvnrepository.com",
        "gradle.org",
        "kotlinlang.org",
        "jetbrains.com",
        "developer.android.com",
        "android.com",
        "google.com",
        "googlesource.com",
        "readthedocs.io",
        "arxiv.org",
        "openai.com",
        "duckduckgo.com",
        "bing.com",
        "baidu.com"
    )

    /**
     * 主机是否在白名单内：支持 `*` 通配、精确匹配与子域覆盖（`example.com` 覆盖 `www.example.com`）。
     * 语义对齐 nanoMuse 的 `host_allowed`。
     */
    fun hostAllowed(host: String?, allowlist: List<String> = DEFAULT_ALLOWLIST): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase().trimEnd('.')
        return allowlist.any { pattern ->
            val p = pattern.lowercase()
            p == "*" ||
                normalized == p ||
                (p.startsWith("*.") && normalized.endsWith(p.substring(1))) ||
                normalized.endsWith(".$p")
        }
    }

    /**
     * 该调用是否为「出站」。判定渠道：
     * - 明确的外发工具（webfetch/websearch/generateImage）——固定走网络；
     * - 带 [com.aharou.feature.agent.domain.tool.ToolCapability.NETWORK_WRITE] 或
     *   [com.aharou.feature.agent.domain.tool.ToolCapability.EXTERNAL_TOOL] 能力的工具；
     * - shell 类工具：命令中出现网络程序名，或命令里含 URL / 主机样式的目标。
     *
     * shell 是否联网按静态分析保守判断，不引入额外网络调用。
     */
    fun isEgress(
        toolName: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        capabilities: Set<com.aharou.feature.agent.domain.tool.ToolCapability>
    ): Boolean {
        if (toolName in EGRESS_TOOLS) return true
        if (capabilities.any {
                it == com.aharou.feature.agent.domain.tool.ToolCapability.NETWORK_WRITE ||
                    it == com.aharou.feature.agent.domain.tool.ToolCapability.EXTERNAL_TOOL
            }
        ) return true
        // 只有读能力的诸如下载类工具也可联网；shell 单独判。
        if (toolName in SHELL_TOOLS || toolName == TERMINAL_TOOL) {
            return shellLooksNetworked(args)
        }
        return false
    }

    /**
     * 出站目标主机：能解析出主机返回主机名，否则返回 null（=目标未知，污点下同样升 ASK）。
     * shell 命令的目标不可静态确定，返回 null。
     */
    fun egressTarget(args: Map<String, kotlinx.serialization.json.JsonElement>): String? {
        val url = (args["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (url != null) return GrantKey.hostOf(url)
        return null
    }

    private fun shellLooksNetworked(args: Map<String, kotlinx.serialization.json.JsonElement>): Boolean {
        val command = ((args["command"] ?: args["input"]) as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return true // 拿不到命令内容时保守视为可能联网
        if (URL_LIKE.containsMatchIn(command)) return true
        return NETWORK_PROGRAMS.any { prog ->
            Regex("(^|[\\s;&|()`<>])" + Regex.escape(prog) + "([\\s;&|()`<>]|$)").containsMatchIn(command)
        }
    }

    private const val TERMINAL_TOOL = "terminal"
    private val SHELL_TOOLS = setOf("Bash", "Shizuku")

    /** 固定走网络的外发工具（含读为主的抓取/检索，只要有把当前会话内容带出去的可能即纳入）。 */
    private val EGRESS_TOOLS = setOf("webfetch", "websearch", "generateImage")

    /** 会发起网络请求的常见程序名（shell 静态判定用）。 */
    private val NETWORK_PROGRAMS = listOf(
        "curl", "wget", "ssh", "scp", "sftp", "rsync", "nc", "netcat", "telnet",
        "git", "gh", "npm", "npx", "pnpm", "yarn", "pip", "pip3", "poetry", "uv",
        "docker", "kubectl", "helm", "aws", "gcloud", "az", "terraform",
        "python", "python3", "node", "deno", "bun", "ruby", "php", "go", "cargo", "rustup"
    )

    /** 命令里显式出现 URL / host:port 样式目标，视为有出站意图。 */
    private val URL_LIKE = Regex("https?://|\\b[a-z0-9.-]+\\.[a-z]{2,}(?::\\d+)?(/|\\b)", RegexOption.IGNORE_CASE)
}
