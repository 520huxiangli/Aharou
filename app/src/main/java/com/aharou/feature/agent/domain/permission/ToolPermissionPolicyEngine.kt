package com.aharou.feature.agent.domain.permission

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 评估顺序（Bash）：
 *   1) 任一段命中 DENY 规则 → DENY（即便命令不可静态判定，DENY 也对已解析的段生效；可覆盖内置白名单）；
 *   2) 命令不可静态判定（含命令替换/分组/绝对路径重定向等）→ ASK，且不可记忆；
 *   3) 所有段都命中内置安全白名单（ls/git status 等只读命令，见 [BuiltInSafeCommands]）→ ALLOW；
 *   4) 所有段都命中已记忆的 ALLOW 规则 → ALLOW；
 *   5) 否则 → ASK，可记忆前缀为各段的「程序名」或「程序名+子命令」（对 git/npm 等子命令分发器，
 *      记 `git pull` 而非 `git`，使不同子命令各自独立授权）。
 * 非 shell 的 ASK 工具按整工具（pattern=`*`）匹配。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：无论内置白名单或已记忆规则，一律 ASK 且不可记忆；
 * AUTO 模式也不自动放行（除非已开启「禁用安全拦截」）。
 *
 * 审批增强（移植自 nanoMuse，见各分支注释）：
 *   - 敏感调用钩子 [AgentTool.isCallSensitive]：读取已记忆规则**之前**先判，为真直接 ASK 且不可记忆，
 *     堵住「点过一次始终允许就把屏幕敏感点按放成免确认」的绕过；
 *   - 污点追踪 + egress 白名单：会话读过私密数据后，任何发往非白名单主机的调用升 ASK 且翻不过；
 *   - 每次裁决落一条 [DecisionAuditLog]。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf(
            "screenshot", "get_text", "get_readable", "get_backbone", "get_page_info",
            "find_elements", "list_tabs", "get_cookies", "fetch", "wait_for_dom_stable"
        )
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 提权参数：非 AUTO 模式下，命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 提权时必须附带的理由参数名（与工具参数表一致）。 */
        const val ELEVATE_REASON_ARG = "reason"

        /** 弹窗标题里理由的截断长度：标题不是正文，放得下要点即可。 */
        const val ASK_TITLE_REASON_CHARS = 120

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "安全防护：禁止执行高危删除操作（根目录删除）"
        const val REASON_RM_RELATIVE = "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        const val REASON_RM_HOME = "安全防护：禁止执行高危删除操作（用户目录删除）"
        const val REASON_RM_WORKSPACE = "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        const val REASON_RM_TMP = "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        const val REASON_RM_SYSTEM_DIR = "安全防护：禁止执行高危删除操作（系统关键目录删除）"

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        const val REASON_UNANALYZABLE_DESTRUCTIVE =
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )

        /**
         * 读取私密数据的工具：读它们会把当前会话标记为 tainted（[TaintTracker]）。
         * 判定聚焦仓库里确实存在的「读外部私密数据」入口：记忆读取、读工作区外文件。屏幕/无障碍类
         * 敏感点按由 [AgentTool.isCallSensitive] 钩子单独负责（其迁移不在本次范围内）。
         */
        const val MEMORY_TOOL = "memory"
        private val MEMORY_READ_ACTIONS = setOf("read", "list", "search", "log", "fact", "mindstream", "core")
        const val READ_FILE_TOOL = "readFile"
        private val PRIVATE_READ_ACTIONS = mapOf(
            MEMORY_TOOL to MEMORY_READ_ACTIONS
        )

        /** 工作区外文件读取的容器前缀（读这些路径算私密数据）。 */
        val WORKSPACE_CONTAINER_PREFIX = "$HOME_DIR/workspace"

        const val REASON_TAINT_EGRESS_PREFIX =
            "本会话此前读过私密数据，本次调用可能把数据外发到非白名单主机（提示注入外泄防护），需要你确认"

        const val REASON_SENSITIVE_CALL =
            "该调用被工具标记为敏感操作，需逐次确认（不可记忆）"

        /** 24 小时档的时效。 */
        const val TIME_24H_MILLIS = 24L * 60 * 60 * 1000
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     * @param riskLevel 风险档（safe/moderate/sensitive），供审计与埋点；由能力位与调用参数推导。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        /** 非空表示本次 ASK 命中了敏感调用钩子（由 [AgentTool.isCallSensitive] 提供理由），不可记忆。 */
        val sensitiveReason: String? = null,
        /** 非空表示本次 ASK 源于污点追踪（读过私密数据后的出站），任何模式都翻不过（AUTO 除外，见实现）。 */
        val taintedReason: String? = null,
        /** 出站目标主机（用于审计与弹窗展示）；仅在出站调用时可能有值，未知为 null。 */
        val egressTarget: String? = null,
        /** 风险档，供审计。 */
        val riskLevel: String? = null
    )

    /**
     * @param workspacePath 发起这次工具调用的**会话工作区**（不是界面当前选中的工作区），
     *   规则读取与「始终允许」落库都以它为准，避免多会话或切工作区时读写到别的范围。
     */
    suspend fun evaluate(
        tool: AgentTool?,
        toolName: String,
        args: Map<String, JsonElement>,
        mode: com.aharou.feature.agent.domain.model.AgentMode,
        workspacePath: String,
        sessionId: String? = null
    ): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        val risk = riskLevelOf(toolName, capabilities)
        if (mode == com.aharou.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return audit(
                EvalResult(Verdict.DENY, emptyList(), denyReason = "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在计划模式下仅调用只读工具探索代码，不要尝试修改文件或执行命令。"),
                sessionId, toolName, risk
            )
        }

        // ── AUTO 模式：放行所有权限 ─────────────────────────────────
        // 用户已定：AUTO 维持全放行，新增的污点与敏感拦截均不生效（与 nanoMuse 不同，勿当 bug 修改）。
        // 灾难性 rm 防护（根目录/系统目录删除）默认保留，可在「工具授权」设置中关闭（禁用安全拦截）。
        if (mode == com.aharou.feature.agent.domain.model.AgentMode.AUTO) {
            val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
            if (!safetyDisabled && isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    checkCatastrophicRm(analysis.segments)?.let { return audit(elevationOrDeny(it, args), sessionId, toolName, risk) }
                    // 命令替换/子 shell/绝对路径重定向等无法静态判定的构造，删除目标不可知；
                    // 若同时疑似破坏性，宁可拦下（可提权），避免绕过安全防护。
                    if (!analysis.analyzable && looksDestructive(command)) {
                        return audit(elevationOrDeny(REASON_UNANALYZABLE_DESTRUCTIVE, args), sessionId, toolName, risk)
                    }
                }
            }
            // Shizuku 直接操作宿主系统，AUTO 下也不自动放行，仍需逐次确认（除非已开启「禁用安全拦截」）。
            if (toolName == SHIZUKU_TOOL && !safetyDisabled) {
                return audit(EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU), sessionId, toolName, risk)
            }
            return audit(EvalResult(Verdict.ALLOW, emptyList()), sessionId, toolName, risk)
        }

        // ── 以下为非 AUTO（BUILD/PLAN 的后续）分支 ──────────────────
        // 敏感调用钩子：读已记忆规则之前先判。为真直接 ASK 且不可记忆——堵住「点过一次始终允许，
        // 之后屏幕敏感点按被直接放行」的绕过（旧顺序是「先匹配已记忆规则、再看能力位」）。
        val sensitive = runCatching { tool?.isCallSensitive(args) == true }.getOrDefault(false)
        if (sensitive) {
            return audit(
                EvalResult(
                    Verdict.ASK,
                    emptyList(),
                    askTitle = "敏感操作确认",
                    rememberDisabledReason = REASON_SENSITIVE_CALL,
                    sensitiveReason = REASON_SENSITIVE_CALL
                ),
                sessionId, toolName, risk
            )
        }

        // 污点追踪：只读/自动放行的工具也可能读私密数据（如 readFile/memory），故先于一切放行分支判定。
        // 任何模式都翻不过（AUTO 已在上方提前返回，不在此列）。
        if (isPrivateRead(toolName, args)) {
            TaintTracker.taint(sessionId.orEmpty())
        }
        if (isTaintedEgress(sessionId, toolName, args, capabilities)) {
            val target = EgressPolicy.egressTarget(args)
            return audit(
                EvalResult(
                    Verdict.ASK,
                    emptyList(),
                    askTitle = "疑似数据外发确认",
                    rememberDisabledReason = REASON_TAINT_EGRESS_PREFIX + if (target != null) "（目标：$target）" else "（目标未知）",
                    taintedReason = REASON_TAINT_EGRESS_PREFIX,
                    egressTarget = target
                ),
                sessionId, toolName, risk
            )
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return audit(EvalResult(Verdict.ALLOW, emptyList()), sessionId, toolName, risk)
        }
        // task 只读动作（read/list）：不放行 DENY 规则，其余直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) {
                val rules = rulesRepo.loadEffectiveFor(workspacePath).filter { it.toolName == toolName }
                val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
                if (whole.any { it.decision == PermissionDecision.DENY }) {
                    return audit(EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行"), sessionId, toolName, risk)
                }
                return audit(EvalResult(Verdict.ALLOW, emptyList()), sessionId, toolName, risk)
            }
        }

        val rules = rulesRepo.loadEffectiveFor(workspacePath).filter { it.toolName == toolName }
        val result = if (isShellTool(toolName, args)) {
            evaluateShell(rules, args, forceAsk = toolName == SHIZUKU_TOOL, sessionId = sessionId.orEmpty(), toolName = toolName)
        } else {
            evaluateGeneric(rules, capabilities, targetsFor(toolName, args), sessionId.orEmpty(), toolName)
        }
        val egressTarget = if (EgressPolicy.isEgress(toolName, args, capabilities)) EgressPolicy.egressTarget(args) else null
        return audit(
            if (result.egressTarget != null || egressTarget == null) result else result.copy(egressTarget = egressTarget),
            sessionId, toolName, risk
        )
    }

    // ── 审计 ────────────────────────────────────────────────────

    /** 记录一次裁决并原样返回结果，避免每个 return 点手写审计。 */
    private fun audit(result: EvalResult, sessionId: String?, toolName: String, risk: String?): EvalResult {
        val decision = when (result.verdict) {
            Verdict.ALLOW -> "allow"
            Verdict.DENY -> "deny"
            Verdict.ASK -> "ask"
        }
        val reasons = buildList {
            result.denyReason?.let { add(it) }
            result.sensitiveReason?.let { add(it) }
            result.taintedReason?.let { add(it) }
        }
        DecisionAuditLog.recordToolCall(
            sessionId = sessionId,
            toolName = toolName,
            decision = decision,
            riskLevel = risk,
            reasons = reasons,
            tainted = TaintTracker.isTainted(sessionId.orEmpty()),
            egressTarget = result.egressTarget,
            approved = null,
            approvalScope = null,
            durationMs = null,
            outputPreview = null
        )
        return result
    }

    /** 记录一次「用户已决」的审计补全（批准与否、授权档位、耗时、输出预览），由审批层调用。 */
    fun auditResolution(
        sessionId: String?,
        toolName: String,
        decision: String,
        riskLevel: String?,
        reasons: List<String>,
        egressTarget: String?,
        approved: Boolean?,
        approvalScope: String?,
        durationMs: Long?,
        outputPreview: String?
    ) {
        DecisionAuditLog.recordToolCall(
            sessionId = sessionId,
            toolName = toolName,
            decision = decision,
            riskLevel = riskLevel,
            reasons = reasons,
            tainted = TaintTracker.isTainted(sessionId.orEmpty()),
            egressTarget = egressTarget,
            approved = approved,
            approvalScope = approvalScope,
            durationMs = durationMs,
            outputPreview = outputPreview
        )
    }

    /** PLAN 模式下视为危险的工具：写工作区 / 执行命令 / 网络写 / 改配置 / 外部工具，以及写型的终端与浏览器动作。 */
    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 由工具名与能力位推导风险档（审计用）：执行/写工作区类为 sensitive，网络读写为 moderate，其余 safe。 */
    private fun riskLevelOf(
        toolName: String,
        capabilities: Set<ToolCapability>
    ): String {
        return when {
            capabilities.any {
                it == ToolCapability.EXECUTE_COMMANDS || it == ToolCapability.WRITE_WORKSPACE ||
                    it == ToolCapability.MODIFY_AGENT_CONFIG || it == ToolCapability.MODIFY_CONTAINER_ENV ||
                    it == ToolCapability.EXTERNAL_TOOL
            } -> "sensitive"
            capabilities.any { it == ToolCapability.NETWORK_WRITE || it == ToolCapability.NETWORK_READ } -> "moderate"
            toolName in setOf("webfetch", "websearch", "generateImage") -> "moderate"
            else -> "safe"
        }
    }

    // ── 污点 / 出站 ─────────────────────────────────────────────

    /** 该调用是否读取私密数据（读后会把当前会话标记为 tainted）。 */
    private fun isPrivateRead(toolName: String, args: Map<String, JsonElement>): Boolean {
        PRIVATE_READ_ACTIONS[toolName]?.let { actions ->
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return false
            return action in actions
        }
        if (toolName == READ_FILE_TOOL) {
            return isOutsideWorkspace((args["path"] as? JsonPrimitive)?.content)
        }
        return false
    }

    /** 读工作区外的文件算私密数据；相对路径默认落在工作区内，不算。 */
    private fun isOutsideWorkspace(rawPath: String?): Boolean {
        val path = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!path.startsWith("/")) return false
        val normalized = normalizePath(path)
        return normalized != WORKSPACE_CONTAINER_PREFIX && !normalized.startsWith("$WORKSPACE_CONTAINER_PREFIX/")
    }

    /** 污点会话里的出站调用且目标是「非白名单主机或未知」→ 需确认。 */
    private fun isTaintedEgress(
        sessionId: String?,
        toolName: String,
        args: Map<String, JsonElement>,
        capabilities: Set<ToolCapability>
    ): Boolean {
        if (!TaintTracker.isTainted(sessionId.orEmpty())) return false
        if (!EgressPolicy.isEgress(toolName, args, capabilities)) return false
        val target = EgressPolicy.egressTarget(args)
        return !EgressPolicy.hostAllowed(target)
    }

    // ── 授权键（grant key）────────────────────────────────────────

    /** 本次调用的可绑定目标（shell 程序名 / 网络主机 / 外发文件）。空表示按整工具授权。 */
    private fun targetsFor(toolName: String, args: Map<String, JsonElement>): List<String> =
        GrantKey.targetsFor(toolName, args)

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 该命令是否疑似破坏性（仅在命令不可静态判定时使用）：含输出重定向、`-delete`
     * 或常见破坏性程序名。宁可多问，不误放。
     */
    private fun looksDestructive(command: String): Boolean =
        command.contains('>') || command.contains("-delete") || DESTRUCTIVE_PROGRAM.containsMatchIn(command)

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            // 提权理由一并带进弹窗标题：让用户看到的是「为什么非做不可」，而不是一串命令。
            val reason = (args[ELEVATE_REASON_ARG] as? JsonPrimitive)?.content?.trim().orEmpty()
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = if (reason.isEmpty()) "高危操作提权确认"
                else "高危操作提权确认：${reason.take(ASK_TITLE_REASON_CHARS)}",
                rememberDisabledReason = "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason。如确需执行，可在调用时加 `$ELEVATE_ARG: true` 重试，系统将向用户请求授权。"
            )
        }

    /**
     * 把用户选择记忆下来：按档位落地。
     * - [ApprovalScope.ALWAYS]：写长期 ALLOW 规则（无过期）；
     * - [ApprovalScope.TIME_24H]：写带过期时刻的 ALLOW 规则（24 小时后加载即被过滤）；
     * - [ApprovalScope.SESSION]：仅登记到内存 [SessionGrantStore]（按会话隔离，重启即失），不落盘；
     * - [ApprovalScope.ONCE]：不记忆。
     * [patterns] 为 [EvalResult.rememberablePatterns]，每条按「工具 + 目标」绑定；空则不记忆。
     */
    suspend fun remember(
        toolName: String,
        patterns: List<String>,
        scope: ApprovalScope,
        workspacePath: String,
        sessionId: String? = null,
        decision: PermissionDecision = PermissionDecision.ALLOW
    ) {
        if (scope == ApprovalScope.ONCE) return
        if (patterns.isEmpty()) return
        when (scope) {
            ApprovalScope.SESSION -> patterns.distinct().forEach { pattern ->
                SessionGrantStore.grant(sessionId.orEmpty(), GrantKey.of(toolName, pattern))
            }
            ApprovalScope.TIME_24H -> {
                val expiresAt = System.currentTimeMillis() + TIME_24H_MILLIS
                patterns.distinct().forEach { pattern ->
                    rulesRepo.add(PermissionScope.PROJECT, PermissionRule(toolName, pattern, decision, expiresAt), workspacePath)
                }
            }
            else -> patterns.distinct().forEach { pattern ->
                rulesRepo.add(PermissionScope.PROJECT, PermissionRule(toolName, pattern, decision), workspacePath)
            }
        }
    }

    /**
     * 兼容旧调用：给定 [PermissionScope] 落库位置（当前日期只区分项目/全局，均按长期档处理），
     * 等价于持续有效。新调用点请用带 [ApprovalScope] 的重载以表达会话/24 小时档。
     */
    suspend fun remember(
        toolName: String,
        patterns: List<String>,
        scope: PermissionScope,
        workspacePath: String
    ) {
        remember(toolName, patterns, ApprovalScope.ALWAYS, workspacePath, null, PermissionDecision.ALLOW)
    }

    // ── 通用（非 shell）评估 ──────────────────────────────────────

    private fun evaluateGeneric(
        rules: List<PermissionRule>,
        capabilities: Set<ToolCapability>,
        targets: List<String>,
        sessionId: String,
        toolName: String
    ): EvalResult {
        val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
        if (whole.any { it.decision == PermissionDecision.DENY }) return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
        if (whole.any { it.decision == PermissionDecision.ALLOW }) return EvalResult(Verdict.ALLOW, emptyList())

        // 工具 + 目标绑定：会话档内存授权命中，或规则里存在「按目标（host）前缀/相等」的 ALLOW。
        if (targets.isNotEmpty()) {
            val allTargets = targets.all { target ->
                SessionGrantStore.covers(sessionId, GrantKey.of(toolName, target)) ||
                    rules.any {
                        it.decision == PermissionDecision.ALLOW &&
                            (it.pattern == PermissionRule.WHOLE_TOOL || targetMatches(it.pattern, target))
                    }
            }
            if (allTargets) return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        // 有可绑定目标时，「始终允许」记忆目标（host 等），否则退化为整工具。
        return EvalResult(Verdict.ASK, if (targets.isNotEmpty()) targets else listOf(PermissionRule.WHOLE_TOOL))
    }

    /** 规则的 host 模式是否能覆盖目标（`example.com` 覆盖 `www.example.com`；`*` 覆盖一切）。 */
    private fun targetMatches(pattern: String, target: String): Boolean {
        if (pattern == "*") return true
        val p = pattern.lowercase()
        val t = target.lowercase()
        return t == p || t.endsWith(".$p") || (p.startsWith("*.") && t.endsWith(p.substring(1)))
    }

    // ── shell 评估 ──────────────────────────────────────────────

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun evaluateShell(
        rules: List<PermissionRule>,
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        sessionId: String = "",
        toolName: String = ""
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)
        val allow = rules.filter { it.decision == PermissionDecision.ALLOW }
        val deny = rules.filter { it.decision == PermissionDecision.DENY }

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权。
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return elevationOrDeny(catastrophicReason, args)
        }

        // 1) DENY 优先（含对内置安全白名单的覆盖）：任一段命中 DENY 即拒。
        if (analysis.segments.any { seg -> deny.any { ShellCommandParser.matches(it.pattern, seg) } }) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "该命令被项目权限规则策略禁止执行")
        }

        // 2) Shizuku 高危工具：无论内置白名单或已记忆规则，一律弹窗且不可记忆。
        if (forceAsk) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 3) 不可静态判定 → 必须弹窗、不可记忆（内置白名单也不适用）。
        if (!analysis.analyzable) return EvalResult(Verdict.ASK, emptyList())

        // 4) 内置安全白名单：每段都命中安全前缀（ls/git status 等）→ 自动放行，不弹窗。
        if (analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 4.5) 会话档授权（grant key）：每段的程序都被会话内「本次会话」授权覆盖 → 放行。
        if (analysis.segments.isNotEmpty()) {
            val allSessionGranted = analysis.segments.all { seg ->
                val program = ShellCommandParser.rememberablePrefix(seg) ?: return@all false
                SessionGrantStore.isGranted(sessionId, GrantKey.of(toolName, program))
            }
            if (allSessionGranted) return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 5) 已记忆的 ALLOW：每段都命中 → 放行。
        // 对 rm 命令进行精细化校验：避免存量或宽泛的 "rm"/"rm -rf" 无目标规则放行高风险删除
        val allAllowed = analysis.segments.isNotEmpty() &&
            analysis.segments.all { seg ->
                val rmInfo = ShellCommandParser.parseRmInfo(seg)
                allow.any { rule ->
                    if (!ShellCommandParser.matches(rule.pattern, seg)) {
                        false
                    } else if (rmInfo.isRm) {
                        val ruleRmInfo = ShellCommandParser.parseRmInfo(rule.pattern.split(Regex("\\s+")))
                        ruleRmInfo.targetPaths.isNotEmpty() && (!rmInfo.isRecursive || ruleRmInfo.isRecursive)
                    } else {
                        true
                    }
                }
            }
        if (allAllowed) return EvalResult(Verdict.ALLOW, emptyList())

        // 6) 否则弹窗，可记忆前缀（子命令分发器记 程序名+子命令）。
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        if (hasRmUnrememberable) {
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
            )
        }

        val rememberable = analysis.segments.mapNotNull { ShellCommandParser.rememberablePrefix(it) }.distinct()
        return EvalResult(Verdict.ASK, rememberable)
    }
}
