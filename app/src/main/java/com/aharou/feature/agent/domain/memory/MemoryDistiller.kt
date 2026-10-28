package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.core.util.SecretMasker
import com.aharou.core.util.TextSanitizer
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆的 AI 蒸馏：把近期流水（每日日志、归档）交给模型，提炼成长期结论后**落成普通记忆条目**。
 *
 * 三条硬约束：
 *  - **产出必须是条目**：模型每条输出都会被 [DistilledEntryParser] 拆成独立的记忆文件，
 *    这样检索、单条修改、统计、撤销全部复用现有机制；拆不出条目的输出不写盘，宁可这轮白跑。
 *  - **只经条目写**：不再往 GLOBAL.md 追加散文段，避免同一批内容在两处各注入一次。
 *  - **有成本护栏**：默认每 [DISTILL_INTERVAL_MS] 才跑一次，喂进去的素材有字符上限；供应商没配置
 *    或调用失败一律静默返回，不打断启动。
 */
@Singleton
class MemoryDistiller @Inject constructor(
    private val memoryStore: AharouMemoryStore,
    private val agentWorkflow: AgentWorkflow,
    private val promptProvider: SystemPromptProvider,
    private val writer: DistilledMemoryWriter,
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

        /** 旧版要点在 GLOBAL.md 里的段落标记（现在只用于一次性迁移与清理）。 */
        const val PREVIOUS_MARKER = "## 蒸馏要点"
    }

    /**
     * 到点就蒸馏一次。
     *
     * @param force 忽略时间间隔（设置页的「立即蒸馏」用）。
     * @return 是否真的写入了记忆条目。
     */
    suspend fun distillIfDue(force: Boolean = false): Boolean {
        if (!memoryStore.isMemoryEnabled()) return false
        val last = memoryStore.lastDistillAt()
        if (!force && System.currentTimeMillis() - last < DISTILL_INTERVAL_MS) return false

        // 旧版把要点存成 GLOBAL.md 里的一段散文：先搬成条目，再按新路径蒸馏。
        migrateLegacySection()

        val source = memoryStore.collectDistillSource(since = last, maxChars = MAX_SOURCE_CHARS)
        if (source.isBlank()) {
            // 之前这里静默返回，导致「蒸馏从未产出」无法区分是没跑、还是没素材可蒸。
            FileLogger.i(TAG, "蒸馏跳过：自上次以来没有新的可蒸馏素材（last=${'$'}{if (last > 0L) last else 0L}）")
            return false
        }

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

        val entries = DistilledEntryParser.parse(distilled)
        if (entries.isEmpty()) {
            // 整段都拆不出条目：这轮不写盘，让下一轮重新蒸——写进去反而是一段没法检索的文本。
            FileLogger.w(TAG, "蒸馏产出解析不出条目，本轮跳过：${distilled.take(80)}")
            return false
        }

        val written = writer.write(entries, projectRoot = null)
        if (written > 0) {
            memoryStore.markDistilled()
            FileLogger.i(
                TAG,
                "已蒸馏：${entries.size} 条要点落盘 $written 条" +
                    "（素材 ${source.length} 字，产出 ${distilled.length} 字）"
            )
        }
        return written > 0
    }

    /**
     * 把旧版 GLOBAL.md 里 `## 蒸馏要点` 那段散文拆成条目。
     *
     * 只在**解析出条目并成功写入后**才删原段：解析失败宁可保持原样，否则内容直接丢。
     * 反复执行安全——段已不存在就什么都不做。
     */
    private fun migrateLegacySection() {
        val global = memoryStore.readGlobal() ?: return
        val idx = global.lastIndexOf(PREVIOUS_MARKER)
        if (idx < 0) return
        val entries = DistilledEntryParser.parse(global.substring(idx))
        if (entries.isEmpty()) return
        val written = writer.write(entries, projectRoot = null)
        if (written <= 0) return
        if (memoryStore.removeGlobalSection(PREVIOUS_MARKER)) {
            FileLogger.i(TAG, "旧蒸馏段已拆成 $written 条记忆条目并从 GLOBAL.md 移除")
        }
    }

    /**
     * 拼输入：上一轮条目在前、本轮新流水在后。
     *
     * 把旧要点一并回喂，是为了让它做**增量更新**而不是每轮从零重写——重写会一轮轮洗掉细节，
     * 最终「约定/偏好」跟流水一起被稀释掉（业界实测五轮后这类内容只剩一成）。
     */
    private fun buildInput(source: String): String = buildString {
        writer.previousEntriesText(MAX_PREVIOUS_CHARS)?.let {
            append("## 上一轮已经蒸馏出的要点\n")
            append("请在此基础上更新：仍然成立的保留、已经过期的删掉、本轮新出现的补上；不要在条目上重复叙述。\n\n")
            append(SecretMasker.mask(TextSanitizer.sanitize(it))).append("\n\n")
        }
        append("## 本轮新增流水\n")
        // 流水是 AI 自己写的，可能把令牌或私钥抄进去；送去模型前同样要消毒并掩码。
        append(SecretMasker.mask(TextSanitizer.sanitize(source)))
    }
}
