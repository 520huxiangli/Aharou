package com.aharou.feature.settings.presentation

import com.aharou.R
import com.aharou.feature.agent.data.local.dao.CallSummary
import com.aharou.feature.agent.data.local.dao.DayCallStats
import com.aharou.feature.agent.data.local.dao.ModelCallStats
import com.aharou.feature.agent.data.local.dao.ProviderCallStats
import com.aharou.feature.agent.data.local.dao.RecentCallRecord

/** Token 统计周期：决定统计起始时间与趋势粒度（今天=小时粒度，其余=天粒度）。 */
enum class TokenStatsPeriod(val labelRes: Int, val startMillis: (Long) -> Long) {
    TODAY(R.string.settings_token_stats_period_today, { now ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }),
    LAST_7_DAYS(R.string.settings_token_stats_period_7d, { now -> now - 7 * 86_400_000L }),
    LAST_30_DAYS(R.string.settings_token_stats_period_30d, { now -> now - 30 * 86_400_000L }),
    ALL(R.string.settings_token_stats_period_all, { 0L })
}

/** Token 统计页的完整 UI 状态，由周期 + 5 个聚合 Flow 组合而成。 */
data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: CallSummary? = null,
    val trend: List<DayCallStats> = emptyList(),
    val providers: List<ProviderCallStats> = emptyList(),
    val models: List<ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    /** 调用明细当前页（0 起）。 */
    val callsPage: Int = 0,
    /** 当前周期调用总数，供分页显示总页数。 */
    val callsTotal: Int = 0,
    /** 渠道当前页（0 起）。 */
    val providersPage: Int = 0,
    /** 渠道总数。 */
    val providersTotal: Int = 0,
    /** 模型当前页（0 起）。 */
    val modelsPage: Int = 0,
    /** 模型总数。 */
    val modelsTotal: Int = 0,
    /** 当前周期总费用（USD，渠道自定义单价优先，否则回退 models.dev 单价估算）。 */
    val totalCostUsd: Double = 0.0,
    /** 调用明细分页内每条记录的费用（key=记录 id，null=模型无单价）。 */
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    /** 筛选：指定供应商 ID（null 为不限）。 */
    val filterProviderId: String? = null,
    /** 筛选：指定模型名（null 为不限）。 */
    val filterModel: String? = null,
    /** 可供筛选的供应商列表（id to 名称）。 */
    val availableProviders: List<Pair<String, String>> = emptyList(),
    /** 可供筛选的模型列表。 */
    val availableModels: List<String> = emptyList(),
    /** 各供应商 ID 对应的密钥/凭据数量。 */
    val providerKeyCounts: Map<String, Int> = emptyMap()
)

/** 模型统计的分页结果：当前页数据 + 钳制后的页号 + 总数。 */
internal data class ModelStatsPaging(
    val paged: List<ModelCallStats>,
    val page: Int,
    val total: Int
)
