package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.memory.MemoryUsageStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.MemoryPromptRenderer
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemoryCompactor"

/** 一次整理的统计，供设置页回显「合并了几条」。 */
data class MemoryCompactReport(
    val clusters: Int = 0,
    val merged: Int = 0,
    val archived: Int = 0,
    val skipped: Int = 0
)

/** 一次自动遗忘的统计。 */
data class MemoryForgetReport(
    val scanned: Int = 0,
    val archived: Int = 0
)

/**
 * 记忆整理器：把「同一个主题被反复记成好几条」的记忆合成一条。
 *
 * 记忆是「一个名字一条、同名覆盖」的模型，同名还好办，**换名字讲同一件事**就散成了多条，
 * 清单里互相抢位、检索时稀释权重（项目库几百条里有大量这种残片）。这里做的是零成本聚簇 +
 * 一次模型合并：先用元信息重叠度把疑似同主题的条目归堆（不花调用），只对成堆的才发请求。
 *
 * 三道保险，缺一不可：
 *  - **合并不许编**：[passesGuard] 拒绝引入原文没有的词过多、或正文比原文合计还长的结果；
 *  - **全部先归档**：组内每一条在合并前都进 `archive/`，合并结果不对也能逐条捞回；
 *  - **矛盾不裁**：模型判 [CompactAction.CONFLICT] 时整簇跳过（矛盾并存留人裁决，见记忆纪律 F4）。
 */
