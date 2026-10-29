package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemoryTopicOrganizer"

/**
 * 深度整理：按主题归拢 + 重写摘要，由「自动整理记忆」开关控制。
 *
 * [MemoryCompactor] 的聚簇只看字面重叠，换个说法讲同一件事的条目它认不出来；这里改让模型读
 * 名字与摘要来分组，合并本身仍交给 [MemoryCompactor.tryMerge]，防编造、先归档、矛盾跳过三道保险不变。
 * 不做「对照代码淘汰过时条目」：后台任务读不到代码，让模型凭空判过时只会误删知识。
 */
@Singleton
class MemoryTopicOrganizer @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryCompactor: MemoryCompactor,
    private val promptFileResolver: PromptFileResolver,
    private val agentWorkflow: AgentWorkflow,
    private val memoryTrace: MemoryTraceLog,
    private val settings: MemoryAutoCompactSettings,
) {
    // 每日任务与设置页「打开开关立刻跑一次」可能撞上，同一时刻只许一轮。
    private val running = Mutex()

    /**
     * 开关开着且距上次满一天才跑；[force] 跳过间隔（开关刚打开时用），但仍要求开关开着。
     *
     * @param projectRoots 要整理的工作区；全局作用域总会跑。
     * @return 真跑了返回本次统计，否则 null。
     */
    suspend fun organizeIfDue(projectRoots: List<String>, force: Boolean = false): MemoryOrganizeReport? {
        if (!settings.autoOrganize) return null
        if (!force && System.currentTimeMillis() - settings.lastOrganizeAt < COOLDOWN_MILLIS) return null
        if (!running.tryLock()) return null
        return try {
            var total = organize(null, MemoryScope.GLOBAL)
            projectRoots.filter { it.isNotBlank() }.forEach { root ->
                val r = organize(root, MemoryScope.PROJECT)
                total = MemoryOrganizeReport(total.merged + r.merged, total.described + r.described)
            }
            settings.lastOrganizeAt = System.currentTimeMillis()
            settings.lastOrganizeReport = total
            FileLogger.i(TAG, "深度整理完成：合并 ${total.merged} 组，重写摘要 ${total.described} 条")
            total
        } finally {
            running.unlock()
        }
    }

    private suspend fun organize(projectRoot: String?, scope: MemoryScope): MemoryOrganizeReport {
        val merged = runCatching { mergeByTopic(projectRoot, scope) }
            .onFailure { FileLogger.w(TAG, "主题归拢失败（$scope）：${it.message}", it) }
            .getOrDefault(0)
        val described = runCatching { rewriteDescriptions(projectRoot, scope) }
            .onFailure { FileLogger.w(TAG, "摘要重写失败（$scope）：${it.message}", it) }
            .getOrDefault(0)
        return MemoryOrganizeReport(merged, described)
    }

    private suspend fun mergeByTopic(projectRoot: String?, scope: MemoryScope): Int {
        val candidates = memoryCompactor.compactionCandidates(projectRoot, scope)
        if (candidates.size < MIN_CANDIDATES) return 0
        val prompt = loadPrompt(TOPIC_PROMPT_FILE) ?: return 0

        // 合并要求组内类型与过期时间一致，按这两项先分桶，模型就不会提出注定被拒的组。
        val buckets = candidates.groupBy { it.type to it.expiresAt }.values
        val budget = maxOf(1, (candidates.size * MAX_SHRINK_RATIO).toInt())
        var shrink = 0
        var merged = 0
        for (bucket in buckets) {
            for (batch in bucket.chunked(TOPIC_BATCH_SIZE)) {
                if (merged >= MAX_GROUPS_PER_RUN || shrink >= budget) return merged
                if (batch.size < 2) continue
                val byName = batch.associateBy { it.name }
                for (names in proposeGroups(prompt, batch)) {
                    if (merged >= MAX_GROUPS_PER_RUN || shrink >= budget) return merged
                    val group = names.mapNotNull(byName::get).distinct().take(budget - shrink + 1)
                    if (group.size < 2) continue
                    if (memoryCompactor.tryMerge(group)) {
                        merged++
                        shrink += group.size - 1
                    }
                }
            }
        }
        return merged
    }

    private suspend fun proposeGroups(prompt: String, batch: List<Memory>): List<List<String>> {
        val input = batch.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val obj = completeJson(prompt, input) ?: return emptyList()
        return runCatching {
            obj["groups"]?.jsonArray.orEmpty().map { group ->
                group.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            }.filter { it.size >= 2 }
        }.getOrDefault(emptyList())
    }

    /** 只改摘要不改正文：写回时带读取时的版本，主对话恰好在改这条就放弃。 */
    private suspend fun rewriteDescriptions(projectRoot: String?, scope: MemoryScope): Int {
        val targets = memoryCompactor.compactionCandidates(projectRoot, scope)
            .filterNot { hasTrigger(it.description) }
            .take(MAX_DESCRIBE_PER_RUN)
        if (targets.isEmpty()) return 0
        val prompt = loadPrompt(DESCRIBE_PROMPT_FILE) ?: return 0

        val input = targets.joinToString("\n\n") { memory ->
            "### ${memory.name}\ndescription: ${memory.description}\n${memory.content.take(DESCRIBE_CONTENT_CHARS)}"
        }
        val obj = completeJson(prompt, input) ?: return 0
        val rewrites = runCatching {
            obj["items"]?.jsonArray.orEmpty().mapNotNull { element ->
                val item = element.jsonObject
                val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val desc = item["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                name to desc
            }.toMap()
        }.getOrDefault(emptyMap())

        var done = 0
        for (memory in targets) {
            val desc = rewrites[memory.name]?.takeIf { it.isNotEmpty() && it.length <= MAX_DESCRIPTION_CHARS }
                ?: continue
            if (desc == memory.description) continue
            val file = memory.file ?: continue
            val current = MemoryParser.parse(file, scope) ?: continue
            if (current.version != memory.version) continue
            val payload = MemoryParser.format(
                current.name, desc, current.content, current.type, current.keywords, current.expiresAt, current.pending
            )
            if (!MemoryFileStorage.write(file, payload, expectedVersion = current.version)) continue
            done++
            memoryTrace.record(
                source = MemoryTraceLog.SOURCE_TOOL,
                action = MemoryTraceLog.ACTION_EDIT,
                name = memory.name,
                scope = scope.name.lowercase(),
                detail = "自动整理重写摘要：${memory.description} → $desc",
            )
        }
        return done
    }

    private fun hasTrigger(description: String): Boolean =
        description.isNotBlank() && TRIGGER_WORDS.any { description.contains(it, ignoreCase = true) }

    private fun loadPrompt(file: String): String? =
        promptFileResolver.resolve(file).replace(LEADING_COMMENT, "").trim().ifEmpty {
            FileLogger.w(TAG, "整理提示词缺失：$file")
            null
        }

    private suspend fun completeJson(prompt: String, input: String): JsonObject? {
        val raw = runCatching { agentWorkflow.completeOnce(prompt, input) }.getOrNull() ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private companion object {
        const val TOPIC_PROMPT_FILE = "agent/memory-topic-group.md"
        const val DESCRIBE_PROMPT_FILE = "agent/memory-describe.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        const val COOLDOWN_MILLIS = 24L * 60L * 60L * 1_000L
        const val MIN_CANDIDATES = 4

        /** 一次发给模型分组的条目数：只发名字和摘要，60 条的输入量还在一次调用的舒适区。 */
        const val TOPIC_BATCH_SIZE = 60

        /** 单次最多合并的组数与收敛比例：一次改太多，人来不及核对合并质量。 */
        const val MAX_GROUPS_PER_RUN = 8
        const val MAX_SHRINK_RATIO = 0.2

        const val MAX_DESCRIBE_PER_RUN = 30
        const val DESCRIBE_CONTENT_CHARS = 300
        const val MAX_DESCRIPTION_CHARS = 120

        /** 摘要里出现这些词，就算已经写清「什么时候该读」，不再重写。 */
        val TRIGGER_WORDS = listOf("时读", "前读", "时用", "前用", "时看", "前看", "何时", "when ", "before ")
    }
}
