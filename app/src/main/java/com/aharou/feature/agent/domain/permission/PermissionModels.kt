package com.aharou.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * 工具授权规则的作用域。scope 不存在规则对象里，而是由「存在哪个文件」隐含：
 * 项目文件（`.aicode/permissions.json`）或全局文件。管理界面「提升为全局」= 从项目文件删、往全局文件加。
 */
enum class PermissionScope { PROJECT, GLOBAL }

/**
 * 规则的判定方向。弹窗的「始终允许」只会产生 [ALLOW]；[DENY] 仅由权限配置文件（deny 列表）提供，
 * 评估时 DENY 跨 scope 优先于 ALLOW（安全优先：全局禁可挡项目允许）。
 */
enum class PermissionDecision { ALLOW, DENY }

/**
 * 授权弹窗的用户选择。
 * - [REJECT]：拒绝本次调用，不记忆。
 * - [ONCE]：仅放行本次，不记忆。
 * - [SESSION]：放行并在**本次会话**内有效（内存态，重启即失，随会话隔离）。
 * - [TIME_24H]：放行并记忆为规则，24 小时后自动失效（规则带过期时间，加载时过滤）。
 * - [ALWAYS]：放行并记忆为规则，长期有效（弹窗路径固定写入当前项目 scope）。
 *
 * 记忆的粒度是「工具 + 目标」（见 [GrantKey]）：ALWAYS/SESSION/TIME_24H 都不会放成
 * 「这个工具随便用」，而是绑到本次调用覆盖到的目标（shell 程序名、网络主机等）。
 */
enum class PermissionChoice { REJECT, ONCE, SESSION, TIME_24H, ALWAYS }

/**
 * 一条工具授权规则。
 *
 * @param toolName 适用的工具名，如 `Bash`、`writeFile`。
 * @param pattern 匹配模式。对 shell 命令是「命令前缀」，按 token 前缀匹配：子命令分发器记
 *   `git pull`（仅命中 `git pull ...`，不命中 `git clone`），普通程序记 `cat`/`ls`（命中其所有调用）；
 *   对非 shell 工具是通配 `*`（整工具匹配）。
 * @param decision 判定方向，默认 [PermissionDecision.ALLOW]。
 * @param expiresAt 规则的过期时刻（epoch 毫秒）。null 表示长期有效；非 null 且已过时刻的规则在
 *   [PermissionRulesRepository.loadEffectiveFor] 加载时被过滤，用于 [PermissionChoice.TIME_24H] 的 24 小时时效。
 *   编码进紧凑格式时不携带过期信息的老规则照旧（向后兼容）。
 */
@Serializable
data class PermissionRule(
    val toolName: String,
    val pattern: String,
    val decision: PermissionDecision = PermissionDecision.ALLOW,
    val expiresAt: Long? = null
) {
    companion object {
        /** 非 shell 工具的整工具匹配模式。 */
        const val WHOLE_TOOL = "*"

        /** 过期时间后缀分隔符：`Bash(git)@1735689600000`；仅 [expiresAt] 非空时编码。 */
        private const val EXPIRY_DELIMITER = '@'

        /** 从紧凑格式字符串解析为 PermissionRule。
         *  格式示例：
         *  - `"Bash"` → toolName=Bash, pattern=*, ALLOW
         *  - `"Bash(git pull)"` → toolName=Bash, pattern="git pull", ALLOW
         *  - `"Bash(git)@1735689600000"` → 同上并带过期时刻
         */
        fun fromCompact(compact: String, decision: PermissionDecision = PermissionDecision.ALLOW): PermissionRule {
            val (body, expiresAt) = splitExpiry(compact)
            val parenIdx = body.indexOf('(')
            return if (parenIdx >= 0 && body.endsWith(')')) {
                val toolName = body.substring(0, parenIdx)
                val pattern = body.substring(parenIdx + 1, body.length - 1)
                PermissionRule(toolName, pattern, decision, expiresAt)
            } else {
                PermissionRule(body, WHOLE_TOOL, decision, expiresAt)
            }
        }

        /** 拆掉尾部的 `@<epochMillis>` 过期后缀；没有或非法则原样返回、过期为空。 */
        private fun splitExpiry(compact: String): Pair<String, Long?> {
            val idx = compact.lastIndexOf(EXPIRY_DELIMITER)
            if (idx < 0) return compact to null
            val millis = compact.substring(idx + 1).toLongOrNull() ?: return compact to null
            return compact.substring(0, idx) to millis
        }
    }

    /** 转为紧凑格式字符串。
     *  示例：
     *  - toolName=Bash, pattern=* → `"Bash"`
     *  - toolName=Bash, pattern="git pull" → `"Bash(git pull)"`
     *  - 带过期时刻时追加 `@<epochMillis>`
     */
    fun toCompact(): String {
        val body = if (pattern == WHOLE_TOOL) toolName else "$toolName($pattern)"
        return if (expiresAt != null) "$body$EXPIRY_DELIMITER$expiresAt" else body
    }

    /** 该规则在当前时刻是否已过期（[expiresAt] 为空表示长期有效，永不过期）。 */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && now >= expiresAt
}

// ── 权限文件顶层结构 ──────────────────────────────────────────────

/**
 * 权限规则 JSON 文件的顶层结构。
 *
 * 示例：
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 */
@Serializable
data class PermissionFile(
    val permissions: PermissionSection = PermissionSection()
) {
    @Serializable
    data class PermissionSection(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    companion object {
        val EMPTY = PermissionFile()
    }
}

// PermissionFile 和 List<PermissionRule> 转换

/** 从 [PermissionFile] 解析为规则列表。 */
fun PermissionFile.toRuleList(): List<PermissionRule> =
    permissions.allow.map { PermissionRule.fromCompact(it, PermissionDecision.ALLOW) } +
            permissions.deny.map { PermissionRule.fromCompact(it, PermissionDecision.DENY) }

/** 从规则列表构建 [PermissionFile]。 */
fun List<PermissionRule>.toPermissionFile(): PermissionFile {
    val (allow, deny) = partition { it.decision == PermissionDecision.ALLOW }
    return PermissionFile(
        permissions = PermissionFile.PermissionSection(
            allow = allow.map { it.toCompact() },
            deny = deny.map { it.toCompact() }
        )
    )
}
