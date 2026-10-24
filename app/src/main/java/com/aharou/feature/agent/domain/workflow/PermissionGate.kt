package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.LineDiff
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.ApprovalScope
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionHunk
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.file.runFileOpWithTimeout
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一次工具调用的纯授权判定（不含等待用户决定）。 */
internal sealed interface PermissionVerdict {
    /** 放行（含 AUTO_APPROVE、内置白名单、planMode 退出等）。 */
    data object Allow : PermissionVerdict

    /** 策略/系统拒绝，携带原因与错误码。 */
    data class Deny(val reason: String, val code: String) : PermissionVerdict

    /** 需要用户确认；[request] 已带预览，[rememberablePatterns] 非空时「始终允许」可落库。 */
    data class Ask(
        val request: PendingToolPermission,
        val rememberablePatterns: List<String>,
        /** 策略提供的风险档与出站目标，供审计补全。 */
        val riskLevel: String? = null,
        val egressTarget: String? = null
    ) : PermissionVerdict
}

/**
 * 工具授权判定与批量挂起的收口。把原先「逐条弹窗」的判定逻辑抽成两半：
 *  - [evaluate] 是纯判定（策略引擎 + planMode 退出特例 + AUTO_APPROVE 放行），不挂起；
 *  - [awaitBatch] 对需确认的项一次性挂起一个批次，返回后对「始终允许且可记忆」的项落库。
 *
 * 批量路径先对整批 tool_call 逐条 [evaluate]，再对 ask 项统一等待，从而把「一个回合的多次工具调用」
 * 合并成「一次批量提案审批」。planMode 退出特例与 PLAN 模式写工具 DENY 仍逐条保留。
 *
 * 审批超时（[APPROVAL_TIMEOUT_MS]，默认 300 秒）视为拒绝：挂起超过时限仍无答复，整批按 REJECT
 * 结束并回灌可读原因，避免请求永久阻塞 Agent 循环；与通知栏弹窗的交互由 [ToolPermissionManager]
 * 在结算时统一收口，超时同样走它的「整批拒绝」路径。
 */
