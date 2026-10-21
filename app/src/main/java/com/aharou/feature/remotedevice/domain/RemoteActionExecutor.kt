package com.aharou.feature.remotedevice.domain

import android.content.Context
import android.os.Build
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.PermissionGate
import com.aharou.feature.agent.domain.workflow.PermissionVerdict
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.notification.AgentPermissionNotifier
import com.aharou.feature.remotedevice.RemoteMessageNotifier
import com.aharou.feature.remotedevice.server.RemoteFileTicketStore
import com.aharou.feature.remotedevice.server.RemoteMcpServer
import com.aharou.feature.remotedevice.server.TransferDirection
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 一次远端动作的执行结果，会被翻译成 MCP 的 content 块（文本 + 图片）。 */
data class RemoteActionResult(
    val text: String,
    val images: List<AgentImage> = emptyList(),
    val isError: Boolean = false
)

/**
 * 远端动作在本机的执行器：**所有**远端调用都在这里落到本机既有门禁上，没有第二条执行路径。
 *
 * 链路：
 *  1. 动作 → 本机工具（[RemoteActionCatalog.resolveTool]，`info` 用 [RemoteInfoTool]）；
 *  2. [PermissionGate.evaluate] 走本机策略引擎（PLAN/AUTO 语义、DENY 规则、污点与外发判断、审计）；
 *  3. 需要审批时挂到 [ToolPermissionManager]，并由 [AgentPermissionNotifier] 复用通知栏「允许/拒绝」，
 *     用户点选回到同一个 `resolveBatch` 入口，与本机 AI 的授权弹窗完全同一套状态；
 *  4. 执行 [AgentTool.executeWithContext] 并回传结果。
 *
 * 两个刻意取舍：
 *  - **审批有 90 秒上限**：调用方（另一台 Aharou）的 MCP client 有 120 秒 callTimeout，若审批拖过它，
 *    对端已经放弃、本机却还会照批执行——那样会留下「没人收结果的副作用」。超时即按拒绝处理，
 *    请求从挂起表移除，绝不执行。
 *  - **远端调用不写「始终允许」规则**：升级审批时把可记忆模式置空，避免「为了远端放行」在本机
 *    落下一份长期规则、日后连本机自己的调用也被它放行。策略引擎主动判定为 ASK 的动作（写文件、
 *    非白名单命令、截图）仍保留引擎自己的记忆语义，与本地一致。
 */