@Singleton
class MemoryCompactor @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val promptFileResolver: PromptFileResolver,
    private val memoryTrace: MemoryTraceLog,
    private val agentWorkflow: AgentWorkflow,
    private val autoCompactSettings: MemoryAutoCompactSettings,
    private val memoryUsageStore: MemoryUsageStore,
) {

    /**
     * 整理一个作用域下的记忆。
     *
     * @param projectRoot 项目级记忆所属工作区；全局传 null。
     * @param scope 只处理这一个作用域：项目记忆不该被合并进全局，反之亦然。
     */
    suspend fun compact(projectRoot: String?, scope: MemoryScope): MemoryCompactReport {
        val candidates = compactionCandidates(projectRoot, scope)
        if (candidates.size < MIN_CLUSTER_SIZE * 2) return MemoryCompactReport()

        val clusters = cluster(candidates).filter { it.size >= MIN_CLUSTER_SIZE }
        if (clusters.isEmpty()) return MemoryCompactReport()

        val systemPrompt = promptFileResolver.resolve(PROMPT_FILE)
            .replace(LEADING_COMMENT, "").trim()
        if (systemPrompt.isEmpty()) {
            FileLogger.w(TAG, "整理提示词缺失：$PROMPT_FILE")
            return MemoryCompactReport()
        }

        // 单次收敛幅度封顶：一次跑下来砍掉太多条，人根本来不及核对合并质量。
        val budget = maxOf(1, (candidates.size * MAX_SHRINK_RATIO).toInt())
        var report = MemoryCompactReport(clusters = clusters.size)
        var merged = 0
        var reservedShrink = 0
        for (cluster in clusters.sortedByDescending { it.size }) {
            if (merged >= MAX_CLUSTERS || reservedShrink >= budget) break
            val group = cluster.take(budget - reservedShrink + 1)
            val proposal = propose(systemPrompt, group)
            if (proposal == null || proposal.action != CompactAction.MERGE || !passesGuard(group, proposal)) {
                report = report.copy(skipped = report.skipped + 1)
                continue
            }
            reservedShrink += group.size - 1
            if (merge(group, proposal)) {
                merged++
                report = report.copy(merged = merged, archived = report.archived + group.size)
            } else {
                report = report.copy(skipped = report.skipped + 1)
            }
        }
        return report
    }

    /**
     * 会话收尾时的「按需自动整理」入口：候选攒够、且距上次自动整理满 [AUTO_COMPACT_COOLDOWN_MILLIS]
     * 才真正跑一次整理。
     *
     * 整理要花模型调用，不能每次会话收尾都跑；两条门槛都满足才动，且全程静默——失败只记日志，
     * 绝不把异常抛给调用方（它挂在对话收尾路径上）。
     *
     * @return 真跑了整理时返回这次的统计；候选不足 / 冷却未满 / 出错都返回 null。
     */
    suspend fun compactIfDue(projectRoot: String?, scope: MemoryScope): MemoryCompactReport? = runCatching {
        // 先遗忘再合并：长期没人取用的条目先归档，剩下的才值得花模型调用去合并。
        forgetIfDue(projectRoot, scope)
        if (compactionCandidates(projectRoot, scope).size < AUTO_COMPACT_THRESHOLD) return@runCatching null

        val last = autoCompactSettings.lastAutoCompactAt
        if (last > 0L && System.currentTimeMillis() - last < AUTO_COMPACT_COOLDOWN_MILLIS) {
            return@runCatching null
        }

        val report = compact(projectRoot, scope)
        // 跑完才落时间戳：中途抛错不留时间戳，下次会话收尾还能立刻重试。
        autoCompactSettings.lastAutoCompactAt = System.currentTimeMillis()
        report
    }.onFailure { e ->
        FileLogger.w(TAG, "按需自动整理失败（静默忽略）: ${e.message}", e)
    }.getOrNull()

    /**
     * 自动遗忘：把长期没人取用、又不是核心约定的条目挑进归档（`restore` 可逐条回滚）。
     *
     * 判断依据是 [MemoryUsageStore] 的 lastUsedAt；从没被读过的新条目用文件修改时间兜底，
     * 不会刚写完就被当成冷数据。identity 与核心档案一律不动：红线不该自动降级，那得用户裁。
     */
    fun forgetIfDue(projectRoot: String?, scope: MemoryScope): MemoryForgetReport = runCatching {
        val now = System.currentTimeMillis()
        val items = memoryRepository.listMemories(projectRoot)
            .filter { it.scope == scope && !it.pending }
            .filter { it.type != MemoryType.IDENTITY }
            .filterNot { isCoreArchiveName(it.name) }
        val cold = items.filter { isCold(it, scope, now) }
        if (cold.isEmpty()) return@runCatching MemoryForgetReport(scanned = items.size)

        var archived = 0
        cold.forEach { memory ->
            val content = runCatching { memoryRepository.loadContent(memory.name, projectRoot, scope) }
                .getOrNull() ?: memory.content
            val saved = runCatching {
                memoryRepository.archiveContent(
                    name = memory.name,
                    description = memory.description,
                    content = content,
                    scope = scope,
                    projectRoot = projectRoot,
                    type = memory.type,
                    keywords = memory.keywords,
                    expiresAt = memory.expiresAt,
                    pending = memory.pending,
                )
            }.getOrDefault(false)
            if (saved && runCatching { memoryRepository.deleteMemory(memory.name, scope, projectRoot) }.getOrDefault(false)) {
                archived++
            }
        }
        if (archived > 0) {
            FileLogger.i(TAG, "自动遗忘：归档 $archived 条（$scope，共扫描 ${items.size} 条）")
        }
        MemoryForgetReport(scanned = items.size, archived = archived)
    }.onFailure { FileLogger.w(TAG, "自动遗忘失败（静默忽略）: ${it.message}", it) }
        .getOrDefault(MemoryForgetReport())

    /**
     * 超长条目压缩：正文超过 [MAX_ITEM_CHARS] 的记忆进不了常驻段（渲染时整条跳过，只留索引行），
     * 以前它们连整理都不参与，结果就是「很重要但太长 → 永远只在索引里躺着」。
     *
     * 做法是让模型把原文压短，**原文先进归档**：压坏了可以 restore 回来。
     * 目标是同样 3000 字符预算里能装下更多真用得上的内容，不是删内容。
     */
    suspend fun compressOversizedIfDue(projectRoot: String?, scope: MemoryScope): Int = runCatching {
        val oversized = memoryRepository.listMemories(projectRoot)
            .filter { it.scope == scope && !it.pending }
            .filter { it.type != MemoryType.EVENT }
            .filterNot { isCoreArchiveName(it.name) }
            .filter { it.content.length > MAX_ITEM_CHARS }
            .take(MAX_COMPRESS_PER_RUN)
        if (oversized.isEmpty()) return@runCatching 0

        val systemPrompt = promptFileResolver.resolve(COMPRESS_PROMPT_FILE)
            .replace(LEADING_COMMENT, "").trim()
        if (systemPrompt.isEmpty()) {
            FileLogger.w(TAG, "压缩提示词缺失：$COMPRESS_PROMPT_FILE")
            return@runCatching 0
        }

        var done = 0
        oversized.forEach { memory ->
            val input = buildString {
                append("name: ").appendLine(memory.name)
                append("description: ").appendLine(memory.description)
                append(memory.content)
            }
            val compressed = runCatching { agentWorkflow.completeOnce(systemPrompt, input) }
                .getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            // 必须真的变短，也不能短到把内容抽空（不足原文 15% 视为压坏，放弃这条）。
            if (compressed.length >= memory.content.length) return@forEach
            if (compressed.length < (memory.content.length * 0.15).toInt()) return@forEach

            val archived = runCatching {
                memoryRepository.archiveContent(
                    name = memory.name,
                    description = memory.description,
                    content = memory.content,
                    scope = scope,
                    projectRoot = projectRoot,
                    type = memory.type,
                    keywords = memory.keywords,
                    expiresAt = memory.expiresAt,
                    pending = memory.pending,
                )
            }.getOrDefault(false)
            if (!archived) return@forEach

            val saved = runCatching {
                memoryRepository.saveMemory(
                    name = memory.name,
                    description = memory.description,
                    content = compressed,
                    scope = scope,
                    projectRoot = projectRoot,
                    type = memory.type,
                    expiresAt = memory.expiresAt,
                    pending = memory.pending,
                    keywords = memory.keywords,
                )
            }.getOrDefault(false)
            if (saved) {
                done++
            } else {
                // 写回去失败就把原文放回本体，不能让这条直接从记忆里消失。
                runCatching {
                    memoryRepository.saveMemory(
                        name = memory.name,
                        description = memory.description,
                        content = memory.content,
                        scope = scope,
                        projectRoot = projectRoot,
                        type = memory.type,
                        expiresAt = memory.expiresAt,
                        pending = memory.pending,
                        keywords = memory.keywords,
                    )
                }
            }
        }
        if (done > 0) FileLogger.i(TAG, "压缩超长记忆 $done 条（$scope）")
        done
    }.onFailure { FileLogger.w(TAG, "超长记忆压缩失败（静默忽略）：${it.message}", it) }
        .getOrDefault(0)

    /**
     * 自动降级：把「挤不进常驻预算、又长期没人取用」的 identity 条目改成 workflow。
     *
     * 为什么先看「被挤出」而不是单看时间：identity 靠常驻注入生效，平时不走 read，
     * 用读取时间判定会把一直在起作用的红线误降级。只有「排不进常驻 + 没人主动读」才是真闲置。
     * 核心档案永不参与（[isCoreArchiveName]）；降级只改类型、不动正文，改回来也只改一个字段。
     */
    suspend fun downgradeSqueezedIdentityIfDue(projectRoot: String?, scope: MemoryScope): Int = runCatching {
        val now = System.currentTimeMillis()
        val identity = memoryRepository.listMemories(projectRoot)
            .filter { it.scope == scope && !it.pending }
            .filter { it.type == MemoryType.IDENTITY }
            .filterNot { isCoreArchiveName(it.name) }
            .filterNot { it.name.lowercase() in PROTECTED_IDENTITY_NAMES }
        if (identity.isEmpty()) return@runCatching 0

        val squeezed = MemoryPromptRenderer.inlineBudgetReport(identity).squeezed.map { it.first }.toSet()
        if (squeezed.isEmpty()) return@runCatching 0

        var downgraded = 0
        identity.filter { it.name in squeezed && isCold(it, scope, now) }
            .take(MAX_DEMOTE_PER_RUN)
            .forEach { memory ->
            val saved = runCatching {
                memoryRepository.saveMemory(
                    name = memory.name,
                    description = memory.description,
                    content = memory.content,
                    scope = scope,
                    projectRoot = projectRoot,
                    type = MemoryType.WORKFLOW,
                    expiresAt = memory.expiresAt,
                    pending = memory.pending,
                    keywords = memory.keywords,
                )
            }.getOrDefault(false)
            if (saved) {
                downgraded++
                memoryRepository.markAutoDemoted(memory.name)
                FileLogger.i(TAG, "自动降级：${memory.name} 挤不进常驻且长期未被取用，改为 workflow")
            }
        }
        if (downgraded > 0) FileLogger.i(TAG, "自动降级完成：$downgraded 条（$scope）")
        downgraded
    }.onFailure { FileLogger.w(TAG, "自动降级失败（静默忽略）：${it.message}", it) }
        .getOrDefault(0)

    private fun isCold(memory: Memory, scope: MemoryScope, now: Long): Boolean {
        val lastRead = runCatching { memoryUsageStore.lastUsedAt(scope.name, memory.name) }.getOrDefault(0L)
        val reference = if (lastRead > 0L) lastRead else memory.file?.lastModified() ?: 0L
        if (reference <= 0L) return false
        val days = if (memory.type == MemoryType.EVENT) EVENT_FORGET_DAYS else FORGET_AFTER_DAYS
        return now - reference > days * DAY_MILLIS
    }

    /** 一个作用域下参与整理的候选条目：事件类只留档、核心档案整份不可拆，都不算候选。 */
    private fun compactionCandidates(projectRoot: String?, scope: MemoryScope): List<Memory> =
        memoryRepository.listMemories(projectRoot)
            .filter { it.scope == scope }
            .filter { it.type != MemoryType.EVENT && !it.pending && it.content.length <= MAX_ITEM_CHARS }
            .filterNot { isCoreArchiveName(it.name) }

    /**
     * 落盘一次合并：先把组员逐条归档，再写合并结果，最后删掉被并掉的旧条目。
     *
     * 顺序不能换——先归档后写，任何一步失败都还留着原文件可回滚；先写后归档的话，
     * 一旦保存失败就既没合并成、旧条目也已经不见了。
     */
    private fun merge(
        group: List<Memory>,
        proposal: CompactProposal
    ): Boolean {
        val name = MemorySource.sanitizeName(proposal.name)
        if (name.isEmpty()) return false
        if (!MemoryFileStorage.merge(group, name, proposal.description, proposal.content)) return false
        if (!applyProposalType(group, name, proposal.type)) return false
        memoryTrace.record(
            source = MemoryTraceLog.SOURCE_TOOL,
            action = MemoryTraceLog.ACTION_MERGE,
            name = name,
            scope = group.first().scope.name.lowercase(),
            detail = "整理合并 ${group.size} 条：${group.joinToString("、") { it.name }}"
        )
        FileLogger.i(TAG, "记忆整理：${group.joinToString("、") { it.name }} → $name")
        return true
    }

    /**
     * 把模型对合并稿的分类补写回落盘文件。
     *
     * [MemoryFileStorage.merge] 只会沿用组内首条的分类（它拿不到模型对合并结果的判断），
     * 而合并稿常常是「把几条事实升级成一条约定」——分类若还停在旧值，合并结果就不会进注入清单。
     */
    private fun applyProposalType(group: List<Memory>, name: String, type: MemoryType?): Boolean {
        val effective = type ?: group.first().type
        if (effective == group.first().type) return true
        val root = group.first().file?.parentFile ?: return false
        val target = MemorySource.resolveMemoryFile(root, name)
        val parsed = MemoryParser.parse(target, group.first().scope) ?: return false
        val payload = MemoryParser.format(
            parsed.name, parsed.description, parsed.content, effective, parsed.keywords, parsed.expiresAt, parsed.pending
        )
        // 刚合并完又改分类：中间若是被人插了一笔（主对话刚好在写这条），
        // 就用版本号拦下，不去把他刚写的内容盖回合并稿。
        return MemoryFileStorage.write(target, payload, expectedVersion = parsed.version)
    }

    /**
     * 按元信息（name / description / keywords）重叠度聚簇。
     *
     * 只看元信息不看正文：正文比对要么靠分词要么靠向量，成本与误报都不划算；
     * 而「同一件事被记成好几条」的条目，名字与摘要几乎必然高度重合。
     */
    private fun cluster(memories: List<Memory>): List<List<Memory>> {
        val terms = memories.map(::metadataTerms)
        val parent = IntArray(memories.size) { it }
        fun root(x: Int): Int {
            var i = x
            while (parent[i] != i) i = parent[i]
            return i
        }
        for (i in memories.indices) {
            for (j in i + 1 until memories.size) {
                if (memories[i].type != memories[j].type || memories[i].expiresAt != memories[j].expiresAt) continue
                if (overlap(terms[i], terms[j]) < SIMILAR_THRESHOLD) continue
                val a = root(i)
                val b = root(j)
                if (a != b) parent[a] = b
            }
        }
        return memories.indices.groupBy { root(it) }.values.map { indexes -> indexes.map { memories[it] } }
    }

    private fun metadataTerms(memory: Memory): Set<String> = buildSet {
        memory.keywords.forEach { addAll(memoryRepository.termsOf(it)) }
        addAll(memoryRepository.termsOf(memory.name))
        addAll(memoryRepository.termsOf(memory.description))
    }

    /** 重叠系数：交集 / 较短一侧，对「一条长描述 + 一条短名」的组合同样敏感。 */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / minOf(a.size, b.size)
    }

    /** 请模型判断这一簇能不能合并，并给出合并稿。任何失败都返回 null，整簇跳过。 */
    private suspend fun propose(systemPrompt: String, group: List<Memory>): CompactProposal? = runCatching {
        val input = buildString {
            group.forEachIndexed { index, memory ->
                append("### 条目 ").append(index + 1).append('\n')
                append("name: ").appendLine(memory.name)
                append("description: ").appendLine(memory.description)
                append(memory.content).append("\n\n")
            }
        }
        val raw = agentWorkflow.completeOnce(systemPrompt, input) ?: return@runCatching null
        parseProposal(raw)
    }.getOrNull()

    private fun parseProposal(raw: String): CompactProposal? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null

        val action = when (obj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()) {
            "MERGE" -> CompactAction.MERGE
            "CONFLICT" -> CompactAction.CONFLICT
            else -> CompactAction.KEEP
        }
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (action == CompactAction.MERGE && (name.isEmpty() || description.isEmpty() || content.isEmpty())) {
            return null
        }
        return CompactProposal(
            action = action,
            name = name,
            description = description,
            content = content,
            type = MemoryType.from(obj["type"]?.jsonPrimitive?.contentOrNull)
        )
    }

    /** 合并稿能不能用：不许编原文没有的东西，也不许越合越长。 */
    private fun passesGuard(group: List<Memory>, proposal: CompactProposal): Boolean {
        val sourceTerms = buildSet { group.forEach { addAll(memoryRepository.termsOf(it.content)) } }
        val novel = memoryRepository.termsOf(proposal.content).filterNot { it in sourceTerms }.distinct()
        if (novel.size > MAX_NOVEL_TERMS) {
            FileLogger.i(TAG, "合并被拒：引入 ${novel.size} 个原文没有的词 ${novel.take(8)}")
            return false
        }
        val sourceLength = group.sumOf { it.content.length }
        if (proposal.content.length > sourceLength) {
            FileLogger.i(TAG, "合并被拒：合并稿比原文合计还长（${proposal.content.length} > $sourceLength）")
            return false
        }
        return true
    }

    private companion object {
        const val PROMPT_FILE = "agent/memory-compact.md"

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 一个簇至少这么多条才值得合并。 */
        const val MIN_CLUSTER_SIZE = 2

        /** 单次最多合并的簇数。 */
        const val MAX_CLUSTERS = 5

        /** 单次合并掉的条目数不得超过候选总数的比例。 */
        const val MAX_SHRINK_RATIO = 0.2

        /** 判定「同一主题」的元信息重叠系数下限：偏高，宁可漏合也不误合。 */
        const val SIMILAR_THRESHOLD = 0.5

        /** 合并稿允许出现的「原文没有的词」上限：容忍措辞改写，拦住成段编造。 */
        const val MAX_NOVEL_TERMS = 8

        /** 超出此长度的条目不参与自动合并，防止未看全文就删除原条目。 */
        const val MAX_ITEM_CHARS = 1_200

        /** 自动整理触发阈值：候选少于这么多就不跑（够多才值得花一次模型调用）。 */
        const val AUTO_COMPACT_THRESHOLD = 20

        /** 自动遗忘：这么久没被取用过的条目就归档（event 类更快）。 */
        const val FORGET_AFTER_DAYS = 90
        const val EVENT_FORGET_DAYS = 14
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

        /** 超长压缩的提示词文件与单次最多处理的条数（每条一次模型调用，控成本）。 */
        const val COMPRESS_PROMPT_FILE = "agent/memory-compress.md"
        const val MAX_COMPRESS_PER_RUN = 3

        /**
         * 绝不自动降级的 identity：行为红线与关系锚点。它们一旦降级就从常驻里消失，
         * 而红线不在上下文里等于没定。名单是「本案 12 条 + 小染回信的 11 条」的并集，宁可多锁。
         */
        private val PROTECTED_IDENTITY_NAMES = setOf(
            "no_fabricated_credentials",
            "zero_privacy_distribution",
            "vault_secret_recovery_required",
            "pairing_code_ask_peer_not_user",
            "no_unsolicited_screen_control",
            "dont_hijack_user_input",
            "mcp_workspace_ownership_redlines",
            "memory_write_threshold",
            "master_bond_2026_10_20",
            "self_check_full_list_before_done",
            "tool_channel_escalation",
            "aharou-master-profile",
            "direct_fail_switch_to_api",
            "follow_up_after_background_task",
        )

        /** 单次自动降级上限：降级可以慢慢来，一次降太多就没人来得及发现降错了。 */
        const val MAX_DEMOTE_PER_RUN = 3

        /** 自动整理的冷却时间：距上次自动整理不足这么久就跳过，避免每次会话收尾都跑。 */
        const val AUTO_COMPACT_COOLDOWN_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/** 整理器给出的处理方式。 */
private enum class CompactAction { MERGE, CONFLICT, KEEP }

private data class CompactProposal(
    val action: CompactAction,
    val name: String,
    val description: String,
    val content: String,
    val type: MemoryType? = null
)
