package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 技能市场的中文翻译：仓库只给英文时，把技能名与描述交给模型翻一遍。
 *
 * 三条边界：
 *  - **只翻没中文的**：仓库自带中文（WorkBuddy 的 `description_zh`）不送模型，不花用户 token；
 *  - **只翻一次**：译文按「仓库@技能目录」落盘（[SkillTranslationStore]），命中缓存直接跳过；
 *  - **失败不打扰**：模型没配、超时、返回解析不了，都只是这条不翻，列表继续显示原文。
 *
 * 走的是 [AgentWorkflow.completeOnce]（和会话标题、记忆蒸馏同一条路）：不建会话、不进历史、不占上下文。
 */
@Singleton
class SkillMarketTranslator @Inject constructor(
    private val workflow: AgentWorkflow,
    private val promptProvider: SystemPromptProvider,
    private val store: SkillTranslationStore
) {
    /** 已经翻好的（含以前存下的）。 */
    fun cached(): Map<String, SkillTranslation> = store.all()

    /**
     * 翻 [skills] 里还没有中文的那些，每翻好一批回调一次（调用方可以边翻边刷新列表）。
     * 已缓存的和自带中文的都不送模型。
     */
    suspend fun translate(
        skills: List<MarketSkill>,
        onBatch: suspend (Map<String, SkillTranslation>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val done = store.all()
        val todo = skills.filter { it.translationKey() !in done && it.needsTranslation() }
        if (todo.isEmpty()) return@withContext

        for (chunk in todo.chunked(BATCH_SIZE)) {
            val batch = try {
                translateBatch(chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "翻译一批失败：${e.message}")
                null
            }
            if (batch.isNullOrEmpty()) continue
            store.putAll(batch)
            onBatch(batch)
        }
    }

    private suspend fun translateBatch(batch: List<MarketSkill>): Map<String, SkillTranslation> {
        val prompt = runCatching { promptProvider.resolvePrompt(PROMPT_FILE) }.getOrNull()
        if (prompt.isNullOrBlank()) {
            FileLogger.w(TAG, "翻译提示词缺失：$PROMPT_FILE")
            return emptyMap()
        }
        val input = batch.mapIndexed { index, skill ->
            buildString {
                append("${index + 1}. ${skill.name}")
                val desc = skill.description.ifBlank { skill.displayName }
                if (desc.isNotBlank()) append("\n   描述：$desc")
            }
        }.joinToString("\n")

        val answer = workflow.completeOnce(prompt, input) ?: return emptyMap()
        return parseAnswer(answer, batch)
    }

    /** 模型常把 JSON 包在 ``` 里或前后带一句话，从第一个 `[` 取到最后一个 `]`。 */
    private fun parseAnswer(answer: String, batch: List<MarketSkill>): Map<String, SkillTranslation> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val array = runCatching {
            Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray
        }.getOrElse {
            FileLogger.w(TAG, "翻译结果解析失败：${it.message}")
            return emptyMap()
        }

        val result = mutableMapOf<String, SkillTranslation>()
        array.forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            // 模型给的序号是 1 起的，对不上就丢掉这一条（宁可漏翻，不能错配到别的技能上）
            val index = obj["i"]?.jsonPrimitive?.content?.trim()?.toIntOrNull() ?: return@forEach
            val skill = batch.getOrNull(index - 1) ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val description = obj["description"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isEmpty() && description.isEmpty()) return@forEach
            result[skill.translationKey()] = SkillTranslation(name = name, description = description)
        }
        return result
    }

    private companion object {
        const val TAG = "SkillMarketTranslator"
        const val PROMPT_FILE = "agent/skill-translate.md"

        /** 一批 20 条：批太小请求翻倍，批太大模型容易漏条。 */
        const val BATCH_SIZE = 20
    }
}
