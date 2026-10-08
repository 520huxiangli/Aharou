package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）。
        // 每日日志与月归档（LOG-*）跟记忆条目同在记忆目录里，但它们不是「记忆」：
        // 列进摘要清单只会把清单塞满（空日志全是「无」），正文也已由今日日志尾巴覆盖。
        return allMemories
            .filterNot { it.name.startsWith("LOG-") }
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

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
            // 没有一句话摘要的条目不算「摘要式记忆」：注入它的名字等于白占一行，
            // 使用者看到名字也无从判断要不要读。
            // 但核心档案（CORE / GLOBAL / SOUL / OPS 等）是按文件名直接读的，本来就没有
            // frontmatter，若一并滤掉等于把全局约定和身份直接从提示词里除名。
            .filter { it.description.isNotBlank() || isCoreArchive(it.name) }
            .filterNot { isStale(it) }
            // 先算一次分再排序：写在 comparator 里会随比较次数反复重算、反复切词。
            .map { memory -> memory to relevanceScore(memory, normalizedQuery) }
            .sortedWith(
                compareByDescending<Pair<Memory, Int>> { it.second }
                    .thenByDescending { it.first.file?.lastModified() ?: 0L }
                    .thenBy { it.first.name.lowercase() }
            )
            .take(MAX_INJECTED_MEMORIES)
            .map { it.first }
    }

    /**
     * 判定一条记忆是否已「遗忘」（长期未改动）。
     *
     * 核心档案（名字含 CORE / GLOBAL）永远保留——它们承载的正是「长期不变的约定」，
     * 长期不动是正常状态，被降级等于用户丢了自己的全局偏好。
     * 取不到文件或时间戳时保守保留，宁可多注入也不误伤。
     */
    private fun isStale(memory: Memory): Boolean {
        if (isCoreArchive(memory.name)) return false
        val lastModified = memory.file?.lastModified() ?: return false
        if (lastModified <= 0L) return false
        return System.currentTimeMillis() - lastModified > STALE_MEMORY_MILLIS
    }

    /**
     * 核心档案：按文件名直接读、不写 frontmatter 的那几份，如 CORE / GLOBAL / SOUL / OPS /
     * PROFILE / SECRETS / LIFE / L0_AGENT / L1_MAP。它们的共同特征是**基名全大写**。
     *
     * 判定不能只认 CORE / GLOBAL：漏掉 SOUL、OPS 就等于把身份与运维约定从提示词里除名。
     */
    private fun isCoreArchive(name: String): Boolean {
        val base = name.substringBeforeLast('.').uppercase()
        return base.isNotEmpty() && base.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }

    /**
     * 记忆与当前任务的相关度：对 keywords / name / description 做命中计数后加权求和。
     * [query] 已 trim + lowercase；为空直接返回 0，调用方据此退化到 mtime 排序。
     */
    private fun relevanceScore(memory: Memory, query: String): Int {
        if (query.isEmpty()) return 0
        var score = hitCount(memory.keywords.flatMap { termsOf(it) }, query) * KEYWORD_HIT_WEIGHT
        score += hitCount(termsOf(memory.name), query) * NAME_HIT_WEIGHT
        // description 命中数封顶：散文越长越容易碰巧命中，不封顶会让它压过 keywords。
        val descriptionHits = hitCount(termsOf(memory.description), query)
            .coerceAtMost(MAX_DESCRIPTION_HITS)
        return score + descriptionHits * DESCRIPTION_HIT_WEIGHT
    }

    /** [terms] 去重后，统计有多少个作为子串出现在 [query] 里。 */
    private fun hitCount(terms: List<String>, query: String): Int {
        var hits = 0
        for (term in terms.toHashSet()) {
            if (term in query) hits++
        }
        return hits
    }

    /**
     * 把一段文本切成可命中的短词：按「非字母数字」切分并小写；纯 CJK 的词额外展开成字符
     * 二元组（bigram），弥补中文无空格分词——整串 CJK 词几乎不会原样出现在用户输入里，
     * bigram 才是能对上的最小单位。单字与停用词噪音太大，直接丢弃。
     */
    private fun termsOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val terms = ArrayList<String>()
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
        const val SIMILAR_MEMORY_MIN_SCORE = 2

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
        limit: Int = 3
    ): List<Memory> {
        val query = "$name $description".lowercase()
        return listMemories(projectRoot)
            .asSequence()
            .filterNot { it.name.equals(name, ignoreCase = true) }
            .map { it to relevanceScore(it, query) }
            .filter { it.second >= SIMILAR_MEMORY_MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content)
            }
        }
    }

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
        projectRoot: String?
    ): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.archiveContent(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).archiveContent(name, description, content)
            }
        }
    }
}
