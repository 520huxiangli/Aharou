package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.MemoryTraceLog
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.session.GhostModeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
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
    private val chatSessionDao: ChatSessionDao,
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
        // 隐身会话不自动沉淀记忆（主模型经 memory 工具写入的路径已在 MemoryTool 拦截）。
        if (GhostModeStore.isGhost(chatSessionDao, sessionId)) return@runCatching 0
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
            if (c.scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) continue
            val effectiveScope = c.scope
            val existing = memoryRepository.findMemory(c.name, projectRoot, effectiveScope)
            if (existing != null) {
                if (existing.content.trim() != c.content.trim()) {
                    if (preserveConflict(c, existing, projectRoot, sessionId)) saved++
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

            // 名字不同、讲的却是同一件事，是记忆库最大的噪声来源。先做零成本的相似检索。
            val similar = memoryRepository
                .findSimilarMemories(c.name, c.description, projectRoot, limit = 1, scope = effectiveScope)
                .firstOrNull()
            if (similar?.pending == true) {
                if (preserveConflict(c, similar, projectRoot, sessionId)) saved++
                continue
            }
            val decision = judge(provider, c, similar)
            if (decision == null) {
                memoryTrace.record(
                    source = MemoryTraceLog.SOURCE_CURATOR,
                    action = MemoryTraceLog.ACTION_SKIP,
                    name = c.name,
                    scope = effectiveScope.name.lowercase(),
                    detail = "裁决失败，保守跳过自动写入",
                    sessionId = sessionId
                )
                continue
            }
            if (decision.action == ArbiterAction.REJECT) {
                FileLogger.i(TAG, "记忆「${c.name}」不值得写入，已拒绝")
                memoryTrace.record(
                    source = MemoryTraceLog.SOURCE_CURATOR,
                    action = MemoryTraceLog.ACTION_REJECT,
                    name = c.name,
                    scope = effectiveScope.name.lowercase(),
                    detail = decision.reason.ifBlank { "自动裁决判定不值得写入" },
                    sessionId = sessionId
                )
                continue
            }
            if (similar != null) {
                when (decision.action) {
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
                        }
                        continue
                    }
                    ArbiterAction.CONFLICT -> {
                        if (preserveConflict(c, similar, projectRoot, sessionId)) saved++
                        continue
                    }
                    else -> Unit
                }
            }

            val ok = memoryRepository.saveMemory(
                name = c.name,
                description = c.description,
                content = c.content,
                scope = effectiveScope,
                projectRoot = projectRoot,
                type = c.type
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

    private fun preserveConflict(
        candidate: Candidate,
        existing: Memory,
        projectRoot: String?,
        sessionId: String
    ): Boolean {
        if (candidate.scope != existing.scope) return false
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(candidate.content.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val baseName = "${candidate.name}_pending_$hash"
        var name = if (candidate.name.equals(existing.name, ignoreCase = true)) baseName else candidate.name
        var suffix = 0
        while (true) {
            val occupied = memoryRepository.findMemory(name, projectRoot, candidate.scope) ?: break
            if (occupied.content.trim() == candidate.content.trim()) break
            name = "${baseName}_${++suffix}"
        }
        // 旧条目**保持原样**：它正在生效，不该因为新来的候选而当场失效。
        // 旧实现把双方都标 pending，结果是两条都不进上下文——那次改动等于把知识丢了，
        // 比「暂时分不清哪条对」还糟。
        // 冲突关系放 keywords，不写进 description：描述会被永久污染，裁决完也洗不掉。
        val ok = memoryRepository.saveMemory(
            name,
            candidate.description,
            candidate.content,
            candidate.scope,
            projectRoot,
            candidate.type,
            pending = true,
            keywords = listOf("conflict:${existing.name}")
        )
        if (ok) {
            memoryTrace.record(
                source = MemoryTraceLog.SOURCE_CURATOR,
                action = MemoryTraceLog.ACTION_CONFLICT,
                name = name,
                scope = candidate.scope.name.lowercase(),
                detail = "与「${existing.name}」冲突，新条目标为待确认，旧条目不受影响",
                sessionId = sessionId
            )
        }
        return ok
    }

    /**
     * 让轻量模型裁决一个候选：先判「值不值得写」（不过门槛就 REJECT），再在无相近条目时
     * 于 NEW / REJECT 之间、有相近条目时于 NEW / MERGE / CONFLICT / SKIP / REJECT 之间选。
     *
     * 无论有没有相近条目都会调用。任何失败——提示词缺失、请求出错、JSON 不合规——
     * 都返回 null，由调用方保守跳过自动写入，不扩大自动沉淀范围。
     */
    private suspend fun judge(
        provider: AIProvider,
        candidate: Candidate,
        existing: Memory?
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
            if (existing == null) {
                // 没有相近条目：这次裁决只判「值不值得写」，模型据此在 NEW 与 REJECT 间选。
                append("（无相近的已有记忆）")
            } else {
                append("名称：").append(existing.name).append('\n')
                append("摘要：").append(existing.description).append('\n')
                append(existing.content.take(MAX_ARBITER_ITEM_CHARS))
            }
        }

        val raw = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(AgentMessage.UserMessage(content = userText)),
            tools = emptyList()
        ).content
        parseDecision(raw)?.takeIf {
            existing != null || it.action == ArbiterAction.NEW || it.action == ArbiterAction.REJECT
        }
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
            "REJECT" -> ArbiterAction.REJECT
            else -> return null
        }
        val merged = obj["mergedContent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (action == ArbiterAction.MERGE && merged.isEmpty()) return null
        // reason 是裁决器的一句话理由，REJECT 时记入流水，其余动作忽略。
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ArbiterDecision(action, merged, reason)
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
                    val type = MemoryType.from(obj["type"]?.jsonPrimitive?.contentOrNull?.trim())
                    if (name.isEmpty() || body.isEmpty() || description.isEmpty()) return@runCatching null
                    Candidate(
                        name = MemorySource.sanitizeName(name),
                        description = description.take(200),
                        content = body.take(2000),
                        scope = if (isProject) MemoryScope.PROJECT else MemoryScope.GLOBAL,
                        type = type
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
        val scope: MemoryScope,
        /** 抽取器声明的分类；null 时交给 MemoryParser 启发式推断。 */
        val type: MemoryType? = null
    )

    private companion object {
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 裁决器提示词，与 [PromptFileResolver.resolve] 的路径约定一致。 */
        const val ARBITER_PROMPT_FILE = "agent/memory-arbiter.md"
    }
}

/** 裁决器给出的处理方式。 */
private enum class ArbiterAction { NEW, MERGE, CONFLICT, SKIP, REJECT }

/**
 * 一次裁决的结果；[mergedContent] 仅在 MERGE 时非空（由 [MemoryCurator.parseDecision] 保证），
 * [reason] 是裁决器给出的一句话理由，REJECT 时记入流水。
 */
private data class ArbiterDecision(
    val action: ArbiterAction,
    val mergedContent: String = "",
    val reason: String = ""
)
