package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆的 AI 蒸馏：把近期流水（每日日志、归档）交给模型，提炼成几条长期结论后追加进 GLOBAL.md。
 *
 * 两条硬约束：
 *  - **只追加**：结果写成 GLOBAL.md 里的一个新段，原有内容一字不动；模型写坏也只是多一段废话，
 *    删掉那段即可回滚（追加前另有 `GLOBAL.md.bak-<日期>` 备份）。
 *  - **有成本护栏**：默认每 [DISTILL_INTERVAL_MS] 才跑一次，喂进去的素材有字符上限；供应商没配置
 *    或调用失败一律静默返回，不打断启动。
 */
@Singleton
class MemoryDistiller @Inject constructor(
    private val memoryStore: AharouMemoryStore,
    private val agentWorkflow: AgentWorkflow,
    private val promptProvider: SystemPromptProvider,
) {

    private companion object {
        const val TAG = "MemoryDistiller"
        const val PROMPT_FILE = "agent/memory-distill.md"

        /** 两次蒸馏的最小间隔（7 天）。 */
        const val DISTILL_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /** 单次喂给模型的素材字符上限，避免长年积累把一次调用撑爆。 */
        const val MAX_SOURCE_CHARS = 40_000

        /** 上一轮蒸馏要点回喂时的字符上限。 */
        const val MAX_PREVIOUS_CHARS = 8_000

        /** 模型判定「无需保留」时的约定回答。 */
        const val EMPTY_ANSWER = "无"

        /** 上一轮要点在 GLOBAL.md 里的段落标记。 */
        const val PREVIOUS_MARKER = "## 蒸馏要点"
    }

    /**
     * 到点就蒸馏一次。
     *
     * @param force 忽略时间间隔（设置页的「立即蒸馏」用）。
     * @return 是否真的往 GLOBAL.md 写了内容。
     */
    suspend fun distillIfDue(force: Boolean = false): Boolean {
        if (!memoryStore.isMemoryEnabled()) return false
        val last = memoryStore.lastDistillAt()
        if (!force && System.currentTimeMillis() - last < DISTILL_INTERVAL_MS) return false

        val source = memoryStore.collectDistillSource(since = last, maxChars = MAX_SOURCE_CHARS)
        if (source.isBlank()) return false

        val systemPrompt = runCatching { promptProvider.resolvePrompt(PROMPT_FILE) }.getOrNull()
        if (systemPrompt.isNullOrBlank()) {
            FileLogger.w(TAG, "蒸馏提示词缺失：$PROMPT_FILE")
            return false
        }

        val answer = agentWorkflow.completeOnce(systemPrompt, buildInput(source)) ?: return false
        val distilled = answer.trim()
        if (distilled.isEmpty() || distilled == EMPTY_ANSWER) {
            memoryStore.markDistilled()
            FileLogger.i(TAG, "本轮没有值得长期保留的内容")
            return false
        }

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val written = memoryStore.appendGlobalSection("蒸馏要点（$day）", distilled)
        if (written) {
            memoryStore.markDistilled()
            FileLogger.i(TAG, "已蒸馏 ${source.length} 字素材，写入 ${distilled.length} 字要点")
        }
        return written
    }

    /**
     * 拼输入：上一轮要点在前、本轮新流水在后。
     *
     * 把旧要点一并回喂，是为了让它做**增量更新**而不是每轮从零重写——重写会一轮轮洗掉细节，
     * 最终「约定/偏好」跟流水一起被稀释掉（业界实测五轮后这类内容只剩一成）。
     */
    private fun buildInput(source: String): String = buildString {
        previousDistilled()?.let {
            append("## 上一轮已经蒸馏出的要点\n")
            append("请在此基础上更新：仍然成立的保留、已经过期的删掉、本轮新出现的补上；不要在条目上重复叙述。\n\n")
            append(it).append("\n\n")
        }
        append("## 本轮新增流水\n")
        append(source)
    }

    /** 取 GLOBAL.md 里最后一段「蒸馏要点」，用于增量更新。 */
    private fun previousDistilled(): String? {
        val global = memoryStore.readGlobal() ?: return null
        val idx = global.lastIndexOf(PREVIOUS_MARKER)
        if (idx < 0) return null
        return global.substring(idx).trim().take(MAX_PREVIOUS_CHARS).takeIf { it.isNotBlank() }
    }
}
