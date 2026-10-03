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
 * 技能市场的分类打标：技能元数据里没有分类字段，交给模型按描述判。
 *
 * 与 [SkillMarketTranslator] 同一套路数：
 *  - **只打没打过的**：结果按「仓库@技能目录」落盘（[SkillCategoryStore]），命中缓存直接跳过；
 *  - **失败不打扰**：模型没配、超时、解析不了，都只是这批没分类，列表继续用；
 *  - 走 [AgentWorkflow.completeOnce]：不建会话、不进历史、不占上下文。
 */
@Singleton
class SkillCategoryTagger @Inject constructor(
    private val workflow: AgentWorkflow,
    private val promptProvider: SystemPromptProvider,
    private val store: SkillCategoryStore
) {
    /** 已经分好类的（含以前存下的）。 */
    fun cached(): Map<String, List<String>> = store.all()

    /**
     * 给 [skills] 里还没分类的那些打标，每批打完回调一次（调用方可以边打边刷新筛选项）。
     *
     * 只送「有名字或有描述」的条目；两者都空的直接记成「其它」，不占一次模型调用。
     */
    suspend fun categorize(
        skills: List<MarketSkill>,
        onBatch: suspend (Map<String, List<String>>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val done = store.all()
        val todo = mutableListOf<MarketSkill>()
        val blanks = mutableMapOf<String, List<String>>()
        for (skill in skills) {
            if (skill.translationKey() in done) continue
            if (skill.name.isBlank() && skill.description.isBlank() && skill.displayName.isBlank()) {
                blanks[skill.translationKey()] = listOf(SkillCategories.OTHER)
            } else {
                todo += skill
            }
        }
        if (blanks.isNotEmpty()) {
            store.putAll(blanks)
            onBatch(blanks)
        }
        if (todo.isEmpty()) return@withContext

        for (chunk in todo.chunked(BATCH_SIZE)) {
            val batch = try {
                categorizeBatch(chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "分类一批失败：${e.message}")
                null
            }
            if (batch.isNullOrEmpty()) continue
            store.putAll(batch)
            onBatch(batch)
        }
    }

    private suspend fun categorizeBatch(batch: List<MarketSkill>): Map<String, List<String>> {
        val prompt = runCatching { promptProvider.resolvePrompt(PROMPT_FILE) }.getOrNull()
        if (prompt.isNullOrBlank()) {
            FileLogger.w(TAG, "分类提示词缺失：$PROMPT_FILE")
            return emptyMap()
        }
        val input = batch.mapIndexed { index, skill ->
            buildString {
                append("${index + 1}. ${skill.name.ifBlank { skill.displayName }}")
                val desc = skill.description.takeIf { it.isNotBlank() }
                    ?: skill.displayName.takeIf { it.isNotBlank() && it != skill.name }
                if (desc != null) append("\n   描述：$desc")
            }
        }.joinToString("\n")

        val answer = workflow.completeOnce(prompt, input) ?: return emptyMap()
        return parseAnswer(answer, batch)
    }

    /** 模型常把 JSON 包在 ``` 里或前后带一句话，从第一个 `[` 取到最后一个 `]`。 */
    private fun parseAnswer(answer: String, batch: List<MarketSkill>): Map<String, List<String>> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val array = runCatching {
            Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray
        }.getOrElse {
            FileLogger.w(TAG, "分类结果解析失败：${it.message}")
            return emptyMap()
        }

        val result = mutableMapOf<String, List<String>>()
        array.forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            // 模型给的序号是 1 起的，对不上就丢掉这一条（宁可漏打，不能错配到别的技能上）
            val index = obj["i"]?.jsonPrimitive?.content?.trim()?.toIntOrNull() ?: return@forEach
            val skill = batch.getOrNull(index - 1) ?: return@forEach
            val raw = obj["categories"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .orEmpty()
            result[skill.translationKey()] = SkillCategories.normalize(raw)
        }
        return result
    }

    private companion object {
        const val TAG = "SkillCategoryTagger"
        const val PROMPT_FILE = "agent/skill-categorize.md"

        /** 一批 25 条：批太小请求翻倍，批太大模型容易漏条。 */
        const val BATCH_SIZE = 25
    }
}
