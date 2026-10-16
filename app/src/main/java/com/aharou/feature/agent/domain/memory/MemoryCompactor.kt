package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.util.FileLogger
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
        return MemoryFileStorage.write(target, payload)
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
        const val AUTO_COMPACT_THRESHOLD = 150

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
