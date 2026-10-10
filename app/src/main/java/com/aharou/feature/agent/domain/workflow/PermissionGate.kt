package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.LineDiff
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.PermissionScope
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionHunk
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.file.runFileOpWithTimeout
import com.aharou.feature.workspace.domain.FileAccessProvider
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
        val rememberablePatterns: List<String>
    ) : PermissionVerdict
}

/**
 * 工具授权判定与批量挂起的收口。把原先「逐条弹窗」的判定逻辑抽成两半：
 *  - [evaluate] 是纯判定（策略引擎 + planMode 退出特例 + AUTO_APPROVE 放行），不挂起；
 *  - [awaitBatch] 对需确认的项一次性挂起一个批次，返回后对「始终允许且可记忆」的项落库。
 *
 * 批量路径先对整批 tool_call 逐条 [evaluate]，再对 ask 项统一等待，从而把「一个回合的多次工具调用」
 * 合并成「一次批量提案审批」。planMode 退出特例与 PLAN 模式写工具 DENY 仍逐条保留。
 */
internal class PermissionGate(
    private val policyEngine: ToolPermissionPolicyEngine,
    private val permissionManager: ToolPermissionManager,
    private val fileAccess: FileAccessProvider
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

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode, workspacePath)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionVerdict.Deny(reason, code)
        }

        if (tool.effectivePermissionPolicy(mode) == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionVerdict.Allow
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionVerdict.Allow
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionVerdict.Deny("该工具被项目安全规则策略禁止执行", "SYSTEM_DENIED")
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val base = buildPreview(tool, callId, arguments, argsPreview, context)
                    ?: tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty()
                )
                PermissionVerdict.Ask(request, eval.rememberablePatterns)
            }
        }
    }

    /** 一次挂起整个批次，等用户决定；返回「请求 id → 选择」。ALWAYS 且可记忆的项落库。 */
    suspend fun awaitBatch(
        batch: PendingPermissionBatch,
        asks: List<PermissionVerdict.Ask>,
        workspacePath: String
    ): Map<String, PermissionChoice> {
        val decisions = permissionManager.awaitBatchApproval(batch)
        asks.forEach { ask ->
            if (decisions[ask.request.id] == PermissionChoice.ALWAYS && ask.rememberablePatterns.isNotEmpty()) {
                policyEngine.remember(ask.request.toolName, ask.rememberablePatterns, PermissionScope.PROJECT, workspacePath)
            }
        }
        return decisions
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
    }
}
