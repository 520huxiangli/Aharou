package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentClaimVerifier
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import com.aharou.feature.agent.domain.subagent.SubAgentResultInspector
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.subagent.WriteLease
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理工具 `task`：统一管理子代理的生命周期。
 *
 * 通过 `action` 参数区分操作类型：
 * - `create`（默认）：创建一个子代理会话并让 AI 替用户向其发消息，子代理自动开始回复。
 * - `send`：向指定子代理发送一条消息（可反复发送）。运行中的子代理会在下一批工具结果里搭车收到，
 *   已完成的子代理会被重新唤醒；消息按发送顺序送达。
 * - `read`：读取指定子代理的最后输出（最后一条助手回复）。
 * - `stop`：停止指定子代理的执行（取消其 AI 任务）。
 * - `del`：删除指定子代理会话及其全部消息。
 * - `list`：列出当前会话的全部子代理及其状态。
 * - `team`：列出同批队友（同一父会话下的其它子代理）：谁在跑、各自负责任务与角色。
 * - `tell`：向同批队友发一条消息（id + message），供分支上的子代理互相交流。
 *
 * 最多同时允许 5 个运行中的子代理（create 时检查上限），嵌套派发同样计入该上限。
 * 子代理可再派子代理，但最多嵌套 2 层（主代理 → 子代理 → 孙代理，见 [AgentDefinition.MAX_SUBAGENT_DEPTH]），
 * 再深会被拒绝。
 *
 * `create` 可用 `agent` 参数指定自定义子代理定义（`agents/<name>.md`），
 * 由定义决定该子会话的模型、工具集与系统提示词；省略则用继承父会话模型的默认通用子代理。
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val subAgentWriteLease: SubAgentWriteLease
) : AbstractContextualTool() {

    /**
     * 派发临界区：「查写路径冲突 → 建会话 → 登记租约」必须串行。
     * 同一轮并发 create 时若不互斥，几个子代理会同时通过冲突检查（线上踩过：两个都改同一个 strings.xml 都没被拦）。
     */
    private val createMutex = Mutex()

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30
        /** `roles` 动作一次最多列多少个角色（避免把 140 个角色全塞进上下文）。 */
        const val ROLE_LIST_LIMIT = 30
        /** `roles` 里每条描述的截断长度。 */
        const val ROLE_DESCRIPTION_MAX = 160

        /** 流水线等待子代理的时长上限：超了就带着「仍在运行」的结论返回，不让工具调用无限挂住。 */
        const val PIPELINE_WAIT_TIMEOUT_MS = 30L * 60L * 1000L
        /** 能改文件/落盘的工具：出现在角色白名单里就说明它不是只读角色。 */
        val WRITE_TOOLS = setOf("writeFile", "editFile", "generateImage")
        /** 能跑命令或操作设备的工具：用在「能不能执行命令」的提示上。 */
        val EXEC_TOOLS = setOf("Bash", "terminal", "Shizuku", "vscreen", "a11y")
        /** 未指定 agent 时子会话记录的类型标识。 */
        const val DEFAULT_SUBAGENT_TYPE = "subagent"
        /** `team` 名单一次最多列多少个队友，以及单个队友标题的截断长度。 */
        const val TEAM_ROSTER_LIMIT = 20
        /** 发给队友的单条消息长度上限（与 messageParent 一致）。 */
        const val SIBLING_MESSAGE_MAX = 4000
    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK

    /** 非 PLAN 模式直接放行（派子代理是默认行为）；PLAN 下仍逐次确认。 */
    override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
        if (mode == AgentMode.PLAN) permissionPolicy else ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send", "roles", "team", "tell" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description = "管理子代理：创建、发消息、读取结果、停止、删除、列表、查角色。子代理拥有独立上下文与完整工具能力，可并行工作，最多同时运行 5 个。任务复杂或几个活能并行时默认直接派，不必等用户开口；多个独立子任务应同一轮并发发起多个 create。完成后会收到后台通知，不要轮询。用 send 可反复追加指令或对已完成子代理继续追问；子代理运行中也可能主动发消息。create 选角：优先用 department + agentQuery（部门 + 2~5 个功能关键词，中英文都可）让系统选一个最对口的角色；不确定有哪些角色名时先用 action=\"roles\" 查出来，确知角色名时用 agent 点名。分支上的子代理可用 team 查队友、tell 互相发消息；子代理最多嵌套 2 层。子代理写到租约外被拦时会来求助，用 action=\"grant\"（id + writePaths）运行期追加写权限即可，不必重派。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：create（默认，创建子代理并执行任务）/ send（向子代理发一条消息，可反复发送）/ read（读取子代理的最后输出）/ stop（停止子代理的执行）/ del（删除子代理会话及其消息）/ list（列出当前会话的全部子代理）/ roles（列出可用子代理角色，可按 department、agentQuery 过滤排序，用于查名字后再用 agent 点名）/ team（列出同批队友：同一父会话下的其它子代理及其任务与状态）/ tell（向同批队友发一条消息，配 id 与 message 使用）/ grant（运行期给已派发的子代理追加可写路径：id + writePaths）",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "子会话 id（read/send/stop/del/tell 必填，取自 task 返回的 id 或 team 名单）",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "子代理任务描述（create 用，作为子会话标题，如「修复登录 bug」）",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "给子代理的完整指令（create 必填），将作为它的第一条用户消息；子代理看到的是全新上下文",
            required = false
        ),
        "agent" to ToolParameter(
            name = "agent",
            type = ParameterType.STRING,
            description = "自定义子代理名（create 可选）：仅在确知系统提示词「可用子代理」索引里的确切名称时使用，一般改用 department + agentQuery 让系统选角。省略则用继承本会话模型的默认通用子代理",
            required = false
        ),
        "department" to ToolParameter(
            name = "department",
            type = ParameterType.STRING,
            description = "子代理部门（create 可选）：取系统提示词「可用子代理」部门索引里的部门名，如 engineering / security / testing / design / product / project-management / specialized / game-development / unity / unreal / godot / roblox / xr / explore。与 agentQuery 配合在该部门内选角",
            required = false
        ),
        "agentQuery" to ToolParameter(
            name = "agentQuery",
            type = ParameterType.STRING,
            description = "选角关键词（create 可选，2~5 个词）：描述任务的功能或领域，如 \"code review\"、\"database migration\"、\"ui design\"。与 department 配合在该部门内按名称与用途描述打分选一个角色，比记角色名更可靠",
            required = false
        ),
        "model" to ToolParameter(
            name = "model",
            type = ParameterType.STRING,
            description = "子代理使用的模型名（create 可选）：显式传给子会话，覆盖角色定义里的 model；留空则继承角色定义，未选角色时继承本会话模型",
            required = false
        ),
        "provider" to ToolParameter(
            name = "provider",
            type = ParameterType.STRING,
            description = "子代理使用的提供商（create 可选）：可写 providerId 或设置里显示的提供商名称，覆盖角色定义里的 provider；留空则继承角色定义，未选角色时继承本会话 provider",
            required = false
        ),
        "writePaths" to ToolParameter(
            name = "writePaths",
            type = ParameterType.ARRAY,
            description = "该子代理允许写入的文件路径（create 可选）。纯调研/分析传 [] 表示只读（写文件类工具会被直接拒绝）；需要落盘时传精确文件或目录；确需独占整个工作区才传 [\"*\"]。省略表示不限制。注意：闸门只约束 writeFile/editFile，shell 重定向不受限。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "pipeline" to ToolParameter(
            name = "pipeline",
            type = ParameterType.ARRAY,
            description = "流水线任务列表（action=pipeline 必填）。每项：{id, description, prompt, dependsOn?, writePaths?, agent? / department?+agentQuery?, model?, provider?}。" +
                "无依赖的任务同层并发跑；有依赖的等上游全部完成后才跑，且上游产出会自动接在它的 prompt 前面。" +
                "同一层任务的 writePaths 不得相交（含目录与其中文件的关系）。",
            required = false,
            itemsSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "string", "description" to "本流水线内唯一的任务 id"),
                    "description" to mapOf("type" to "string", "description" to "短标题"),
                    "prompt" to mapOf("type" to "string", "description" to "完整任务指令"),
                    "dependsOn" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "依赖的任务 id 列表"
                    ),
                    "writePaths" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "允许写入的路径；[] 表示只读，省略表示不限制"
                    ),
                    "agent" to mapOf("type" to "string"),
                    "department" to mapOf("type" to "string"),
                    "agentQuery" to mapOf("type" to "string"),
                    "model" to mapOf("type" to "string", "description" to "该分支使用的模型；留空继承角色定义或父会话"),
                    "provider" to mapOf("type" to "string", "description" to "该分支使用的提供商（providerId 或名称）；留空继承角色定义或父会话")
                )
            )
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给子代理的消息正文（send/tell 必填）。可反复调用；运行中的子代理会尽快收到，已完成的会被重新唤醒",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "pipeline" -> runPipeline(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            "roles" -> listRoles(args)
            "team" -> listTeam(context)
            "tell" -> tellSibling(args, context)
            "grant" -> grantWrite(args)
            else -> ToolResult.Error("未知 action: $action，支持：create / pipeline / send / read / stop / del / list / roles / team / tell / grant", "INVALID_ARGS")
        }
    }

    /** 运行期追加写权限：子代理被租约拦住来求助时，主会话放行即可，不必重派；冲突规则同派发。 */
    private fun grantWrite(args: Map<String, JsonElement>): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) return ToolResult.Error("grant 需要 id（子代理会话 id）", "MISSING_ID")
        val paths = stringSetOf(args["writePaths"]).orEmpty().filter { it.isNotBlank() }.toSet()
        if (paths.isEmpty()) return ToolResult.Error("grant 需要非空 writePaths", "MISSING_WRITEPATHS")
        subAgentWriteLease.conflictFor(id, paths, eventBus.activeSubSessionIds.value)?.let { conflictId ->
            return ToolResult.Error(
                "追加的写范围与在跑的子代理 $conflictId 重叠，等它结束后再追加",
                "WRITE_CONFLICT"
            )
        }
        val merged = subAgentWriteLease.grant(id, paths)
        FileLogger.i("TaskTool", "追加写租约 $id += ${paths.joinToString(", ")}（合并后：${merged.joinToString(", ")}）")
        return ToolResult.Success(buildJsonObject {
            put("id", JsonPrimitive(id))
            put("writePaths", buildJsonArray { merged.sorted().forEach { add(JsonPrimitive(it)) } })
        })
    }

    /**
     * 依赖流水线：一次调用把整张 DAG 交出去。
     *
     * 分层（拓扑排序）后逐层执行：层内并发派发，层间串行等待。上游任务的产出会自动接在
     * 下游 prompt 前面，下游不必再自己去读一遍上游做过什么。价值在于主模型不必
     * 「派一批 → 等 → 再派下一批」地串行驱动链式任务。
     *
     * 同层任务的写范围必须互不相交（借用 [WriteLease.overlaps]，与单任务 create 同一套判定）。
     */
    private suspend fun runPipeline(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")

        val raw = args["pipeline"] as? JsonArray
            ?: return ToolResult.Error("pipeline 必须是数组（每项至少要有 id 与 prompt）", "INVALID_ARGS")

        val tasks = raw.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || prompt.isEmpty()) {
                null
            } else {
                PipelineTask(
                    id = id,
                    prompt = prompt,
                    description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim()
                        ?.replace(Regex("\\s+"), " ")?.take(TASK_DESCRIPTION_MAX)?.takeIf { it.isNotBlank() } ?: id,
                    dependsOn = stringSetOf(obj["dependsOn"]).orEmpty(),
                    writePaths = stringSetOf(obj["writePaths"]),
                    agent = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    department = (obj["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    agentQuery = (obj["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    model = (obj["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                )
            }
        }
        if (tasks.isEmpty()) {
            return ToolResult.Error("pipeline 里没有有效任务：每项至少要给 id 与 prompt", "INVALID_ARGS")
        }
        val knownIds = tasks.map { it.id }.toSet()
        if (knownIds.size != tasks.size) {
            return ToolResult.Error("pipeline 里存在重复的 id", "INVALID_ARGS")
        }
        tasks.forEach { task ->
            val missing = task.dependsOn - knownIds
            if (missing.isNotEmpty()) {
                return ToolResult.Error("任务「${task.id}」依赖了不存在的 id：${missing.joinToString("、")}", "INVALID_ARGS")
            }
        }

        val layers = try {
            pipelineLayers(tasks)
        } catch (e: IllegalStateException) {
            return ToolResult.Error("pipeline 的依赖关系无法排序：${e.message}", "INVALID_ARGS")
        }

        val outputs = mutableMapOf<String, String>()
        val report = mutableListOf<String>()

        for ((layerIndex, layer) in layers.withIndex()) {
            overlappingPair(layer)?.let { (a, b) ->
                return ToolResult.Error(
                    "同一层的「${a.id}」与「${b.id}」写范围相交，并发会互相踩：" +
                        "把其中一个改成只读（writePaths=[]），或用 dependsOn 把它们串到不同层",
                    "WRITE_CONFLICT"
                )
            }
            if (layer.size > SubAgentEventBus.MAX_RUNNING) {
                return ToolResult.Error(
                    "第 ${layerIndex + 1} 层有 ${layer.size} 个任务，超过并发上限 ${SubAgentEventBus.MAX_RUNNING}：" +
                        "拆成多层（用 dependsOn 串起来）或减少同层任务数",
                    "TOO_MANY_IN_LAYER"
                )
            }

            val spawned = mutableMapOf<String, String>()
            for (task in layer) {
                val upstream = task.dependsOn.mapNotNull { dep ->
                    outputs[dep]?.takeIf { it.isNotBlank() }?.let { "## 上游任务「$dep」的产出\n$it" }
                }.joinToString("\n\n")
                val fullPrompt = if (upstream.isEmpty()) task.prompt else "$upstream\n\n---\n\n${task.prompt}"

                val createArgs = buildJsonObject {
                    put("action", "create")
                    put("prompt", fullPrompt)
                    put("description", task.description)
                    task.agent?.let { put("agent", it) }
                    task.department?.let { put("department", it) }
                    task.agentQuery?.let { put("agentQuery", it) }
                    task.model?.let { put("model", it) }
                    task.provider?.let { put("provider", it) }
                    task.writePaths?.let { paths ->
                        put("writePaths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                val created = createSubagent(createArgs, context)
                if (created is ToolResult.Error) {
                    outputs[task.id] = ""
                    report += "- ${task.id}：派发失败（${created.code}）${created.message}"
                    continue
                }
                val subId = ((created as? ToolResult.Success)?.data as? JsonObject)
                    ?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (subId.isNullOrBlank()) {
                    outputs[task.id] = ""
                    report += "- ${task.id}：派发后没拿到子会话 id"
                } else {
                    spawned[task.id] = subId
                }
            }
            if (spawned.isEmpty()) continue

            val finished = awaitSubAgents(spawned.values.toSet())
            for ((taskId, subId) in spawned) {
                val event = finished[subId]
                outputs[taskId] = readSubagentOutput(subId, context)
                val verdict = when {
                    event == null -> "仍在运行（等待超时）"
                    event.type == SubAgentEventType.FAILED -> "失败"
                    event.accepted == false -> "完成但未通过交付判定"
                    else -> "完成"
                }
                report += "- $taskId：$verdict（子会话 $subId）"
            }
        }

        val summary = report.joinToString("\n")
        return ToolResult.Success(
            buildJsonObject {
                put("state", "done")
                put("layers", layers.size)
                put("report", summary)
                put(
                    "message",
                    "流水线已跑完（共 ${tasks.size} 个任务、${layers.size} 层）：\n$summary\n" +
                        "各任务产出已按依赖自动传给下游；要看某个任务的完整输出用 task(action=\"read\", id=<子会话 id>)。"
                )
            }
        )
    }

    /** 等这些子代理全部跑到终态；超时则返回已收到的部分（缺失即「仍在运行」）。 */
    private suspend fun awaitSubAgents(ids: Set<String>): Map<String, SubAgentEvent> =
        withTimeoutOrNull(PIPELINE_WAIT_TIMEOUT_MS) {
            val done = mutableMapOf<String, SubAgentEvent>()
            val remaining = ids.toMutableSet()
            while (remaining.isNotEmpty()) {
                val event = eventBus.events.first { it.subSessionId in remaining && it.type.isTerminal() }
                remaining.remove(event.subSessionId)
                done[event.subSessionId] = event
            }
            done
        } ?: emptyMap()

    private fun SubAgentEventType.isTerminal(): Boolean =
        this == SubAgentEventType.COMPLETED || this == SubAgentEventType.FAILED || this == SubAgentEventType.STOPPED

    /** 读子代理最后输出；复用 read 路径，保证与手动 read 拿到的是同一份结论。 */
    private suspend fun readSubagentOutput(subSessionId: String, context: AgentContext): String {
        val readArgs = buildJsonObject {
            put("action", "read")
            put("id", subSessionId)
        }
        val result = readSubagent(readArgs, context)
        return ((result as? ToolResult.Success)?.data as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /** 拓扑分层：同层任务互不依赖（可并发）；依赖成环时抛 IllegalStateException。 */
    private fun pipelineLayers(tasks: List<PipelineTask>): List<List<PipelineTask>> {
        val byId = tasks.associateBy { it.id }
        val depth = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        fun depthOf(id: String): Int {
            depth[id]?.let { return it }
            if (!visiting.add(id)) throw IllegalStateException("任务「$id」形成依赖环")
            val task = byId.getValue(id)
            val value = if (task.dependsOn.isEmpty()) 0 else task.dependsOn.maxOf { depthOf(it) } + 1
            visiting.remove(id)
            depth[id] = value
            return value
        }
        return tasks.groupBy { depthOf(it.id) }.toSortedMap().values.toList()
    }

    /** 同层里第一对写范围相交的任务；没有则返回 null。 */
    private fun overlappingPair(layer: List<PipelineTask>): Pair<PipelineTask, PipelineTask>? {
        for (i in layer.indices) {
            val a = layer[i].writePaths ?: continue
            for (j in i + 1 until layer.size) {
                val b = layer[j].writePaths ?: continue
                if (WriteLease.overlaps(a, b)) return layer[i] to layer[j]
            }
        }
        return null
    }

    /** 把 JSON 数组参数读成字符串集合；非数组返回 null（表示「未声明」，与 create 的语义一致）。 */
    private fun stringSetOf(element: JsonElement?): Set<String>? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private data class PipelineTask(
        val id: String,
        val prompt: String,
        val description: String,
        val dependsOn: Set<String>,
        val writePaths: Set<String>?,
        val agent: String?,
        val department: String?,
        val agentQuery: String?,
        val model: String?,
        val provider: String?
    )

    /** 解析 writePaths 参数；未提供返回 null（不登记租约 = 不限制）。 */
    private fun parseWritePaths(args: Map<String, JsonElement>): Set<String>? {
        val raw = args["writePaths"] as? JsonArray ?: return null
        return raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 创建子代理并启动执行。 */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")

        // 递归深度上限：主代理（0）→ 子代理（1）→ 孙代理（2）为限，再深直接拒。
        // 深度按会话记录的 parentId 链回溯，模型无法用参数伪造。
        val depth = subagentDepth(parentSessionId)
        if (depth >= AgentDefinition.MAX_SUBAGENT_DEPTH) {
            return ToolResult.Error(
                "子代理最多嵌套 ${AgentDefinition.MAX_SUBAGENT_DEPTH} 层（主代理 → 子代理 → 孙代理），" +
                    "当前会话已在第 $depth 层，不能再往下派发。继续下派会让代理树无限膨胀、互相等待而卡死；" +
                    "请在本层把任务做完，或把需求用 messageParent 交回上一层统筹。",
                "MAX_SUBAGENT_DEPTH_EXCEEDED"
            )
        }

        // 检查并发上限
        if (eventBus.isFull) {
            return ToolResult.Error(
                "子代理已达上限（最多 ${SubAgentEventBus.MAX_RUNNING} 个同时运行），请先等待其中某个完成或用 stop 停止后再创建",
                "MAX_SUBAGENTS_REACHED"
            )
        }

        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrBlank()) {
            return ToolResult.Error("参数无效：prompt 不能为空", "INVALID_ARGS")
        }
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "子代理任务"

        // 选角顺序：agent 精确名（兼容旧用法）→ department + agentQuery 关键词匹配 → 默认通用子代理。
        // 前两条找不到定义时直接报错并给出可用部门，不静默回退成通用子代理——
        // 否则会拿着错的工具集与提示词跑完整个任务。
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val department = (args["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = (args["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null || department != null || agentQuery != null) {
            val enabled: List<AgentDefinition> = try {
                agentDefinitionRepository.listEnabled().map { it.definition }
            } catch (e: Exception) {
                FileLogger.e(TAG, "读取子代理定义失败", e)
                return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
            }
            definition = if (agentName != null) {
                enabled.firstOrNull { it.name.equals(agentName, ignoreCase = true) }
                    ?: return ToolResult.Error(
                        "子代理定义不存在: $agentName。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}；" +
                            "也可改用 department + agentQuery 让系统选角。",
                        "AGENT_NOT_FOUND"
                    )
            } else {
                when (val outcome = SubAgentProfileMatcher.match(enabled, department, agentQuery)) {
                    is SubAgentProfileMatcher.MatchOutcome.Matched -> outcome.definition
                    is SubAgentProfileMatcher.MatchOutcome.Unmatched -> return ToolResult.Error(outcome.reason, "AGENT_NOT_FOUND")
                }
            }
        }

        // 显式 model/provider 覆盖：传了就盖掉角色定义里的同名值，未传则沿用定义（再退到父会话继承）。
        // provider 解析不出时不静默回退，否则用户显式指定的提供商被悄悄忽略，跑在别的模型上。
        val explicitModel = (args["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val explicitProvider = (args["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val explicitProviderId = explicitProvider?.let { resolveProviderId(it) }
        if (explicitProvider != null && explicitProviderId == null) {
            return ToolResult.Error(
                "找不到提供商: $explicitProvider（可写 providerId，或设置里显示的提供商名称）",
                "PROVIDER_NOT_FOUND"
            )
        }

        // 并行写隔离：同一时刻两个子代理写同一处会互相踩，冲突的直接拒绝，由主代理串行派发。
        val writePaths = parseWritePaths(args)
        val subSession = createMutex.withLock {
            if (writePaths != null && writePaths.isNotEmpty()) {
                subAgentWriteLease.conflictingSession(writePaths, eventBus.activeSubSessionIds.value)?.let { conflictId ->
                    return ToolResult.Error(
                        "与在跑的子代理 $conflictId 的可写路径冲突：等它结束后再派发，或改成只读（writePaths=[]）并行。",
                        "WRITE_CONFLICT"
                    )
                }
            }
            sessionUseCase.newSubSessionEntity(
                title = description,
                parentId = parentSessionId,
                parent = parentSession,
                subagentType = definition?.name ?: DEFAULT_SUBAGENT_TYPE,
                providerId = explicitProviderId ?: definition?.providerId?.let { resolveProviderId(it) },
                model = explicitModel ?: definition?.model,
                reasoningEffort = definition?.reasoningEffort?.let { effort ->
                    ReasoningEffort.entries.firstOrNull { it.apiValue == effort }?.name
                },
                mode = definition?.mode
            ).also { created ->
                sessionUseCase.upsertSession(created)
                // 写路径租约：派发时声明了才登记；未声明则不限制（保持旧行为）。锁内登记，后续并发派发才看得见。
                writePaths?.let { subAgentWriteLease.register(created.id, it) }
            }
        }
        val subSessionId = subSession.id

        // 团队简报：把同批队友与协作方式接在任务指令前，让分支上的模型知道彼此是一个团队。
        // 取不到简报时退回原始指令，不影响派发（新增通道失败不拖垮主流程）。
        val briefing = runCatching { teamBriefing(parentSessionId, subSessionId) }.getOrNull()
        val detail = if (briefing.isNullOrBlank()) prompt else "$briefing\n\n---\n\n$prompt"

        // 通知 ViewModel 在子会话上启动 AI 工作流
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.SPAWNED,
                detail = detail
            )
        )
        FileLogger.i(TAG, "子代理已创建: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                definition?.let { put("agent", it.name) }
                put("message", "子代理已创建并开始执行，任务完成后会通知。可用 task(action=\"read\", id=...) 读取输出，task(action=\"stop\", id=...) 主动关闭。")
            }
        )
    }

    /**
     * 向指定子代理发送一条消息（可反复发送）。
     *
     * 事件交由 ViewModel 按收件人状态分发：运行中的子代理把消息入通知队列、在下一批工具结果里搭车送达；
     * 已完成的子代理则被重新唤醒并起新一轮。不在工具层直接投递，以复用同一套忙碌/空闲分发逻辑。
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("只能向当前会话派生的子代理发消息", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message
            )
        )
        FileLogger.i(TAG, "向子代理发送消息: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "delivered")
                put("message", "消息已投递给子代理。运行中的会在下一批工具结果里收到，已完成的会被重新唤醒；可继续用 send 追加。")
            }
        )
    }

    /**
     * 把定义里的 provider 字段解析为真实 provider id：先按 id 精确匹配，再按名称忽略大小写匹配，
     * 让用户在 frontmatter 里能直接写设置里看到的提供商名。都匹不上则返回 null（继承父会话）。
     */
    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    /** 读取指定子代理的最后输出。 */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能读取当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        val messages = messagePersistenceUseCase.getMessagesOnce(subSessionId)
        // 取最后一条有内容的助手回复（跳过 reasoning-only 的中间消息）
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（子代理尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（子代理会话为空）"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)

        // 判定与收尾通知共用同一份检查（见 SubAgentResultInspector），避免两条路径各写一套而分叉。
        val inspection = SubAgentResultInspector.inspect(subSessionId, messages)
        val report = inspection.report
        val verdict = inspection.verdict
        val outputText = if (lastAssistant != null) inspection.conclusion.ifBlank { lastAssistant.content } else content

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", outputText)
                put("termination", verdict.termination.name)
                if (verdict.reason.isNotBlank()) put("terminationReason", verdict.reason)
                put(
                    "tokenUsage",
                    buildJsonObject {
                        put("inputTokens", inspection.inputTokens)
                        put("outputTokens", inspection.outputTokens)
                    }
                )
                report?.let { r ->
                    put("claimedStatus", r.declaredStatus)
                    put("effectiveStatus", r.effectiveStatus)
                    if (r.summary.isNotBlank()) put("claimSummary", r.summary)
                    if (r.downgraded) {
                        put(
                            "notice",
                            "子代理自报 ${r.declaredStatus}，但有验收项核验不上，按 ${r.effectiveStatus} 处理。"
                        )
                    }
                    put(
                        "verification",
                        buildJsonArray {
                            r.items.forEach { item ->
                                addJsonObject {
                                    put("claim", item.claim)
                                    put("type", item.type)
                                    put("passed", item.passed)
                                    put("note", item.note)
                                }
                            }
                        }
                    )
                    put("adjudication", SubAgentClaimVerifier.renderAdjudication(r))
                }
            }
        )
    }

    /** 停止指定子代理的执行。 */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能关闭当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOPPED
            )
        )
        subAgentWriteLease.release(subSessionId)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "已请求停止子代理，正在取消其 AI 任务。")
            }
        )
    }

    /** 删除指定子代理会话（含其消息）。若仍在运行先请求停止。 */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能删除当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }
        if (eventBus.activeSubSessionIds.value.contains(subSessionId)) {
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = context.sessionId!!,
                    type = SubAgentEventType.STOPPED
                )
            )
        }
        subAgentWriteLease.release(subSessionId)
        sessionUseCase.deleteSession(subSessionId)
        FileLogger.i(TAG, "子代理已删除: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleted")
                put("message", "子代理已删除（含其全部消息）。")
            }
        )
    }

    /** 列出当前会话的全部子代理。 */
    private suspend fun listSubagents(context: AgentContext): ToolResult {        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in activeIds) "running" else "completed")
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", SubAgentEventBus.MAX_RUNNING)
            }
        )
    }

    /**
     * 会话在代理树里的深度：主代理（根会话）为 0，其子代理为 1，孙代理为 2……
     * 逐级按 parentId 回溯；带跳过上限，异常数据（如成环）也不会无限递归。
     */
    private suspend fun subagentDepth(sessionId: String): Int {
        var depth = 0
        var cursor = sessionUseCase.getSessionById(sessionId)?.parentId
        while (cursor != null && depth <= AgentDefinition.MAX_SUBAGENT_DEPTH + 1) {
            depth++
            cursor = sessionUseCase.getSessionById(cursor)?.parentId
        }
        return depth
    }

    /**
     * 新子代理的团队简报：列出同批队友（同父会话的其它子代理）与协作方式，
     * 让分支上的模型知道彼此是一个团队、可以互相交流。
     */
    private suspend fun teamBriefing(parentSessionId: String, selfId: String): String {
        val siblings = chatSessionDao.getSubSessionsByParentOnce(parentSessionId).filter { it.id != selfId }
        val activeIds = eventBus.activeSubSessionIds.value
        val lines = mutableListOf(
            "## 团队协作",
            "你与同一父会话派出的其它子代理是一个团队，可以互相交流、分工协作。"
        )
        if (siblings.isEmpty()) {
            lines += "当前还没有其它队友（后继可能加入）。"
        } else {
            lines += "当前队友："
            siblings.take(TEAM_ROSTER_LIMIT).forEach { s ->
                val role = s.subagentType?.takeIf { it.isNotBlank() }?.let { "$it，" }.orEmpty()
                lines += "- $role${s.title}（${if (s.id in activeIds) "运行中" else "已完成"}，id=${s.id}）"
            }
        }
        lines += "查看实时队友：task(action=\"team\")；给队友发消息：task(action=\"tell\", id=\"<队友 id>\", message=\"…\")。"
        return lines.joinToString("\n")
    }

    /** 列出同批队友（同父会话的其它子代理）：谁在跑、各自负责任务与角色。主会话无父，返回提示。 */
    private suspend fun listTeam(context: AgentContext): ToolResult {
        val selfId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentId = sessionUseCase.getSessionById(selfId)?.parentId
            ?: return ToolResult.Error("当前是主会话，没有同批队友；查看派出的子代理请用 action=\"list\"。", "NO_PARENT")
        val activeIds = eventBus.activeSubSessionIds.value
        val teammates = eventBus.siblingsOf(selfId).take(TEAM_ROSTER_LIMIT).mapNotNull { id ->
            sessionUseCase.getSessionById(id)?.let { entity ->
                buildJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("role", entity.subagentType ?: "")
                    put("state", if (entity.id in activeIds) "running" else "completed")
                }
            }
        }
        return ToolResult.Success(
            buildJsonObject {
                put("parent", parentId)
                put("teammates", buildJsonArray { teammates.forEach { add(it) } })
                put("count", teammates.size)
                put(
                    "message",
                    if (teammates.isEmpty()) "当前没有其它队友。"
                    else "给队友发消息用 task(action=\"tell\", id=\"<队友 id>\", message=\"…\")。"
                )
            }
        )
    }

    /** 向同批队友发一条消息（复用子代理消息投递通道：收件人忙碌时搭车、空闲时被唤醒）。 */
    private suspend fun tellSibling(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val selfId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val targetId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        if (targetId == selfId) return ToolResult.Error("不能给自己发消息", "INVALID_ARGS")
        if (message.length > SIBLING_MESSAGE_MAX) {
            return ToolResult.Error("消息过长（上限 $SIBLING_MESSAGE_MAX 字）", "INVALID_ARGS")
        }
        val parentId = sessionUseCase.getSessionById(selfId)?.parentId
            ?: return ToolResult.Error("当前是主会话，没有同批队友；向子代理发消息请用 action=\"send\"。", "NO_PARENT")
        val target = sessionUseCase.getSessionById(targetId)
            ?: return ToolResult.Error("队友会话不存在: $targetId", "SESSION_NOT_FOUND")
        if (target.parentId != parentId) {
            return ToolResult.Error("$targetId 不是你的队友（不在同一父会话下）；用 action=\"team\" 查看队友。", "NOT_SIBLING")
        }
        val delivered = runCatching { eventBus.sendToSibling(selfId, targetId, message) }.getOrDefault(false)
        if (!delivered) {
            return ToolResult.Error("消息未送达：该队友不在当前协作批次内或已被停止；用 action=\"team\" 查看实时队友。", "SIBLING_UNREACHABLE")
        }
        FileLogger.i(TAG, "子代理向队友发消息: from=$selfId to=$targetId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", targetId)
                put("state", "delivered")
                put("message", "消息已投递给队友，可继续手头工作；它的回复会像父会话消息一样送达你。")
            }
        )
    }

    /**
     * 角色能力摘要：直接列出该角色的可用工具，并点明「只读」「不能执行命令」这两个最常踩的边界——
     * 常驻索引里只有角色名与一句用途，主代理无从知道 Explore 这类角色不能改文件、不能跑命令。
     */
    private fun roleToolSummary(definition: AgentDefinition): String {
        val allowed = definition.allowedTools
        val disallowed = definition.disallowedTools
        val tools = when {
            allowed.isNotEmpty() -> allowed.joinToString("、")
            disallowed.isNotEmpty() -> "默认全量（禁用 ${disallowed.joinToString("、")}）"
            else -> "默认全量"
        }
        if (allowed.isEmpty()) return tools
        val notes = buildList {
            if (allowed.none { it in WRITE_TOOLS }) add("只读")
            if (allowed.none { it in EXEC_TOOLS }) add("不能执行命令")
        }
        return if (notes.isEmpty()) tools else "$tools ｜ ${notes.joinToString("、")}"
    }

    /**
     * 列出可用子代理角色（不创建）：[department] 收窄部门，[agentQuery] 走与 create 同一套选角打分排序。
     * 常驻索引里每个部门只展示 2 个代表角色，主代理想点名时先用这里把名字与用途查出来。
     */
    private suspend fun listRoles(args: Map<String, JsonElement>): ToolResult {
        val department = (args["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = (args["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val enabled: List<AgentDefinition> = try {
            agentDefinitionRepository.listEnabled().map { it.definition }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取子代理定义失败", e)
            return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
        }
        if (enabled.isEmpty()) {
            return ToolResult.Error("当前没有可用的子代理定义", "AGENT_LIST_EMPTY")
        }
        if (department != null && SubAgentProfileMatcher.canonicalDepartment(department) == null) {
            return ToolResult.Error(
                "未知部门：$department。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}",
                "INVALID_ARGS"
            )
        }

        val ranked = SubAgentProfileMatcher.candidates(enabled, department, agentQuery, ROLE_LIST_LIMIT)
        if (ranked.isEmpty()) {
            return ToolResult.Error(
                "没有匹配的角色（department=${department ?: "-"}, agentQuery=${agentQuery ?: "-"}）。" +
                    "可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}",
                "AGENT_NOT_FOUND"
            )
        }
        val content = buildString {
            append("可用子代理角色")
            if (department != null) append("（department=").append(department).append("）")
            if (agentQuery != null) append("（agentQuery=\"").append(agentQuery).append("\" 排序）")
            append("，共 ").append(ranked.size).append(" 个：\n")
            ranked.forEach { (definition, _) ->
                val summary = definition.description.lineSequence().first().trim().take(ROLE_DESCRIPTION_MAX)
                append("- ").append(definition.name).append("：").append(summary)
                    .append("（工具：").append(roleToolSummary(definition)).append("）\n")
            }
            append("派发：task(action=\"create\", agent=\"<名称>\", prompt=\"…\")；不确定就用 department + agentQuery 让系统选角。")
        }
        return ToolResult.Success(
            buildJsonObject {
                put("content", content)
                put("count", ranked.size)
            }
        )
    }
}