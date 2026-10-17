package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryUsageStore
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import javax.inject.Inject
import kotlin.math.ln
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot,
    private val memoryUsageStore: MemoryUsageStore
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?, scope: MemoryScope? = null): List<Memory> {
        if (scope != null) return listMemoriesInScope(scope, projectRoot)
        return (listMemoriesInScope(MemoryScope.GLOBAL, projectRoot) +
            listMemoriesInScope(MemoryScope.PROJECT, projectRoot))
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    fun listMemoriesInScope(scope: MemoryScope, projectRoot: String?): List<Memory> =
        when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.listMemories()
            MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }
                ?.let { projectSource(it).listMemories() } ?: emptyList()
        }.filterNot { it.name.startsWith("LOG-") }

    fun findMemory(name: String, projectRoot: String?, scope: MemoryScope? = null): Memory? =
        listMemories(projectRoot, scope).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * 当前作用域（全局 + 项目，去重后）的记忆总条数。
     *
     * 供注入清单末尾的规模反馈：清单有 60 条硬上限，模型得知道它看到的只是一部分抽样，
     * 其余条目要靠 memory(action=search) 取回。
     */
    fun countMemories(projectRoot: String?): Int = listMemories(projectRoot).size

    /**
     * 专供系统提示词注入的清单：在 [listMemories] 基础上做「相关度排序 + 降级遗忘 + 有界截断」。
     *
     * 与 [listMemories] 分开，是因为后者还供 `memory(action=list)` 用——那里必须给出**完整**视图，
     * 否则截掉的条目模型连名字都看不到，也就无从 `read` 取回，等于真丢了。
     * 注入这条路则相反：只增不减的记忆每轮都挤占上下文，必须会忘。
     *
     * 遗忘只降级不删除：文件原样留着，模型需要时仍能 read/edit/delete。
     *
     * 排序（[query] 非空时）按与当前任务的相关度降序，权重这么定的理由：
     * - 对记忆的 keywords / name / description 做纯字符串命中计数，不引入分词或向量库；
     * - keywords 是专为检索抽取的短词，噪音最低，命中权重最高（3）；name 是人工起的稳定
     *   标识，次之（2）；description 是散文，越长的描述越容易碰巧命中，故权重最低（1）且
     *   单条命中数封顶，避免长描述凭字数压过精心填写的 keywords；
     * - 中文没有空格分词，一段 CJK 短语整串几乎不可能原样出现在用户句子里，故对 CJK 词额外
     *   拆成字符二元组（bigram）参与命中，让「代码调研」这类词在「帮我做代码调研」里也能命中；
     * - 相关度全为 0（含 [query] 为空）时退化为按文件 mtime 倒序，与旧行为一致。
     *
     * 排序稳定：相关度 → mtime 倒序 → name 升序，三层都确定，保证同一 (projectRoot, query)
     * 每次渲染顺序一致，不因目录扫描顺序抖动，避免无谓地打断 KV Cache。
     */
    fun listMemoriesForPrompt(projectRoot: String?, query: String? = null): List<Memory> {
        val normalizedQuery = query?.trim()?.lowercase().orEmpty()
        return listMemories(projectRoot)
            // 事件类（某次测试结果、某天的排查记录）只留档：它们不是跨会话仍成立的知识，
            // 留在清单里只会把真正该被记住的东西挤淡。需要时靠 memory(action=search/read) 取回。
            .filter { it.type.injected }
            // 短期记忆过期即不再进清单：文件留着，需要时仍可 read/edit，不必人工清理。
            .filterNot { isExpired(it) }
            // 没有一句话摘要的条目不算「摘要式记忆」：注入它的名字等于白占一行，
            // 使用者看到名字也无从判断要不要读。
            // 但核心档案（CORE / GLOBAL / SOUL / OPS 等）是按文件名直接读的，本来就没有
            // frontmatter，若一并滤掉等于把全局约定和身份直接从提示词里除名。
            .filter { it.description.isNotBlank() || isCoreArchiveName(it.name) }
            .filterNot { isStale(it) }
            // 先算一次分再排序：写在 comparator 里会随比较次数反复重算、反复切词。
            // 打分依赖全库的词频统计（IDF），所以先把候选集整好、算一次文档频数，再逐条打分。
            .let { candidates ->
                val frequencies = documentFrequencies(candidates)
                candidates.map { memory ->
                    memory to relevanceScore(memory, normalizedQuery, candidates.size, frequencies)
                }
            }
            .sortedWith(
                compareByDescending<Pair<Memory, Double>> { it.second }
                    .thenByDescending { it.first.file?.lastModified() ?: 0L }
                    .thenBy { it.first.name.lowercase() }
            )
            .take(MAX_INJECTED_MEMORIES)
            .map { it.first }
    }

    /**
     * 短期记忆是否已过期。取不到过期时间时视为永久——宁可多注入也不能把长期知识误判成过期。
     */
    private fun isExpired(memory: Memory): Boolean {
        val at = memory.expiresAt ?: return false
        return at > 0L && System.currentTimeMillis() > at
    }

    /**
     * 判定一条记忆是否已「遗忘」（长期未改动）。
     *
     * 核心档案（名字含 CORE / GLOBAL）永远保留——它们承载的正是「长期不变的约定」，
     * 长期不动是正常状态，被降级等于用户丢了自己的全局偏好。
     * 取不到文件或时间戳时保守保留，宁可多注入也不误伤。
     */
    private fun isStale(memory: Memory): Boolean {
        if (isCoreArchiveName(memory.name)) return false
        // 长期约定类（type: identity）与核心档案同理：长期不动是正常状态，
        // 被降级等于用户丢了自己的约定，也与 MemoryType.IDENTITY「永远保留」的语义相符。
        if (memory.type == MemoryType.IDENTITY) return false
        val lastModified = memory.file?.lastModified() ?: return false
        if (lastModified <= 0L) return false
        // 被频繁取用、只是内容没改的条目不算陈旧：判断依据取「修改」与「取用」中更近的一次。
        val lastUsed = memoryUsageStore.lastUsedAt(memory.scope.name, memory.name)
        return System.currentTimeMillis() - maxOf(lastModified, lastUsed) > STALE_MEMORY_MILLIS
    }

    /**
     * 记忆与当前任务的相关度：对 keywords / name / description 做命中计数后加权求和。
     * [query] 已 trim + lowercase；为空直接返回 0，调用方据此退化到 mtime 排序。
     */
    /**
     * 记忆与当前任务的相关度。
     *
     * 在「命中计数 × 字段权重」的基础上再乘词的稀有度（IDF）：常见词（「记忆」「项目」）几乎每条都有，
     * 命中它说明不了什么；专有词（「PRoot」「bge-m3」）只出现在少数条目里，命中它才是强信号。
     * 单用命中计数时这两者等价，清单就会被通用条目挤满。
     *
     * [total] 为候选条目总数，[frequencies] 为 term → 文档频数（见 [documentFrequencies]）。
     */
    private fun relevanceScore(
        memory: Memory,
        query: String,
        total: Int,
        frequencies: Map<String, Int>
    ): Double {
        if (query.isEmpty()) return 0.0
        var score = weightedHits(memory.keywords.flatMap { termsOf(it) }, query, total, frequencies) *
            KEYWORD_HIT_WEIGHT
        score += weightedHits(termsOf(memory.name), query, total, frequencies) * NAME_HIT_WEIGHT
        // description 命中数封顶：散文越长越容易碰巧命中，不封顶会让它压过 keywords。
        score += weightedHits(
            termsOf(memory.description), query, total, frequencies, MAX_DESCRIPTION_HITS
        ) * DESCRIPTION_HIT_WEIGHT
        return score
    }

    /** [terms] 去重后，取出现在 [query] 里的词，按稀有度（IDF）降序累加，最多 [cap] 个。 */
    private fun weightedHits(
        terms: List<String>,
        query: String,
        total: Int,
        frequencies: Map<String, Int>,
        cap: Int = Int.MAX_VALUE
    ): Double =
        terms.toHashSet()
            .filter { it in query }
            .map { idf(it, total, frequencies) }
            .sortedDescending()
            .take(cap)
            .sum()

    /** 平滑 IDF：出现越少的词权重越高，下限 1.0（常见词仍保有一份基础分，不至于归零）。 */
    private fun idf(term: String, total: Int, frequencies: Map<String, Int>): Double =
        ln((total + 1.0) / ((frequencies[term] ?: 0) + 1.0)) + 1.0

    /** 每个可命中词出现在多少条记忆里：IDF 的原料。同一个词在一条记忆里出现多次只计一次。 */
    private fun documentFrequencies(memories: List<Memory>): Map<String, Int> {
        val frequencies = HashMap<String, Int>()
        for (memory in memories) {
            val terms = HashSet<String>()
            memory.keywords.forEach { terms.addAll(termsOf(it)) }
            terms.addAll(termsOf(memory.name))
            terms.addAll(termsOf(memory.description))
            for (term in terms) frequencies[term] = (frequencies[term] ?: 0) + 1
        }
        return frequencies
    }

    /**
     * 两段文本是否已经不在讲同一件事。
     *
     * 用于注入清单的缓存失效：清单按会话缓存（保 KV 缓存），但话题换掉后旧清单就该让位，
     * 否则上一话题算出来的记忆会长期占着位置，新话题相关的条目永远进不来。
     * 判据是切词后的重叠系数（交集 / 较短一侧），对「任务从改 A 变成改 B」这类换题敏感。
     * [current] 为空或只有一个词（「继续」「嗯」这类延续性输入）时一律当作没换题，避免误重排。
     */
    fun isTopicDrift(previous: String, current: String): Boolean {
        if (current.isBlank()) return false
        val next = termsOf(current).toHashSet()
        if (next.size <= 1) return false
        if (previous.isBlank()) return true
        val prev = termsOf(previous).toHashSet()
        if (prev.isEmpty()) return true
        val overlap = prev.count { it in next }.toDouble() / minOf(prev.size, next.size)
        return overlap < TOPIC_DRIFT_THRESHOLD
    }

    /**
     * 把一段文本切成可命中的短词：先整体取出 IP / 域名 / 路径 / 端口（这些串被拆开就再也对不上），
     * 再按「非字母数字」切分并小写；纯 CJK 的词额外展开成字符
     * 二元组（bigram），弥补中文无空格分词——整串 CJK 词几乎不会原样出现在用户输入里，
     * bigram 才是能对上的最小单位。单字与停用词噪音太大，直接丢弃。
     */
    internal fun termsOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val terms = ArrayList<String>()
        terms += MemoryEntities.extract(text)
        for (raw in TERM_SPLIT.split(text)) {
            val token = raw.lowercase()
            if (token.length < MIN_TERM_LENGTH || token in STOP_WORDS) continue
            terms += token
            if (token.length >= CJK_BIGRAM_MIN_LENGTH && token.all { isCjk(it) }) {
                for (i in 0 until token.length - 1) {
                    terms += token.substring(i, i + 2)
                }
            }
        }
        return terms
    }

    /** 是否 CJK 汉字（Unicode 基本区）：只有纯汉字的词才需要展开 bigram。 */
    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private companion object {
        /** 近重复提示的最低相关度：至少命中一个关键词或名称才值得提醒，避免提示噪声。 */
        const val SIMILAR_MEMORY_MIN_SCORE = 2.0

        /**
         * 判定「换了话题」的重叠系数下限。低于它就认为当前提问与建立清单时不是同一件事，
         * 失效重排注入清单。取 0.25 偏保守：宁可慢一拍让位，也别把同一话题内的提示词反复重排。
         */
        const val TOPIC_DRIFT_THRESHOLD = 0.25

        /** 注入清单的条数上限。超出的条目只是不进提示词，文件仍在，模型仍可 read / edit / delete。 */
        const val MAX_INJECTED_MEMORIES = 60

        /** 遗忘阈值（天）：超过这么久没改动的普通条目不再注入。 */
        const val STALE_MEMORY_DAYS = 180L

        const val STALE_MEMORY_MILLIS = STALE_MEMORY_DAYS * 24L * 60L * 60L * 1000L

        /** 关键词 / 名称 / 描述命中一次的权重：keywords 最准，description 最噪。 */
        const val KEYWORD_HIT_WEIGHT = 3
        const val NAME_HIT_WEIGHT = 2
        const val DESCRIPTION_HIT_WEIGHT = 1

        /** description 命中数封顶，防止长篇描述凭字数压过 keywords。 */
        const val MAX_DESCRIPTION_HITS = 4

        /** 参与命中的最短词长：单字噪音太大，不值得计入。 */
        const val MIN_TERM_LENGTH = 2

        /** CJK 词至少这么长才展开 bigram（两字词本身就是它的大二元组，无需重复展开）。 */
        const val CJK_BIGRAM_MIN_LENGTH = 3

        /** 按「非字母数字」切词：CJK 汉字属 \p{L}，会与相邻汉字连成一整串。 */
        val TERM_SPLIT = Regex("[^\\p{L}\\p{N}]+")

        /** 常见虚词 / 停用词：命中它们不说明相关，直接不计。 */
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "this", "that", "is", "are", "was", "were",
            "一个", "这个", "那个", "就是", "可以", "已经", "还有", "如果", "所以", "但是", "因为",
            "全部", "需要", "注意", "表示", "使用", "进行", "没有", "不是", "以及", "或者"
        )
    }

    /**
     * 找「可能意同的已有条目」，供 memory 工具在保存后提醒模型改用 edit。
     *
     * 复用与注入排序同一套相关度打分（keywords > name > description），只比元信息不比正文：
     * 正文比对要么靠分词要么靠向量，成本与误报都不划算，宁可漏报也不误报。
     * 同名条目已被排除——那是覆盖，不是重名。
     */
    fun findSimilarMemories(
        name: String,
        description: String,
        projectRoot: String?,
        limit: Int = 3,
        scope: MemoryScope? = null
    ): List<Memory> {
        val query = "$name $description".lowercase()
        val candidates = listMemories(projectRoot, scope)
            .filterNot { it.name.equals(name, ignoreCase = true) }
        val frequencies = documentFrequencies(candidates)
        return candidates
            .asSequence()
            .map { it to relevanceScore(it, query, candidates.size, frequencies) }
            .filter { it.second >= SIMILAR_MEMORY_MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?, scope: MemoryScope? = null): String? =
        findMemory(name, projectRoot, scope)?.content

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?, type: MemoryType? = null, expiresAt: Long? = null, pending: Boolean? = null, keywords: List<String>? = null): Boolean {
        val existing = existingMemoryOf(name, scope, projectRoot)
        // 未显式指定的字段沿用原值：否则覆盖 / 合并一条 identity 记忆会抹掉它的 type，
        // 重写一条短期记忆也会把它变成永久（反过来同理）；「待确认」标记同理，
        // 只有调用方明确表态（比如用户在设置页保存）才清除。
        val effectiveType = type ?: existing?.type
        val effectiveExpiresAt = expiresAt ?: existing?.expiresAt
        val effectivePending = pending ?: existing?.pending ?: false
        val effectiveKeywords = keywords ?: existing?.keywords
        return when (scope) {
            MemoryScope.GLOBAL ->
                globalMemorySource.saveMemory(name, description, content, effectiveType, effectiveExpiresAt, effectivePending, effectiveKeywords)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content, effectiveType, effectiveExpiresAt, effectivePending, effectiveKeywords)
            }
        }
    }

    /** 记一次记忆取用（read 成功时调用）：遗忘判定与「这条到底用过没」的事实依据。 */
    fun recordUsage(name: String, scope: MemoryScope) {
        runCatching { memoryUsageStore.record(scope.name, name) }
    }

    /** 已存在条目的分类；不存在时返回 null（新建交给 MemoryParser 的启发式推断）。 */
    private fun existingMemoryOf(name: String, scope: MemoryScope, projectRoot: String?): Memory? =
        findMemory(name, projectRoot, scope)

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }

    /**
     * 把一条没能写成本体的内容存进归档目录。
     *
     * 归档只落在对应作用域的记忆根目录下（全局或该项目），不跨作用域写入——项目记忆里
     * 的候选不该因为同名条目在全局就跑到全局目录去。
     */
    fun archiveContent(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        type: MemoryType? = null,
        keywords: List<String> = emptyList(),
        expiresAt: Long? = null,
        pending: Boolean = false
    ): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.archiveContent(name, description, content, type, keywords, expiresAt, pending)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).archiveContent(name, description, content, type, keywords, expiresAt, pending)
            }
        }
    }

    /** 列归档：设置页「归档」视图用。 */
    fun listArchive(scope: MemoryScope, projectRoot: String?): List<MemoryArchiveInfo> = when (scope) {
        MemoryScope.GLOBAL -> globalMemorySource.listArchive()
        MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }
            ?.let { projectSource(it).listArchive() } ?: emptyList()
    }

    fun readArchive(fileName: String, scope: MemoryScope, projectRoot: String?): String? = when (scope) {
        MemoryScope.GLOBAL -> globalMemorySource.readArchive(fileName)
        MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }
            ?.let { projectSource(it).readArchive(fileName) }
    }

    /** 恢复一条归档为正式记忆；同名现有条目会先被归档（见 [MemorySource.restoreArchive]）。 */
    fun restoreArchive(fileName: String, scope: MemoryScope, projectRoot: String?): Boolean = when (scope) {
        MemoryScope.GLOBAL -> globalMemorySource.restoreArchive(fileName)
        MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }
            ?.let { projectSource(it).restoreArchive(fileName) } ?: false
    }
}
