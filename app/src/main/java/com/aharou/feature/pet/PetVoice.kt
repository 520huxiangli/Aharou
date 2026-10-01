package com.aharou.feature.pet

import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 她会说话的那一半：拿「人设提示词 + 记忆摘要」去换一句台词。
 *
 * 走的是 Agent 的通用一次性调用（[AgentWorkflow.completeOnce]）——不经过会话历史、不动任何会话状态，
 * 失败一律返回 null，由调用方退化成写死的静态台词（她可以哑，但不能报错）。
 *
 * 记忆只取**全局**那部分：桌宠不知道当前在哪个工作区，硬猜会把别的项目的记忆念出来。
 */
@Singleton
class PetVoice @Inject constructor(
    private val workflow: AgentWorkflow,
    private val memoryRepository: MemoryRepository,
    private val promptProvider: SystemPromptProvider,
) {

    /** @param scene 眼下发生了什么（「主人戳了你一下」这种），用来说对景。 */
    suspend fun line(scene: String): String? = withContext(Dispatchers.IO) {
        val prompt = runCatching { promptProvider.resolvePrompt(PROMPT_FILE) }
            .getOrNull()
            ?.replace(LEADING_COMMENT, "")
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null

        val raw = workflow.completeOnce(prompt, buildAsk(scene)) ?: return@withContext null
        clean(raw)
    }

    private fun buildAsk(scene: String): String = buildString {
        append("场景：").append(scene).append('\n')
        val digest = memoryDigest()
        if (digest.isNotBlank()) {
            append("你记得的事：\n").append(digest).append('\n')
        }
        append("说一句。")
    }

    /** 记忆摘要：只带名字与一句话描述，正文本就不该塞进这么小的调用里。 */
    private fun memoryDigest(): String = runCatching {
        memoryRepository.listMemoriesForPrompt(null)
            .take(MEMORY_ITEMS)
            .joinToString("\n") { "- ${it.name}：${it.description}" }
    }.getOrDefault("")

    /** 模型爱加引号、爱多写几行，这里只留第一句像样的话。 */
    private fun clean(raw: String): String? = raw.trim()
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("“", "”")
        ?.removeSurrounding("「", "」")
        ?.trim()
        ?.take(MAX_CHARS)
        ?.ifBlank { null }

    private companion object {
        const val PROMPT_FILE = "agent/pet-line.md"
        const val MEMORY_ITEMS = 12
        const val MAX_CHARS = 40
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