@Singleton
class RemoteActionExecutor @Inject constructor(
    private val catalog: RemoteActionCatalog,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val permissionManager: ToolPermissionManager,
    private val fileAccess: FileAccessProvider,
    private val workspaceRepository: WorkspaceRepository,
    private val notifier: AgentPermissionNotifier,
    private val settings: RemoteDeviceSettings,
    private val tickets: RemoteFileTicketStore,
    private val pathMapper: WorkspacePathMapper,
    private val messages: MessagePersistenceUseCase,
    private val sessions: SessionUseCase,
    private val messageNotifier: RemoteMessageNotifier,
    private val agentTurnRunner: AgentTurnRunner,
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "RemoteActionExecutor"

        /**
         * 审批用的会话标识前缀（拼接调用方设备 id）：必须非空才能进
         * [ToolPermissionManager.awaitingSessionIds]，通知栏据此展示。
         * 按设备分开而不是共用一个常量：共用时多个设备同时等审批会挤同一个通知槽，
         * 后一个把前一个的 id 覆盖掉，前一条通知就再也撤不下来（残留）。
         */
        const val APPROVAL_SESSION_PREFIX = "remote-device:"

        /** 审批等待上限（毫秒），必须小于调用方 MCP client 的 120 秒 callTimeout。 */
        const val APPROVAL_TIMEOUT_MS = 90_000L

        const val PREVIEW_LIMIT = 500
        const val MAX_DEVICE_LOCKS = 64
    }

    /** 每台设备一把短动作锁：串行执行，避免多张审批同时挂起把用户淹没。 */
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 整轮问 AI 的长任务用独立锁：与短动作共用一把时，一次 ask_ai 会把它后面的
     * send_message 全堵到超时——投递只需要落库+通知，本不该等任何 AI 回合。
     */
    private val deviceLongLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 把对端给的路径解析成本机绝对路径。
     *
     * 传文件绕不开「从哪儿读、往哪儿写」，这里复用与本机文件工具同一套解析与越界校验
     * （[WorkspacePathMapper.toHostFile]），越界会直接抛异常、由上层按失败返回。
     */
    private suspend fun resolveTransferPath(path: String): String =
        pathMapper.toHostFile(path, workspaceRepository.currentPath()).absolutePath

    suspend fun execute(
        action: RemoteAction,
        rawArgs: Map<String, JsonElement>,
        caller: RemotePairedDevice
    ): RemoteActionResult {
        val isLongTask = action.name == RemoteActionCatalog.ACTION_ASK_AI
        val lock = lockFor(if (isLongTask) deviceLongLocks else deviceLocks, caller)
        val queuedAt = System.currentTimeMillis()
        return lock.withLock {
            FileLogger.i(
                TAG,
                "远端动作开始: ${action.name}（${caller.deviceName}），等锁 ${System.currentTimeMillis() - queuedAt}ms"
            )
            try {
                runAction(action, rawArgs, caller)
            } finally {
                FileLogger.i(
                    TAG,
                    "远端动作结束: ${action.name}（${caller.deviceName}），总耗时 ${System.currentTimeMillis() - queuedAt}ms"
                )
            }
        }
    }

    private fun deviceKey(caller: RemotePairedDevice): String =
        caller.deviceId.ifBlank { caller.tokenHint }

    private fun lockFor(locks: ConcurrentHashMap<String, Mutex>, caller: RemotePairedDevice): Mutex {
        if (locks.size > MAX_DEVICE_LOCKS) locks.clear()
        return locks.getOrPut(deviceKey(caller)) { Mutex() }
    }

    private suspend fun runAction(
        action: RemoteAction,
        rawArgs: Map<String, JsonElement>,
        caller: RemotePairedDevice
    ): RemoteActionResult {
        // 取值白名单先于一切：schema 里没列不等于对方传不进来，越权值必须在这里挡住
        action.firstViolatedArg(rawArgs)?.let { (key, allowed) ->
            return failure(action, "$key 只支持：${allowed.joinToString(" / ")}")
        }
        val args = buildArgs(action, rawArgs)
        val tool = if (action.toolName == null) {
            when (action.name) {
                RemoteActionCatalog.ACTION_FETCH_FILE -> RemoteFetchFileTool { path ->
                    "${RemoteMcpServer.FILE_PATH}/${tickets.issue(resolveTransferPath(path), TransferDirection.DOWNLOAD).id}"
                }
                RemoteActionCatalog.ACTION_PUSH_FILE -> RemotePushFileTool { path ->
                    "${RemoteMcpServer.UPLOAD_PATH}/${tickets.issue(resolveTransferPath(path), TransferDirection.UPLOAD).id}"
                }
                RemoteActionCatalog.ACTION_SEND_MESSAGE -> RemoteSendMessageTool { text, workspace, sessionId ->
                    deliverMessage(text, workspace, sessionId, caller)
                }
                RemoteActionCatalog.ACTION_READ_LOGS -> RemoteReadLogsTool()
                RemoteActionCatalog.ACTION_ASK_AI -> RemoteAskAiTool { text, timeoutMs, workspace, sessionId ->
                    askAi(text, timeoutMs, workspace, sessionId, caller)
                }
                else -> RemoteInfoTool { deviceInfo(caller) }
            }
        } else {
            catalog.resolveTool(action)
                ?: return failure(action, "本机未提供能力：${action.name}")
        }

        val callId = UUID.randomUUID().toString()
        val approvalSessionId = APPROVAL_SESSION_PREFIX + deviceKey(caller).take(12)
        val workspacePath = workspaceRepository.currentPath()
        val argsPreview = JsonObject(args).toString().take(PREVIEW_LIMIT)
        // 协作开启即免逐次审批：协助者往往不在这台手机上，弹窗等于把功能用废。
        // 与本机 agent 同一套语义——PermissionGate 只降 ASK，DENY（越界、灾难性命令）照旧直接拒。
        val collaborationActive = settings.current().enabled
        val gate = PermissionGate(policyEngine, permissionManager, fileAccess) { collaborationActive }
        val verdict = gate.evaluate(
            tool = tool,
            callId = callId,
            arguments = args,
            argsPreview = argsPreview,
            mode = AgentMode.BUILD,
            sessionId = approvalSessionId,
            workspacePath = workspacePath,
            context = agentContext(workspacePath, approvalSessionId)
        )

        val approved = when (verdict) {
            is PermissionVerdict.Deny -> {
                FileLogger.i(TAG, "远端动作被策略拒绝: ${action.name} — ${verdict.reason}")
                return failure(action, verdict.reason)
            }
            is PermissionVerdict.Ask -> awaitDecision(
                action = action,
                gate = gate,
                tool = tool,
                callId = callId,
                caller = caller,
                ask = verdict,
                workspacePath = workspacePath,
                approvalSessionId = approvalSessionId
            )
            PermissionVerdict.Allow -> {
                val escalate = action.escalateWhenAllowed && settings.current().alwaysAskRemote
                if (!escalate) {
                    true
                } else {
                    awaitDecision(
                        action = action,
                        gate = gate,
                        tool = tool,
                        callId = callId,
                        caller = caller,
                        ask = forcedAsk(tool, callId, args, argsPreview, caller, approvalSessionId),
                        workspacePath = workspacePath,
                        approvalSessionId = approvalSessionId
                    )
                }
            }
        }

        if (!approved) {
            FileLogger.i(TAG, "远端动作未获批准: ${action.name}（来自 ${caller.deviceName}）")
            return failure(action, "本机用户未批准该动作")
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                tool.executeWithContext(args, agentContext(workspacePath, approvalSessionId))
            }
            FileLogger.i(TAG, "远端动作执行完成: ${action.name}（来自 ${caller.deviceName}）")
            toActionResult(result, action)
        } catch (e: Exception) {
            FileLogger.e(TAG, "远端动作执行失败: ${action.name}", e)
            failure(action, "执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 审批 ──────────────────────────────────────────────────────

    /**
     * 一次目标解析的结果：要么给出工作区 + 会话，要么给出错误码与原因。
     */
    private class TargetResolution(
        val workspace: Workspace? = null,
        val session: ChatSessionEntity? = null,
        val error: String? = null,
        val errorCode: String? = null
    )

    /**
     * 解析 `workspace` / `session_id` 两个投递目标参数。
     *
     * - 工作区按目录名精确匹配；只写不带哈希的部分（如 `小染`）时按前缀匹配，命中多个就报错要求写全名；
     * - 只给 session_id 时以该会话所属工作区为准，避免「会话在 A 工作区、却按当前工作区找会话」；
     * - 两者都不给 = 本机当前工作区最近活跃会话（没有则新建），与旧行为一致；
     * - 找不到目标一律报错，**不静默回退到当前会话**——静默兜底正是「投错地方」的根源。
     */
    private suspend fun resolveTarget(workspaceHint: String?, sessionHint: String?): TargetResolution {
        val all = workspaceRepository.workspaces.value
        val known = sessionHint?.let { sessions.getSessionById(it) }
        if (sessionHint != null && known == null) {
            return TargetResolution(error = "找不到会话「$sessionHint」", errorCode = "SESSION_NOT_FOUND")
        }
        val workspace = when {
            workspaceHint == null && known != null ->
                all.firstOrNull { it.path == known.workspacePath } ?: workspaceRepository.current.value
            workspaceHint == null -> workspaceRepository.current.value
            else -> {
                val exact = all.firstOrNull { it.name == workspaceHint || it.path == workspaceHint }
                val matches = if (exact != null) listOf(exact) else all.filter { it.name.startsWith("$workspaceHint-") }
                when {
                    matches.size == 1 -> matches.first()
                    matches.isEmpty() -> return TargetResolution(
                        error = "找不到工作区「$workspaceHint」；现有：${all.joinToString(" / ") { it.name }}",
                        errorCode = "WORKSPACE_NOT_FOUND"
                    )
                    else -> return TargetResolution(
                        error = "工作区「$workspaceHint」匹配到多个：${matches.joinToString(" / ") { it.name }}，请写全名",
                        errorCode = "WORKSPACE_AMBIGUOUS"
                    )
                }
            }
        } ?: return TargetResolution(
            error = "本机没有选中的工作区；请先在本机打开一个工作区，或用 workspace 指定",
            errorCode = "WORKSPACE_NOT_FOUND"
        )
        if (!workspace.available) {
            return TargetResolution(
                error = "工作区「${workspace.name}」当前不可用（目录被移动或删除）",
                errorCode = "WORKSPACE_UNAVAILABLE"
            )
        }
        if (known != null && known.workspacePath.isNotBlank() && known.workspacePath != workspace.path) {
            val owner = all.firstOrNull { it.path == known.workspacePath }?.name ?: known.workspacePath
            return TargetResolution(
                error = "会话「${known.id}」属于工作区「$owner」，与指定的「${workspace.name}」不一致",
                errorCode = "SESSION_WORKSPACE_MISMATCH"
            )
        }
        val session = known ?: sessions.getMostRecentSessionOfWorkspace(workspace.path)
            ?: sessions.newSessionEntity(workspace.path).also { sessions.upsertSession(it) }
        return TargetResolution(workspace = workspace, session = session)
    }

    /**
     * 把对端的传话投到指定工作区/会话，并回传实际投递到哪。
     *
     * 消息以 assistant 身份落库并标 `isContextExcluded`——它只是「收到一条」，不该混进本机 AI
     * 的上下文回放，免得模型把自己没说过的话当成自己说过的。
     */
    private suspend fun deliverMessage(
        text: String,
        workspaceHint: String?,
        sessionHint: String?,
        caller: RemotePairedDevice
    ): ToolResult {
        val target = resolveTarget(workspaceHint, sessionHint)
        target.error?.let { return ToolResult.Error(it, target.errorCode ?: "TARGET_INVALID") }
        val workspace = target.workspace ?: return ToolResult.Error("没有可投递的工作区", "WORKSPACE_NOT_FOUND")
        val session = target.session ?: return ToolResult.Error("没有可投递的会话", "SESSION_NOT_FOUND")
        val from = caller.deviceName.ifBlank { caller.deviceId }
        messages.persist(
            sessionId = session.id,
            role = MessageRole.ASSISTANT,
            content = "【来自「$from」的传话】\n$text",
            isContextExcluded = true
        )
        sessions.touch(session.id, System.currentTimeMillis())
        messageNotifier.notify(from, text)
        return ToolResult.Success(buildJsonObject {
            put("delivered", "ok")
            put("workspace", workspace.name)
            put("session_id", session.id)
            put("session_title", session.title)
        })
    }

    /**
     * 把对端的提问交给本机 AI 跑一轮，并把回复带回去。
     *
     * 与 [deliverMessage] 的分工：那条以 assistant 身份落库、标 `isContextExcluded`，只给人看、AI 不可见；
     * 这条以普通用户消息进入会话、触发一轮 agent，回复原样返回调用方。
     *
     * 前缀必须写明来源并点明「不是本机用户说的」：远端内容一旦进了上下文就可能左右模型判断，
     * 至少让它知道自己是在替第三方转述，涉及改动文件、执行命令、读敏感内容时该先问过本机用户。
     */
    private suspend fun askAi(
        text: String,
        timeoutMs: Long,
        workspaceHint: String?,
        sessionHint: String?,
        caller: RemotePairedDevice
    ): ToolResult {
        if (!settings.current().allowRemoteAskAi) {
            return ToolResult.Error(
                "本机没有开放「远端向 AI 提问」——跨设备协作里那个开关默认是关的。" +
                    "可以改用 send_message 把话投给本机用户，或请他到设置里打开这项授权。",
                "REMOTE_ASK_AI_DISABLED"
            )
        }
        val target = resolveTarget(workspaceHint, sessionHint)
        target.error?.let { return ToolResult.Error(it, target.errorCode ?: "TARGET_INVALID") }
        val workspace = target.workspace ?: return ToolResult.Error("没有可投递的工作区", "WORKSPACE_NOT_FOUND")
        val session = target.session ?: return ToolResult.Error("没有可投递的会话", "SESSION_NOT_FOUND")
        // 以本机用户的口吻投递：这是开关明确授权的语义——本机用户表态「这些设备可以当我自己来问」。
        // 因此不再加「来自外部设备」的标注（那会让模型一律当不可信输入挡回）。代价写进了设置项描述：
        // 已配对设备能借本机 AI 的手办事；不过工具调用仍走本机权限引擎，该审批的照旧。
        FileLogger.i(TAG, "远端 AI 提问: ${caller.deviceName.ifBlank { caller.deviceId }}")
        val prompt = text
        var reply = ""
        val finished = withTimeoutOrNull(timeoutMs) {
            agentTurnRunner.run(
                AgentTurnRequest(sessionId = session.id, text = prompt, skipTitleUpdate = true)
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantText -> reply = event.content
                    is AgentEvent.AssistantDelta -> if (event.accumulated.isNotBlank()) reply = event.accumulated
                    is AgentEvent.Failed -> reply = "（本机 AI 出错：${event.error.take(300)}）"
                    else -> Unit
                }
            }
            true
        }
        sessions.touch(session.id, System.currentTimeMillis())
        val body = when {
            finished == null -> "已投递给本机 AI，但 ${timeoutMs / 1000} 秒内没跑完；回复请到本机会话里看。"
            reply.isBlank() -> "本机 AI 这一轮没有产出文本回复。"
            else -> reply
        }
        return ToolResult.Success(buildJsonObject {
            put("reply", body)
            put("workspace", workspace.name)
            put("session_id", session.id)
            put("session_title", session.title)
        })
    }

    /**
     * 挂起一次审批并等待用户决定。同时驱动通知栏：
     * 请求进入挂起表后调 [AgentPermissionNotifier.sync] 展示通知，结束时无论结果如何再同步一次撤下。
     * 无人应答（前台 ViewModel 不在、用户离开）时也至少要有一条通知可点，故不依赖 UI 侧的收集器。
     */
    private suspend fun awaitDecision(
        action: RemoteAction,
        gate: PermissionGate,
        tool: AgentTool,
        callId: String,
        caller: RemotePairedDevice,
        ask: PermissionVerdict.Ask,
        workspacePath: String,
        approvalSessionId: String
    ): Boolean {
        val batch = PendingPermissionBatch(
            id = UUID.randomUUID().toString(),
            sessionId = approvalSessionId,
            items = listOf(ask.request)
        )
        val startedAt = System.currentTimeMillis()
        val decisions = coroutineScope {
            val notifyJob = launch {
                runCatching {
                    permissionManager.awaitingSessionIds.first { approvalSessionId in it }
                    notifier.sync(permissionManager.awaitingSessionIds.value)
                }
            }
            try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                    gate.awaitBatch(batch, listOf(ask), workspacePath)
                }
            } finally {
                notifyJob.cancel()
            }
        }
        runCatching { notifier.sync(permissionManager.awaitingSessionIds.value) }

        if (decisions == null) {
            // 门禁内部的超时分支不会跑到（它比这里长），故超时审计在调用方补一条。
            policyEngine.auditResolution(
                sessionId = approvalSessionId,
                toolName = tool.name,
                decision = "ask",
                riskLevel = null,
                reasons = listOf(context.getString(R.string.remote_device_perm_timeout)),
                egressTarget = null,
                approved = false,
                approvalScope = null,
                durationMs = System.currentTimeMillis() - startedAt,
                outputPreview = null
            )
            FileLogger.w(TAG, "远端动作审批超时: ${action.name}（来自 ${caller.deviceName}）")
            return false
        }
        val choice = decisions[ask.request.id] ?: PermissionChoice.REJECT
        return choice != PermissionChoice.REJECT
    }

    /**
     * 引擎判定「自动放行」但远端来源仍要问一次时，构造这次审批请求。
     * 可记忆模式置空：远端需求不该在本机落成长期规则（见类注释）。
     */
    private fun forcedAsk(
        tool: AgentTool,
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
        caller: RemotePairedDevice,
        approvalSessionId: String
    ): PermissionVerdict.Ask {
        val base = tool.buildPermissionRequest(callId, args, argsPreview)
        val request = base.copy(
            title = context.getString(R.string.remote_device_perm_title),
            sessionId = approvalSessionId,
            summary = "[${caller.deviceName.ifBlank { caller.deviceId }}] ${base.summary}",
            rememberablePatterns = emptyList(),
            rememberDisabledReason = "远端发起的调用不写入「始终允许」规则，避免把远端需求变成本机自身的长期放行"
        )
        return PermissionVerdict.Ask(request, rememberablePatterns = emptyList())
    }

    // ── 参数与上下文 ──────────────────────────────────────────────

    /** 只透传白名单参数并强制注入固定参数；`elevate`、`background` 这类本机专属开关在这里被彻底挡掉。 */
    private fun buildArgs(action: RemoteAction, raw: Map<String, JsonElement>): Map<String, JsonElement> {
        val args = LinkedHashMap<String, JsonElement>()
        action.allowedArgs.forEach { key -> raw[key]?.let { args[key] = it } }
        action.forcedArgs.forEach { (key, value) -> args[key] = JsonPrimitive(value) }
        if (action.name == RemoteActionCatalog.ACTION_SHELL) {
            val requested = args["timeout"]?.jsonPrimitive?.longOrNull
            if (requested != null && requested > RemoteActionCatalog.REMOTE_SHELL_MAX_TIMEOUT_SECONDS) {
                args["timeout"] = JsonPrimitive(RemoteActionCatalog.REMOTE_SHELL_MAX_TIMEOUT_SECONDS)
            }
        }
        return args
    }

    /**
     * 远端执行的上下文：BUILD 模式（与用户手动发消息同级，不走 AUTO 全放行），
     * 写路径租约不设（`null` = 与本机 agent 同等权限）。
     *
     * 为何不用 `writePaths` 做工作区封闭：租约是对**字符串路径**做前缀匹配，而 AI/对端用的是容器别名
     * （`~/workspace/…`，见 [com.aharou.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT]），
     * 与工作区的宿主路径不同源；拿宿主路径当租约会把所有远端写入卡死。真正的越界防护在文件工具内部：
     * `WorkspacePathMapper.toHostFile` 落盘前会用真实路径校验是否落在「工作区 / rootfs / AI 配置 / 记忆 / 共享区」之内，
     * 越界直接抛「路径越界，拒绝访问」。再加上逐次审批，远端写入不比自己打电话让 AI 写文件更宽。
     */
    private fun agentContext(workspacePath: String, approvalSessionId: String): AgentContext = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = workspacePath,
        language = null,
        sessionId = approvalSessionId,
        mode = AgentMode.BUILD,
        writePaths = null
    )

    /**
     * 统一的失败返回：带错误码与「下一步」提示，让对端一次就能改对（而不是换几个参数重试）。
     * 提示语集中在 [RemoteHints]。
     */
    private fun failure(action: RemoteAction, message: String, code: String? = null): RemoteActionResult =
        RemoteActionResult(
            text = RemoteHints.append(
                if (code.isNullOrBlank()) message else "[$code] $message",
                RemoteHints.forError(code, message)
            ),
            isError = true
        )

    private fun toActionResult(result: ToolResult, action: RemoteAction): RemoteActionResult = when (result) {
        is ToolResult.Success -> RemoteActionResult(
            text = RemoteHints.append(result.data.toString(), RemoteHints.forSuccess(action)),
            images = result.images
        )
        is ToolResult.Error -> RemoteActionResult(
            text = RemoteHints.append(
                "[${result.code}] ${result.message}",
                RemoteHints.forError(result.code, result.message)
            ),
            isError = true
        )
        is ToolResult.Partial -> RemoteActionResult(
            text = RemoteHints.append(
                "${result.data}\n${result.message}",
                RemoteHints.forError(null, result.message)
            )
        )
    }

    private suspend fun deviceInfo(caller: RemotePairedDevice): JsonObject = buildJsonObject {
        put("name", settings.displayName())
        put("id", settings.installId())
        put("kind", "phone")
        put("os", "Android")
        put("os_version", Build.VERSION.RELEASE ?: "")
        put("sdk", Build.VERSION.SDK_INT)
        put("app_version", appVersion())
        putJsonArray("actions") { catalog.actions.forEach { add(JsonPrimitive(it.name)) } }
        putJsonArray("capabilities") { catalog.capabilityList().forEach { add(JsonPrimitive(it)) } }
        put("caller", caller.deviceName.ifBlank { caller.deviceId })
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}
