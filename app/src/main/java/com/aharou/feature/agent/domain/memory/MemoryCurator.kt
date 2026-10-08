package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.provider.AIProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemoryCurator"

/** 单次抽取的上限条数（与提示词约定一致）。 */
private const val MAX_CANDIDATES = 3

/** 一轮对话送给整理器的上下文上限（字符）：只看最近发生的事，控制成本。 */
private const val MAX_TRANSCRIPT_CHARS = 12_000

/** 裁决时单侧正文的截断长度：足够判断是不是同一件事，又不至于把调用撑大。 */
private const val MAX_ARBITER_ITEM_CHARS = 1_500

/**
 * 引擎级记忆兜底：一轮对话结束后，用轻量模型静默抽取值得长期记住的事实，
 * 直接写入 [MemoryRepository]。主模型忘了调用 memory 工具时由此兜底；
 * 全程静默失败，绝不影响对话主流程。
 */
@Singleton
class MemoryCurator @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val promptFileResolver: PromptFileResolver,
    private val memoryTrace: MemoryTraceLog,
) {
    /** 提示词文件名，与 [PromptFileResolver.resolve] 的路径约定一致。 */
    private fun prompt(): String = promptFileResolver.resolve("agent/memory-curator.md")

    /**
     * 抽取并落盘本轮对话的记忆。
     * @param provider 由调用方解析好的轻量 provider（压缩专用模型或当前聊天模型回退）。
     * @param transcript 本轮对话文本（"用户: …/助手: …" 行）。
     * @return 本次实际写入的记忆条数（失败为 0）。
     */
    suspend fun curate(
        provider: AIProvider,
        sessionId: String,
        projectRoot: String?,
        transcript: String,
    ): Int = runCatching {
        if (transcript.isBlank()) return@runCatching 0
        val systemPrompt = prompt().replace(LEADING_COMMENT, "").trim()
        if (systemPrompt.isEmpty()) return@runCatching 0

        val response = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(AgentMessage.UserMessage(content = transcript.takeLast(MAX_TRANSCRIPT_CHARS))),
            tools = emptyList()
        )
        val candidates = parseCandidates(response.content)
        var saved = 0
        for (c in candidates) {
            // PROJECT 但无工作区时降级为 GLOBAL（静默场景下降级比丢弃合理）。
            val effectiveScope = if (c.scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
                MemoryScope.GLOBAL
            } else {
                c.scope
            }
            // 已有同名记忆不覆盖：主模型当轮写的完整版本不该被 curator 的截断版覆盖。
            // 但内容不同说明这条事实已经变了，候选版本照样进归档——直接丢掉等于库里
            // 留着过时版本，还看不出它被改过。
            val existing = memoryRepository.loadContent(c.name, projectRoot)
            if (existing != null) {
                if (existing.trim() != c.content.trim()) {
                    memoryRepository.archiveContent(c.name, c.description, c.content, effectiveScope, projectRoot)
                    FileLogger.i(TAG, "记忆「${c.name}」已存在且内容不同，候选版本转入归档")
                    memoryTrace.record(
                        source = MemoryTraceLog.SOURCE_CURATOR,
                        action = MemoryTraceLog.ACTION_ARCHIVE,
                        name = c.name,
                        scope = effectiveScope.name.lowercase(),
                        detail = "候选与同名条目内容不同，旧版已归档",
                        sessionId = sessionId
                    )
                } else {
                    FileLogger.i(TAG, "记忆「${c.name}」已存在，跳过自动覆盖")
                    memoryTrace.record(
                        source = MemoryTraceLog.SOURCE_CURATOR,
                        action = MemoryTraceLog.ACTION_SKIP,
                        name = c.name,
                        scope = effectiveScope.name.lowercase(),
                        detail = "与已有同名记忆完全一致",
                        sessionId = sessionId
                    )
                }
                continue
            }

            // 名字不同、讲的却是同一件事，是记忆库最大的噪声来源。先做零成本的相似检索，
            // 真撞上了再花一次调用让轻量模型四选一——没候选就一分钱不花。
            val similar = memoryRepository
                .findSimilarMemories(c.name, c.description, projectRoot, limit = 1)
                .firstOrNull()
            if (similar != null) {
                val decision = judge(provider, c, similar)
                // 判定失败（null）时整个 when 不进入，直接落到下面的新建。
                if (decision != null) when (decision.action) {
                    ArbiterAction.SKIP -> {
                        FileLogger.i(TAG, "记忆「${c.name}」与「${similar.name}」重复，跳过")
                        memoryTrace.record(
                            source = MemoryTraceLog.SOURCE_CURATOR,
                            action = MemoryTraceLog.ACTION_SKIP,
                            name = c.name,
                            scope = effectiveScope.name.lowercase(),
                            detail = "与「${similar.name}」重复",
                            sessionId = sessionId
                        )
                        continue
                    }
                    ArbiterAction.MERGE -> {
                        // 只允许改同一作用域里的条目：项目记忆不该改写全局记忆。
                        val merged = decision.mergedContent
                        if (similar.scope == effectiveScope &&
                            memoryRepository.saveMemory(
                                similar.name, similar.description, merged, similar.scope, projectRoot
                            )
                        ) {
                            FileLogger.i(TAG, "记忆「${c.name}」已并入「${similar.name}」")
                            memoryTrace.record(
                                source = MemoryTraceLog.SOURCE_CURATOR,
                                action = MemoryTraceLog.ACTION_MERGE,
                                name = similar.name,
                                scope = similar.scope.name.lowercase(),
                                detail = "并入「${c.name}」的新内容",
                                sessionId = sessionId
                            )
                            saved++
                            continue
                        }
                    }
                    ArbiterAction.CONFLICT -> {
                        if (similar.scope == effectiveScope) {
                            memoryRepository.archiveContent(
                                similar.name, similar.description, similar.content, similar.scope, projectRoot
                            )
                            if (memoryRepository.saveMemory(c.name, c.description, c.content, effectiveScope, projectRoot)) saved++
                            FileLogger.i(TAG, "记忆「${similar.name}」被新事实推翻，旧版已归档")
                            memoryTrace.record(
                                source = MemoryTraceLog.SOURCE_CURATOR,
                                action = MemoryTraceLog.ACTION_CONFLICT,
                                name = c.name,
                                scope = effectiveScope.name.lowercase(),
                                detail = "推翻「${similar.name}」，旧版已归档",
                                sessionId = sessionId
                            )
                            continue
                        }
                    }
                    // NEW、判定失败（null）或跳作用域改不动 → 一律落回新建。
                    else -> Unit
                }
            }

            val ok = memoryRepository.saveMemory(
                name = c.name,
                description = c.description,
                content = c.content,
                scope = effectiveScope,
                projectRoot = projectRoot
            )
            if (ok) {
                saved++
                memoryTrace.record(
                    source = MemoryTraceLog.SOURCE_CURATOR,
                    action = MemoryTraceLog.ACTION_NEW,
                    name = c.name,
                    scope = effectiveScope.name.lowercase(),
                    sessionId = sessionId
                )
            }
        }
        if (saved > 0) {
            FileLogger.i(TAG, "会话 $sessionId 自动沉淀 $saved 条记忆")
        }
        saved
    }.onFailure { e ->
        FileLogger.w(TAG, "自动记忆整理失败（静默忽略）: ${e.message}", e)
    }.getOrDefault(0)

    /**
     * 让轻量模型在新条目与一条已有相似条目之间做四选一。
     *
     * 只在确实检索到相似条目时才调用。任何失败——提示词缺失、请求出错、JSON 不合规——
     * 都返回 null，由调用方落回「直接新建」：判定器抽风时宁可多一条记忆，也不能把候选丢掉。
     */
    private suspend fun judge(
        provider: AIProvider,
        candidate: Candidate,
        existing: Memory
    ): ArbiterDecision? = runCatching {
        val systemPrompt = promptFileResolver.resolve(ARBITER_PROMPT_FILE)
            .replace(LEADING_COMMENT, "").trim()
        if (systemPrompt.isEmpty()) return@runCatching null

        val userText = buildString {
            append("## 待写入的新信息\n")
            append("名称：").append(candidate.name).append('\n')
            append("摘要：").append(candidate.description).append('\n')
            append(candidate.content.take(MAX_ARBITER_ITEM_CHARS)).append("\n\n")
            append("## 已有记忆\n")
            append("名称：").append(existing.name).append('\n')
            append("摘要：").append(existing.description).append('\n')
            append(existing.content.take(MAX_ARBITER_ITEM_CHARS))
        }

        val raw = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(AgentMessage.UserMessage(content = userText)),
            tools = emptyList()
        ).content
        parseDecision(raw)
    }.getOrNull()

    /** 解析裁决输出；MERGE 没给出合并正文时整体作废，交给降级路径处理。 */
    private fun parseDecision(raw: String): ArbiterDecision? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null

        val action = when (obj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()) {
            "NEW" -> ArbiterAction.NEW
            "MERGE" -> ArbiterAction.MERGE
            "CONFLICT" -> ArbiterAction.CONFLICT
            "SKIP" -> ArbiterAction.SKIP
            else -> return null
        }
        val merged = obj["mergedContent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (action == ArbiterAction.MERGE && merged.isEmpty()) return null
        return ArbiterDecision(action, merged)
    }

    /** 解析整理器输出；格式不合法/越界条目一律丢弃，宁缺毋滥。 */
    private fun parseCandidates(content: String): List<Candidate> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching {
            Json.parseToJsonElement(content.substring(start, end + 1)).jsonArray
        }.getOrNull() ?: return emptyList()

        return arr.asSequence()
            .mapNotNull { el -> runCatching { el.jsonObject }.getOrNull() }
            .mapNotNull { obj ->
                runCatching {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val body = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val isProject = obj["scope"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.equals("project", ignoreCase = true) == true
                    if (name.isEmpty() || body.isEmpty() || description.isEmpty()) return@runCatching null
                    Candidate(
                        name = name,
                        description = description.take(200),
                        content = body.take(2000),
                        scope = if (isProject) MemoryScope.PROJECT else MemoryScope.GLOBAL
                    )
                }.getOrNull()
            }
            .filter { MemorySource.sanitizeName(it.name).isNotEmpty() }
            .take(MAX_CANDIDATES)
            .toList()
    }

    private data class Candidate(
        val name: String,
        val description: String,
        val content: String,
        val scope: MemoryScope
    )

    private companion object {
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 裁决器提示词，与 [PromptFileResolver.resolve] 的路径约定一致。 */
        const val ARBITER_PROMPT_FILE = "agent/memory-arbiter.md"
    }
}

/** 裁决器给出的四种处理方式。 */
private enum class ArbiterAction { NEW, MERGE, CONFLICT, SKIP }

/** 一次裁决的结果；[mergedContent] 仅在 MERGE 时非空（由 [MemoryCurator.parseDecision] 保证）。 */
private data class ArbiterDecision(val action: ArbiterAction, val mergedContent: String = "")
