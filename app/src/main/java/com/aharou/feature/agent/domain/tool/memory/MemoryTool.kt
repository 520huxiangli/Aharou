package com.aharou.feature.agent.domain.tool.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.memory.MemoryUsageStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryEdit
import com.aharou.feature.agent.domain.memory.MemoryEditResult
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import com.aharou.feature.agent.domain.memory.MemorySyncManager
import com.aharou.feature.agent.domain.memory.MemorySyncOutcome
import com.aharou.feature.agent.domain.memory.MemorySyncSettings
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.memory.MemoryWriteResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.prompt.MemoryPromptRenderer
import com.aharou.feature.agent.domain.session.GhostModeStore
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val aharouMemory: com.aharou.core.memory.AharouMemoryStore,
    private val memoryTrace: MemoryTraceLog,
    private val chatSessionDao: ChatSessionDao,
    private val memorySyncManager: MemorySyncManager,
    private val memorySyncSettings: MemorySyncSettings,
    private val memoryUsageStore: MemoryUsageStore,
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"

        /** `sync` 的最小间隔：同步一次要 collect + 拉取 + 推送，连刷只会白烧网络。 */
        const val SYNC_MIN_INTERVAL_MS = 60_000L

        /** 上次发起同步的时刻（进程内）；成功完成的时间另由 lastSyncAt 持久化。 */
        val lastSyncAttemptAt = AtomicLong(0L)

        /** 检索放宽时用来切词。 */
        private val WHITESPACE = Regex("\\s+")

        /** 「冷记忆」判定：超过这么多天没被 read 过就算。 */
        private const val STALE_DAYS = 30L

        /** `sync` 明细里最多列多少个文件名 / 失败项，超过只报总数。 */
        private const val SYNC_DETAIL_LIST_LIMIT = 20

        /** `stats` 里「被预算挤出」与「不走预算」的 identity 条目最多列多少条。 */
        private const val STATS_LIST_LIMIT = 30

        /** 记忆总量软阀值：超过就在 stats 里提醒——写再多清单也装不下。 */
        private const val MEMORY_COUNT_SOFT_LIMIT = 800

        /** 乐观锁冲突的错误码：模型据此判定「我该合并后重试」而不是「写入坏了」。 */
        const val VERSION_CONFLICT_CODE = "MEMORY_VERSION_CONFLICT"
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list", "search" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            "stats" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "管理 AI 的长期记忆（read/save/edit/delete/list；log=每日日志；fact=事实库；mindstream=心流；core=读/写核心档案；" +
            "sync=立刻把记忆同步到用户配置的仓库（返回收上的文件清单与跳过/失败分类）；" +
            "stats=使用统计、冷记忆清单与常驻正文预算占用；undo=回滚到最近一次归档）。发现新的用户偏好、项目约定或架构决策时主动记录。" +
            "覆盖已有条目前必须带上 read 返回的 version（乐观锁）：版本不一致会拒绝写入并回你最新正文，合并后重试。"

    /** edits 数组单个元素的结构，供 function-calling 的 items schema，语义与 editFile 一致。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与记忆当前正文精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：read=读取记忆正文；save=保存（创建或全量覆盖）；edit=局部编辑已有正文；delete=删除；list=列出所有记忆摘要",
            enum = listOf("read", "save", "edit", "delete", "list", "search", "log", "fact", "mindstream", "core", "sync", "stats", "undo"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "记忆的短名称（作为文件名，如 conventions）。list 操作可省略。",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "一句话摘要（save 必填，将出现在系统提示词的记忆清单中）。",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "记忆的详细正文（Markdown 格式，save 必填）。",
            required = false
        ),
        "version" to ToolParameter(
            name = "version",
            type = ParameterType.STRING,
            description = "乐观锁版本（save / edit 可用）。覆盖已有条目时传 read 返回的 version；" +
                "确认要新建一条不存在的记忆时传 \"new\"。不传而目标已存在会被拦下并回你最新正文，" +
                "防止把你上下文里已过期的内容盖上去。",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "edit 操作要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "作用域：project=当前项目专属；global=跨项目通用。read/list/search 不指定时查看项目优先的合并视图；写操作默认 project。显式指定时只访问该作用域。", 
            enum = listOf("project", "global"),
            required = false
        ),
        "type" to ToolParameter(
            name = "type",
            type = ParameterType.STRING,
            description = "记忆分类：identity=长期约定与红线（正文会在下一次会话直接进系统提示词常驻）；" +
                "preference=用户偏好；workflow=做事方式 / 流程约定；fact=一般事实（默认）；" +
                "event=一次性事件（只留档，不进注入清单）。只在值得跨会话反复引用时才用 identity。",
            enum = listOf("identity", "preference", "workflow", "fact", "event"),
            required = false
        ),
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "search 的检索关键词；兼容用 name 传入关键词。",
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "search 最多返回的条数，默认 8，范围 1–50。",
            required = false
        ),
        "ttl_days" to ToolParameter(
            name = "ttl_days",
            type = ParameterType.INTEGER,
            description = "短期记忆的有效天数（正整数）。填了就是短期记忆：到期后自动不再进注入清单，" +
                "文件保留、仍可 read，无需人工清理。只在「当前任务 / 阶段临时有效」时填，长期知识别填。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        if (scopeStr != null && scopeStr !in setOf("global", "project")) {
            return ToolResult.Error("scope 仅支持 global 或 project", "INVALID_SCOPE")
        }
        val requestedScope = scopeStr?.let { if (it == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT }
        val scope = requestedScope ?: MemoryScope.PROJECT

        // 隐身会话不写任何记忆（read/list/search 等只读操作照常放行）。
        val ghostSessionId = context.sessionId
        if (ghostSessionId != null && writesMemory(action, args) &&
            GhostModeStore.isGhost(chatSessionDao, ghostSessionId)
        ) {
            return ToolResult.Error("当前会话处于隐身模式，本会话的任何内容都不会写入记忆。", "GHOST_MODE")
        }

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot, requestedScope)
                "search" -> handleSearch(args, context.projectRoot, requestedScope)
                "read" -> handleRead(memoryName, requestedScope, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot, context.sessionId)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot, context.sessionId)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot, context.sessionId)
                "log" -> handleDailyLog(args)
                "fact" -> handleFact(args)
                "mindstream" -> handleMindstream(args)
                "core" -> handleCore(args)
                "sync" -> handleSync()
                "stats" -> handleStats(context.projectRoot, requestedScope)
                "undo" -> handleUndo(memoryName, scope, context.projectRoot)
                else -> ToolResult.Error("不支持的操作: $action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory 工具执行失败: ${e.message}", e)
            ToolResult.Error("记忆操作失败: ${e.message}")
        }
    }

    /** 该动作是否会写记忆（core 带 content 才写，不带 content 是读）。 */
    private fun writesMemory(action: String, args: Map<String, JsonElement>): Boolean = when (action) {
        "save", "edit", "delete", "log", "fact", "mindstream", "undo" -> true
        "core" -> !args["content"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
        else -> false
    }

    /**
     * 立刻跑一次记忆同步。
     *
     * 与 `MemorySyncWorker` 走同一个 [MemorySyncManager] 单例，并发由它内部的 Mutex 兜底
     * （第二个调用会拿到「已有同步正在进行中」）；这里只加一层 60 秒限频。
     */
    private suspend fun handleSync(): ToolResult {
        val settings = memorySyncSettings
        if (settings.repoUrl.isBlank()) {
            return ToolResult.Error("还没有填记忆仓库地址，同步无处可推。", "SYNC_NO_REPO")
        }
        val now = System.currentTimeMillis()
        val previous = lastSyncAttemptAt.get()
        if (previous > 0 && now - previous < SYNC_MIN_INTERVAL_MS) {
            val waitSeconds = (SYNC_MIN_INTERVAL_MS - (now - previous)) / 1000
            return ToolResult.Success(
                JsonPrimitive("刚同步过，${waitSeconds} 秒后可再试（同步是网络操作，不必连刷）。")
            )
        }
        lastSyncAttemptAt.set(now)
        val outcome = memorySyncManager.sync()
        val at = settings.lastSyncAt.takeIf { it > 0 }
            ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }
            .orEmpty()
        val summary = buildString {
            append(if (outcome.success) "同步成功" else "同步失败：${outcome.message}")
            if (outcome.success) {
                append("（收上 ${outcome.collected} 个文件")
                if (outcome.conflicts > 0) append("，${outcome.conflicts} 处冲突已保留双方")
                if (outcome.pendingDeletions.isNotEmpty()) append("，疑似被其他设备删除 ${outcome.pendingDeletions.size} 个")
                append("）")
            }
            if (at.isNotBlank()) append("；上次同步时间：$at")
        }
        val detail = syncDetailOf(outcome)
        val text = when {
            detail.isNotEmpty() -> "$summary\n\n$detail"
            outcome.success -> "$summary\n\n本轮没有收集到文件（本机正文都未变，或没有可同步的条目）。"
            else -> summary
        }
        return if (outcome.success) {
            ToolResult.Success(JsonPrimitive(text))
        } else {
            ToolResult.Error(text, "SYNC_FAILED")
        }
    }

    /**
     * 同步明细：收上了哪些文件、跳过了什么、哪里写盘失败。
     *
     * 只列真实拿得到的分类：collect 的内容未变跳过与写盘失败、distribute 的本机改动保留与补回失败。
     * 拿不到原因的分类一律不编——读者会拿它去查为什么某条记忆没同步上去。
     */
    private fun syncDetailOf(outcome: MemorySyncOutcome): String = buildString {
        val files = outcome.collectedFiles
        if (files.isNotEmpty()) {
            append("收上的文件（仓库内路径，最多列 $SYNC_DETAIL_LIST_LIMIT 个）：")
            files.take(SYNC_DETAIL_LIST_LIMIT).forEach { append("\n- $it") }
            if (files.size > SYNC_DETAIL_LIST_LIMIT) append("\n…等 ${files.size} 个")
        }
        val skipped = buildList {
            if (outcome.unchangedSkipped > 0) add("内容未变跳过 ${outcome.unchangedSkipped} 个")
            if (outcome.localKept > 0) add("本机有改动、保留本机 ${outcome.localKept} 个")
            if (outcome.failedWrites.isNotEmpty()) add("写入失败 ${outcome.failedWrites.size} 个")
        }
        if (skipped.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("跳过与失败：").append(skipped.joinToString("，"))
            outcome.failedWrites.take(SYNC_DETAIL_LIST_LIMIT).forEach { append("\n- $it") }
            if (outcome.failedWrites.size > SYNC_DETAIL_LIST_LIMIT) {
                append("\n…等 ${outcome.failedWrites.size} 个失败项")
            }
        }
    }

    private fun handleList(projectRoot: String?, scope: MemoryScope?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot, scope)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))

        // 列出全部（含事件类）：清单里见不到的条目得能在这里找到名字，否则无从 read 取回。
        // 标注 type，便于区分「跨会话仍成立的知识」与「某次事件的留档」。
        val list = memories.joinToString("\n") {
            "- ${it.name} (${it.scope.name.lowercase()}/${it.type.key}${if (it.pending) "/待确认" else ""}): ${it.description}"
        }
        return ToolResult.Success(JsonPrimitive("当前记忆列表：\n$list"))
    }

    /**
     * 按关键词检索记忆。
     *
     * 打分口径只有一套，就在 MemoryRepository 里（keywords > 名称 > 描述，带 IDF、CJK bigram
     * 与时间衰减）；这里只负责取候选与放宽重试，不再自带一套 substring 权重。
     *
     * 候选取自 [MemoryRepository.listMemories]，scope 非空时就是严格单作用域视图（同名不折叠），
     * 作用域筛选因此真正生效。
     *
     * 只返回摘要行，正文靠 read 取——检索结果不该把上下文塞满。
     */
    private fun handleSearch(args: Map<String, JsonElement>, projectRoot: String?, scope: MemoryScope?): ToolResult {
        val query = (args["query"] ?: args["name"])?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrEmpty()) return ToolResult.Error("search 操作需要 query 参数", "MISSING_QUERY")

        val limit = args["limit"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 8
        if (limit !in 1..50 || (args.containsKey("limit") && (args["limit"] as? JsonPrimitive)?.intOrNull == null)) {
            return ToolResult.Error("limit 必须为 1–50 的整数", "INVALID_LIMIT")
        }
        // 候选取全量后交给仓储层排序：IDF 加权 + 时间衰减，噪声（只有 bigram 碎片命中）在那边被挡掉。
        val candidates = memoryRepository.listMemories(projectRoot, scope)
        var matched = memoryRepository.searchMemories(query, candidates, limit)
        if (matched.isEmpty()) {
            // 一次不中就减词重试，不要直接向模型报「没有」——用户用的词往往比记忆里的长。
            val relaxed = relaxedQueryOf(query)
            if (relaxed.isNotBlank()) {
                FileLogger.i(TAG, "放宽检索：$query → $relaxed")
                matched = memoryRepository.searchMemories(relaxed, candidates, limit)
            }
        }

        if (matched.isEmpty()) {
            return ToolResult.Success(JsonPrimitive("没有匹配「$query」的记忆。可先用 action=list 看全部名称。"))
        }
        val text = matched.joinToString("\n") { m ->
            val kws = if (m.keywords.isEmpty()) "" else " [关键词: ${m.keywords.joinToString(", ")}]"
            // 过期的短期记忆不进注入，但仍在库里、能搜到：不标注的话模型会把它当现行知识用。
            val expired = if (memoryRepository.isExpired(m)) "/已过期" else ""
            "- ${m.name} (${m.scope.name.lowercase()}/${m.type.key}${if (m.pending) "/待确认" else ""}$expired): ${m.description}$kws"
        }
        return ToolResult.Success(
            JsonPrimitive("匹配「$query」的记忆（最多 $limit 条）：\n$text\n\n用 action=read + name=<名称> + scope=<作用域> 取完整正文。")
        )
    }

    /** 去掉最长的一个词重试一次；只剩一个词时返回空串，不再放宽。 */
    private fun relaxedQueryOf(query: String): String {
        val tokens = query.split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.size < 2) return ""
        val longest = tokens.indices.maxByOrNull { tokens[it].length } ?: return ""
        return tokens.filterIndexed { index, _ -> index != longest }.joinToString(" ")
    }

    /**
     * 使用统计：把「哪条从来没人读」振出来，并附上常驻正文预算的占用情况。
     *
     * 计数由 [MemoryUsageStore] 在 read 成功时累积（不写回记忆文件，避免刷 mtime 影响遗忘判定）；
     * 这里只做汇总，供归档决策参考。search 命中不计数——索引命中不等于真用过。
     */
    private fun handleStats(projectRoot: String?, scope: MemoryScope?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot, scope)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))
        val staleCutoff = System.currentTimeMillis() - STALE_DAYS * 24L * 60L * 60L * 1_000L
        val neverUsed = memories.filter { memoryUsageStore.useCount(it.scope.name, it.name) == 0 }
        val stale = memories.filter {
            val last = memoryUsageStore.lastUsedAt(it.scope.name, it.name)
            last == 0L || last < staleCutoff
        }
        val text = buildString {
            append("记忆使用统计：共 ${memories.size} 条，从未被 read 的 ${neverUsed.size} 条，$STALE_DAYS 天未读的 ${stale.size} 条。")
            if (memories.size > MEMORY_COUNT_SOFT_LIMIT) {
                append("\n提示：记忆已 ${memories.size} 条（软阀值 $MEMORY_COUNT_SOFT_LIMIT），而每轮清单上限只有 ${memoryRepository.injectedMemoryLimit()} 条")
                append("——大部分条目只占存储、不进上下文。建议把一次性内容写成 event（完全不进提示词），定期清理过期条目。")
            }
            if (neverUsed.isNotEmpty()) {
                append("\n\n从未取用（归档候选，最多列 30 条）：")
                neverUsed.take(30).forEach { append("\n- ${it.name}（${it.scope.name.lowercase()}/${it.type.key}）") }
                if (neverUsed.size > 30) append("\n…其余 ${neverUsed.size - 30} 条未列出")
            }
            append(inlineBudgetSection(projectRoot))
            append(injectListSection(projectRoot))
        }
        return ToolResult.Success(JsonPrimitive(text))
    }

    /**
     * 常驻正文预算的占用情况：哪些 identity 条目真进了系统提示的常驻段，哪些被预算挤回索引。
     *
     * 预算判定不在这里重写：候选取注入侧同一份（[MemoryRepository.listMemoriesForPrompt]），
     * 切分逻辑与两个上限常量全部复用 [MemoryPromptRenderer.inlineBudgetReport]，两边口径锁在一处。
     * 注入侧总是全局 + 项目一起排序、共用一份预算，所以这里不受 scope 参数影响。
     */
    /**
     * 本轮注入清单：全库几百条里只有前 N 条进了提示词，但以前只列「被挤出的」，看不到「入选的是谁」，
     * 排查「某条为什么没生效」只能靠反推。
     */
    private fun injectListSection(projectRoot: String?): String {
        val injected = memoryRepository.listMemoriesForPrompt(projectRoot)
        if (injected.isEmpty()) return ""
        val total = memoryRepository.listMemories(projectRoot).size
        val globalCount = injected.count { it.scope == MemoryScope.GLOBAL }
        val byType = injected.groupingBy { it.type.key }.eachCount()
            .entries.joinToString("、") { "${it.key} ${it.value}" }
        return buildString {
            append("\n\n注入清单（上限 ${memoryRepository.injectedMemoryLimit()} 条）：实际 ${injected.size} 条")
            append("（全局 $globalCount / 项目 ${injected.size - globalCount}；$byType）。")
            append("\n入选：${injected.joinToString("、") { it.name }}")
            append("\n未入选：${(total - injected.size).coerceAtLeast(0)} 条——这些连清单都进不去，只能靠 memory search 取回。")
        }
    }

    private fun inlineBudgetSection(projectRoot: String?): String {
        val report = MemoryPromptRenderer.inlineBudgetReport(
            // 统计要看全部候选，不能被注入上限截断：截断后口径会变成「前 60 条里有多少 identity」。
            memoryRepository.listMemoriesForPrompt(projectRoot, limit = Int.MAX_VALUE)
        )
        return buildString {
            append("\n\n常驻正文预算（identity 类，全局与项目共用一份：单条上限 ${report.singleEntryLimit} 字符、")
            append("总预算 ${report.totalLimit} 字符）：")
            if (report.identityTotal == 0) {
                append("当前没有 identity 条目，无需常驻。")
                return@buildString
            }
            append("identity 共 ${report.identityTotal} 条，实际常驻 ${report.inlined.size} 条，")
            append("被预算挤出 ${report.squeezed.size} 条。")
            if (report.squeezed.isNotEmpty()) {
                append("\n被挤出（最多列 $STATS_LIST_LIMIT 条；按无提问时的排序估算，实际注入顺序随会话提问重排，谁被挤出会变）：")
                report.squeezed.take(STATS_LIST_LIMIT).forEach { (name, reason) ->
                    append("\n- $name —— $reason")
                }
                if (report.squeezed.size > STATS_LIST_LIMIT) {
                    append("\n…其余 ${report.squeezed.size - STATS_LIST_LIMIT} 条未列出")
                }
                append("\n这些条目的正文不在本轮上下文里；要确认某条约定是否还算数，用 memory(action=read, name=...) 取全文。")
            }
            if (report.notBudgeted.isNotEmpty()) {
                append("\n另有 ${report.notBudgeted.size} 条 identity 不走常驻预算（不计入「被挤出」）：")
                report.notBudgeted.take(STATS_LIST_LIMIT).forEach { (name, reason) ->
                    append("\n- $name —— $reason")
                }
                if (report.notBudgeted.size > STATS_LIST_LIMIT) {
                    append("\n…其余 ${report.notBudgeted.size - STATS_LIST_LIMIT} 条未列出")
                }
            }
        }
    }

    /**
     * 回滚到最近一次归档快照。
     *
     * 覆盖写（save / edit / 恢复归档）之前都会自动留档，所以写错了直接 undo 即可，
     * 不需要用户去设置页的归档列表里翻。
     */
    private fun handleUndo(name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("undo 操作需要 name 参数", "MISSING_NAME")
        val restored = memoryRepository.restoreLatestArchive(name, scope, projectRoot)
            ?: return ToolResult.Error("「$name」没有可回滚的归档快照（或恢复失败）", "UNDO_UNAVAILABLE")
        return ToolResult.Success(JsonPrimitive("已把「$name」回滚到最近一次归档（$restored）。" +
            "本次只反滚这一个文件，同名条目的其它归档快照仍保留（要更早的版本就在归档列表里选）。"))
    }

    private fun handleRead(name: String?, scope: MemoryScope?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("read 操作需要 name 参数", "MISSING_NAME")
        val memory = memoryRepository.findMemory(name, projectRoot, scope)
            ?: return ToolResult.Error("未找到记忆「$name」", "MEMORY_NOT_FOUND")
        memoryRepository.recordUsage(memory.name, memory.scope, projectRoot)
        val notice = if (memory.pending) "待确认：此条记忆存在冲突，未经用户确认不得作为已确认约定或自动裁决。\n\n" else ""
        return ToolResult.Success(JsonPrimitive(notice + versionHeader(memory.version) + memory.content))
    }

    /**
     * 正文前挂一行版本号。模型必须能从 read 的返回值里拿到乐观锁基准，
     * 否则它只能盲写，版本校验就退化成「凡是覆盖一律拒绝」。
     */
    private fun versionHeader(version: String): String =
        if (version.isBlank()) ""
        else "version: $version（覆盖或编辑本条时把这个值作为 version 参数传回；不传而条目已存在会被拒绝）\n\n"

    /**
     * 版本冲突时回给模型的正文。
     *
     * 必须连最新版本与最新正文一起给：只说一句「冲突了」，模型只能再 read 一次，白白多一轮；
     * 给了正文它就能在同一轮里把两边合并、带上 current_version 重试，这正是乐观锁要的效果。
     */
    private fun versionConflictMessage(
        name: String,
        currentVersion: String,
        currentContent: String,
        missing: Boolean = false
    ): String = buildString {
        append("记忆「$name」在你读取后被改动过，本次未写入——乐观锁拦下了这次覆盖，避免盖掉别人的更新。")
        if (missing) {
            append("\ncurrent_version: （文件已不存在，可能已被别处删除）")
            append("\n下一步：如果仍要保留这条记忆，把 version 参数设成 \"${MemorySource.VERSION_NEW}\" 重新调用 save。")
        } else {
            append("\ncurrent_version: $currentVersion")
            append("\ncurrent_content:\n<<<\n").append(currentContent).append("\n>>>")
            append("\n下一步：把你要写的内容与上面的最新正文合并（务必保留其中别人新增的信息），")
            append("然后用同一个 name 加上 version=\"$currentVersion\" 重新调用；")
            append("若两边说法真正冲突、无法合并，先向用户说明再决定，不要二者选一直接覆盖。")
        }
    }

    private fun handleSave(
        args: Map<String, JsonElement>,
        name: String?,
        scope: MemoryScope,
        projectRoot: String?,
        sessionId: String?
    ): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("save 操作需要 name 参数", "MISSING_NAME")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 description 参数", "MISSING_DESCRIPTION")
        if (description.isEmpty()) {
            return ToolResult.Error(
                "摘要不能为空，摘要要包含正文里的关键实体（域名/IP/端口/路径等）；" +
                    "空摘要的条目不会进注入清单，之后也检索不出来。",
                "EMPTY_DESCRIPTION"
            )
        }
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 content 参数", "MISSING_CONTENT")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法保存项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        val type = MemoryType.from(args["type"]?.jsonPrimitive?.contentOrNull)
        val ttlDays = args["ttl_days"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
        val expiresAt = ttlDays?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() + it * 24L * 60L * 60L * 1_000L }
        val existing = memoryRepository.findMemory(name, projectRoot, scope)
        val version = args["version"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        // 没带版本却要覆盖已有条目：直接拒绝，并把最新正文回给模型让它合并后重试。
        // 旧行为是盲写覆盖——模型上下文里的内容常常已经过期，一写就把后台整理/用户手改的更新抹掉了。
        if (existing != null && version == null) {
            return ToolResult.Error(
                versionConflictMessage(existing.name, existing.version, existing.content),
                VERSION_CONFLICT_CODE
            )
        }
        val result = memoryRepository.writeMemory(
            name = name,
            description = description,
            content = content,
            scope = scope,
            projectRoot = projectRoot,
            type = type,
            expiresAt = expiresAt,
            // 新建按「要求文件不存在」写：查找与写盘之间被别人插了一条同名记忆时能被拦住。
            expectedVersion = version ?: MemorySource.VERSION_NEW
        )
        when (result) {
            is MemoryWriteResult.VersionConflict -> return ToolResult.Error(
                versionConflictMessage(result.name, result.currentVersion, result.currentContent, result.missing),
                VERSION_CONFLICT_CODE
            )
            is MemoryWriteResult.Failure -> return ToolResult.Error("保存记忆失败：${result.message}", result.code)
            MemoryWriteResult.Success -> Unit
        }
        val existed = existing != null

        // 重名（意同）是记忆库最大的噪声来源：同一条事实被不同名字反复记下，检索时互相抢位。
        // 只提醒不阻断——写进去的内容本身没毛病，模型拿到提示后可以立刻用 edit 合并。
        val similar = memoryRepository.findSimilarMemories(name, description, projectRoot, scope = scope)
        val detail = buildList {
            if (existed) add("覆盖已有记忆（旧版已归档）")
            if (similar.isNotEmpty()) add("命中相近记忆：" + similar.joinToString("、") { it.name })
        }.joinToString("；").takeIf { it.isNotEmpty() }
        memoryTrace.record(
            source = MemoryTraceLog.SOURCE_TOOL,
            action = if (existed) MemoryTraceLog.ACTION_SAVE else MemoryTraceLog.ACTION_NEW,
            name = name,
            scope = scope.name.lowercase(),
            detail = detail,
            sessionId = sessionId
        )

        val hint = if (similar.isEmpty()) {
            ""
        } else {
            " 注意：库中已有相近记忆 " +
                similar.joinToString("、") { "「${it.name}」（${it.description}）" } +
                "。若这次写的是同一条事实，请改用 action=edit 就地修改，别再新建条目。"
        }
        val stored = memoryRepository.findMemory(name, projectRoot, scope)
        // 把新版本一并给回：模型接下来往往还要改同一处，带上它就不用再 read 一次。
        val versionLine = stored?.version?.takeIf { it.isNotBlank() }
            ?.let { "当前 version: $it（再次修改请作为 version 参数传回）。" }
            .orEmpty()
        val typeNote = if (stored?.pending == true) "它仍待确认，需保留冲突双方，不能作为已确认约定。" else when (stored?.type ?: type) {
            MemoryType.IDENTITY -> "它属于长期约定，正文会在下一次会话启动时直接进系统提示词常驻（无需再 read）。"
            MemoryType.EVENT -> "它被标为一次性事件：只留档，不进注入清单，需要时用 search / read 取回。"
            else -> "它将在下一次会话启动时自动注入摘要。"
        }
        return ToolResult.Success(
            JsonPrimitive(
                "已成功保存记忆「$name」到 ${scope.name.lowercase()} 作用域。$typeNote" +
                    "$versionLine" + "当前会话若需立即使用，请通过 read 操作读取。$hint"
            )
        )
    }

    private fun handleEdit(
        args: Map<String, JsonElement>,
        name: String?,
        scope: MemoryScope,
        projectRoot: String?,
        sessionId: String?
    ): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("edit 操作需要 name 参数", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("edit 操作需要 edits 参数：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法编辑项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        val version = args["version"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot, version)) {
            is MemoryEditResult.Success -> {
                memoryTrace.record(
                    source = MemoryTraceLog.SOURCE_TOOL,
                    action = MemoryTraceLog.ACTION_EDIT,
                    name = name,
                    scope = scope.name.lowercase(),
                    detail = "局部编辑 ${edits.size} 处",
                    sessionId = sessionId
                )
                ToolResult.Success(JsonPrimitive("已成功编辑记忆「$name」的正文（${scope.name.lowercase()} 作用域）。"))
            }
            is MemoryEditResult.NotFound ->
                ToolResult.Error("未找到记忆「${result.name}」，请先通过 save 创建，或确认 name 与作用域是否正确。", "MEMORY_NOT_FOUND")
            is MemoryEditResult.VersionConflict ->
                ToolResult.Error(
                    versionConflictMessage(result.name, result.currentVersion, result.currentContent),
                    VERSION_CONFLICT_CODE
                )
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?, sessionId: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("delete 操作需要 name 参数", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            memoryTrace.record(
                source = MemoryTraceLog.SOURCE_TOOL,
                action = MemoryTraceLog.ACTION_DELETE,
                name = name,
                scope = scope.name.lowercase(),
                sessionId = sessionId
            )
            ToolResult.Success(JsonPrimitive("已成功删除 ${scope.name.lowercase()} 作用域的记忆「$name」。"))
        } else {
            ToolResult.Error("删除失败，记忆「$name」可能不存在于该作用域。", "DELETE_FAILED")
        }
    }

    /** 追加每日日志（Aharou 记忆：按天归档的流水笔记）。 */
    private fun handleDailyLog(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("log 操作需要 content 参数", "MISSING_CONTENT")
        val file = aharouMemory.appendDailyLog(content)
        return ToolResult.Success(JsonPrimitive("已写入每日日志：${file.name}"))
    }

    /** 追加一条事实（Aharou 记忆：结构化事实库）。 */
    private fun handleFact(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("fact 操作需要 content 参数", "MISSING_CONTENT")
        if (!aharouMemory.appendFact(content, source = "agent")) {
            return ToolResult.Error("写入事实库失败，请查看日志。", "FACT_WRITE_FAILED")
        }
        return ToolResult.Success(JsonPrimitive("已写入事实库。"))
    }

    /** 追加心流（顺滑记录当下的想法/情绪）。 */
    private fun handleMindstream(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return ToolResult.Error("mindstream 操作需要 content 参数", "MISSING_CONTENT")
        if (!aharouMemory.appendMindstream(content)) {
            return ToolResult.Error("写入心流失败，请查看日志。", "MINDSTREAM_WRITE_FAILED")
        }
        return ToolResult.Success(JsonPrimitive("已写入心流。"))
    }

    /** 读/写核心档案（CORE.md）：带 content = 覆盖写入；不带 = 读取。 */
    private fun handleCore(args: Map<String, JsonElement>): ToolResult {
        val content = args["content"]?.jsonPrimitive?.contentOrNull
        if (content.isNullOrEmpty()) {
            val current = aharouMemory.readCore().orEmpty()
            return ToolResult.Success(JsonPrimitive(if (current.isEmpty()) "（核心档案为空）" else current))
        }
        aharouMemory.writeCore(content)
        return ToolResult.Success(JsonPrimitive("已更新核心档案。"))
    }
}
