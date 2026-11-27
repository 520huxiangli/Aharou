package com.aharou.feature.agent.presentation

import com.aharou.core.util.formatCostUsd
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.presentation.component.formatTokenCount
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.settings.domain.service.ModelCostCalculator
import java.util.Calendar
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * `/usage` 的用量报表：今日与累计的调用次数、token 用量与预估费用。
 *
 * 纯查询、无副作用，从 [AIAgentViewModel] 搬出来只为给它腾行数（那个文件顶在架构门禁基线上）。
 */
@ViewModelScoped
class UsageReportController @Inject constructor(
    private val llmCallRecordDao: LlmCallRecordDao,
    private val aiProviderRepository: AIProviderRepository,
    private val modelCostCalculator: ModelCostCalculator,
) {

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 以 Markdown 表格返回用量报表（末尾不带空行，调用方直接落库或展示）。 */
    suspend fun renderMarkdown(): String {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = loadUsageSummary(todayStart)
        val allTime = loadUsageSummary(0L)
        return buildString {
            appendLine("| 项目 | 今日 | 累计 |")
            appendLine("|---|---|---|")
            appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
            appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
            appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
            appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
            appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
        }.trimEnd()
    }

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }
}