internal class PermissionGate(
    private val policyEngine: ToolPermissionPolicyEngine,
    private val permissionManager: ToolPermissionManager,
    private val fileAccess: FileAccessProvider,
    /**
     * 协助模式判定：为 true 时把所有 ASK 直接降为放行。
     *
     * 用于「跨设备协作」开启期间——协助者往往不在本机跟前，弹窗没人点，逐次确认等于把功能废掉。
     * 只降 ASK：DENY（越界、灾难性命令等硬规则）在上面已经返回，不受影响。
     */
    private val collaborationActive: suspend () -> Boolean = { false }
) {
    suspend fun evaluate(
        tool: AgentTool,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?,
        workspacePath: String,
        context: AgentContext
    ): PermissionVerdict {
        // planMode 退出 PLAN 时，后续会有计划审查面板兜底用户决策，此处权限弹窗冗余，直接放行；
        // 进入 PLAN 的方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") return PermissionVerdict.Allow
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode, workspacePath, sessionId)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            // 跨设备协作期间一律放行，连策略拒绝也不拦：协助者不在本机跟前，拒绝只会让远端干瞪眼。
            if (collaborationActive()) return PermissionVerdict.Allow
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionVerdict.Deny(reason, code)
        }

        if (tool.effectivePermissionPolicy(mode) == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionVerdict.Allow
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionVerdict.Allow
            ToolPermissionPolicyEngine.Verdict.DENY ->
                if (collaborationActive()) PermissionVerdict.Allow
                else PermissionVerdict.Deny("该工具被项目安全规则策略禁止执行", "SYSTEM_DENIED")
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                if (collaborationActive()) return PermissionVerdict.Allow
                val base = buildPreview(tool, callId, arguments, argsPreview, context)
                    ?: tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty()
                )
                PermissionVerdict.Ask(request, eval.rememberablePatterns, eval.riskLevel, eval.egressTarget)
            }
        }
    }

    /**
     * 一次挂起整个批次，等用户决定；返回「请求 id → 选择」。ALWAYS/SESSION/TIME_24H 且可记忆的项落库。
     * 超过 [APPROVAL_TIMEOUT_MS] 未答复按「拒绝」处理（含「超时未答复」原因写入审计并回灌），避免永久阻塞。
     *
     * 返回类型保持 [Map]（调用方按逐项选择判定整批是否取消），超时信息不外露、仅在方法内用于审计与拒绝原因。
     */
    suspend fun awaitBatch(
        batch: PendingPermissionBatch,
        asks: List<PermissionVerdict.Ask>,
        workspacePath: String
    ): Map<String, PermissionChoice> {
        val startedAt = System.currentTimeMillis()
        val decisionsNullable = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            permissionManager.awaitBatchApproval(batch)
        }
        val timedOut = decisionsNullable == null
        // 超时：整批按拒绝，并显式唤醒管理器以撤下挂起的弹窗/通知（幂等，UI 已决时为空操作）。
        val decisions = decisionsNullable ?: run {
            permissionManager.resolveBatch(batch.id, PermissionChoice.REJECT)
            batch.items.associate { it.id to PermissionChoice.REJECT }
        }
        val durationMs = System.currentTimeMillis() - startedAt
        asks.forEach { ask ->
            val choice = decisions[ask.request.id] ?: PermissionChoice.REJECT
            val scope = ApprovalScope.from(choice)
            if (scope != ApprovalScope.ONCE && ask.rememberablePatterns.isNotEmpty()) {
                policyEngine.remember(
                    ask.request.toolName,
                    ask.rememberablePatterns,
                    scope,
                    workspacePath,
                    ask.request.sessionId
                )
            }
            auditResolution(ask, choice, scope, timedOut, durationMs)
        }
        return decisions
    }

    /** 记录用户对一次 ASK 的最终裁决（批准与否、授权档位、耗时）。超时以「超时未答复」为原因。 */
    private fun auditResolution(
        ask: PermissionVerdict.Ask,
        choice: PermissionChoice,
        scope: ApprovalScope,
        timedOut: Boolean,
        durationMs: Long
    ) {
        val approved = choice != PermissionChoice.REJECT
        val decision = when {
            timedOut -> "ask"
            approved -> "allow"
            else -> "deny"
        }
        val reasons = when {
            timedOut -> listOf("审批超时未答复（${APPROVAL_TIMEOUT_MS / 1000} 秒），按拒绝处理")
            approved -> emptyList()
            else -> listOf("用户拒绝了本次调用")
        }
        policyEngine.auditResolution(
            sessionId = ask.request.sessionId,
            toolName = ask.request.toolName,
            decision = decision,
            riskLevel = ask.riskLevel,
            reasons = reasons,
            egressTarget = ask.egressTarget,
            approved = approved,
            approvalScope = scope.name.lowercase(),
            durationMs = durationMs,
            outputPreview = null
        )
    }

    /**
     * 优先取工具自带的真实差异预览（editFile 覆写 [AgentTool.buildPermissionPreview]）；
     * writeFile 的工具类文件不在本次改动可写范围内，这里就地为其「全文件替换」补一份预览，
     * 使批量面板对两类写文件工具都能展示差异。预览失败/超时一律回退 null。
     */
    private suspend fun buildPreview(
        tool: AgentTool,
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
        context: AgentContext
    ): PendingToolPermission? {
        tool.buildPermissionPreview(callId, args, context)?.let { return it }
        if (tool.name != "writeFile") return null
        return writeFilePreview(callId, args, argsPreview, context)
    }

    private suspend fun writeFilePreview(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
        context: AgentContext
    ): PendingToolPermission? {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = args["content"]?.jsonPrimitive?.contentOrNull ?: return null
        if (content.toByteArray(Charsets.UTF_8).size > PREVIEW_MAX_BYTES) return null
        if (content.split("\n").size > PREVIEW_MAX_LINES) return null
        val access = fileAccess.forWorkspace(context.projectRoot)
        val existed = runCatching { access.exists(path) }.getOrNull() ?: return null
        val oldContent = if (existed) {
            runCatching { runFileOpWithTimeout { access.readFile(path) } }.getOrNull() ?: return null
        } else {
            ""
        }
        val diff = if (existed) {
            LineDiff.toUnified(oldContent, content)
        } else {
            content.split("\n").joinToString("\n") { "+$it" }
        }
        val added = diff.lines().count { it.startsWith("+") }
        val removed = diff.lines().count { it.startsWith("-") }
        return PendingToolPermission(
            id = callId,
            toolName = "writeFile",
            title = "确认写入文件",
            summary = "AI 请求写入 ${access.toDisplayPath(path)}",
            details = "字符数：${content.length}\n行数：${content.lines().size}",
            argsPreview = argsPreview,
            previewHunks = listOf(ToolPermissionHunk(startLine = 1, added = added, removed = removed, diff = diff)),
            itemKind = "edit"
        )
    }

    private companion object {
        /** 预览差异的大小上限，避免为算差异而读入/比对超大文件（与 writeFile 执行路径同量级）。 */
        const val PREVIEW_MAX_BYTES = 4L * 1024 * 1024
        const val PREVIEW_MAX_LINES = 2000

        /**
         * 审批超时（毫秒）：一条挂起的授权请求超过此时限仍无答复，按「拒绝」处理并回灌原因。
         * 默认 300 秒（5 分钟）——比 nanoMuse 手机端默认 180 秒宽，给用户更充裕的决策时间；
         * 服务端默认 1 小时的无人值守场景不适用本 App（有前台用户交互）。改这里即可调整。
         */
        const val APPROVAL_TIMEOUT_MS = 300_000L
    }
}
