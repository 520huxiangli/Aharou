package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.data.local.dao.DayCallStats
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.settings.domain.service.ModelCostCalculator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Token 统计（用量 / 费用 / 渠道 / 模型 / 明细）的状态与聚合。
 *
 * 从 `SettingsViewModel` 里整块搬出来：这部分自己订阅 Room 的调用记录，和设置页其余开关
 * 没有耦合，搬走后主 ViewModel 少 30 个符号。
 *
 * 生命周期：单例常驻，聚合在 [ensureStarted] 后才开始收集（设置页首次进 Token 统计时调用），
 * 避免 App 启动就挂着一串 Room Flow。
 */
@Singleton
class TokenStatsController @Inject constructor(
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val providerRepository: AIProviderRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _period = MutableStateFlow(TokenStatsPeriod.LAST_7_DAYS)
    private val _state = MutableStateFlow(TokenStatsUiState())
    val state: StateFlow<TokenStatsUiState> = _state.asStateFlow()

    private val _callsPage = MutableStateFlow(0)
    private val _providerPage = MutableStateFlow(0)
    private val _modelPage = MutableStateFlow(0)
    private val _filterProviderId = MutableStateFlow<String?>(null)
    val filterProviderId: StateFlow<String?> = _filterProviderId.asStateFlow()
    private val _filterModel = MutableStateFlow<String?>(null)
    val filterModel: StateFlow<String?> = _filterModel.asStateFlow()

    /** 供应商配置快照：费用计算要按渠道取自定义单价，而它只随合并流更新，单独缓存一份避免再开订阅。 */
    @Volatile
    private var providerCache: List<AIProviderConfig> = emptyList()

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            combine(
                _period,
                _filterProviderId,
                _filterModel
            ) { period, filterProviderId, filterModel ->
                Triple(period, filterProviderId, filterModel)
            }.flatMapLatest { (period, filterProviderId, filterModel) ->
                val start = period.startMillis(System.currentTimeMillis())
                val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                combine(
                    combine(
                        if (period == TokenStatsPeriod.TODAY) {
                            llmCallRecordDao.getHourStats(start, tz, filterProviderId, filterModel)
                        } else {
                            llmCallRecordDao.getDayStats(start, tz, filterProviderId, filterModel)
                        },
                        llmCallRecordDao.getSummary(start, filterProviderId, filterModel)
                    ) { rawTrend, summary -> rawTrend to summary },
                    combine(
                        llmCallRecordDao.getProviderStats(start, filterProviderId, filterModel),
                        _providerPage
                    ) { list, page ->
                        val total = list.size
                        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                        val safePage = page.coerceIn(0, lastPage)
                        val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                        Triple(paged, safePage, total)
                    },
                    combine(
                        combine(
                            llmCallRecordDao.getModelStats(start, filterProviderId, filterModel),
                            _modelPage
                        ) { list, page ->
                            val total = list.size
                            val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                            val safePage = page.coerceIn(0, lastPage)
                            val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                            ModelStatsPaging(paged, safePage, total)
                        },
                        // 总费用必须带渠道聚合：自定义单价按渠道存储，丢渠道就会取错价。
                        llmCallRecordDao.getModelProviderCostStats(start, filterProviderId, filterModel)
                    ) { modelPaging, costStats -> modelPaging to costStats },
                    // 明细分页：页号或总数变化时重查当前页，其余聚合不重复计算
                    combine(_callsPage, llmCallRecordDao.getCallsCount(start, filterProviderId, filterModel)) { page, total -> page to total }
                        .flatMapLatest { (page, total) ->
                            llmCallRecordDao.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE, filterProviderId, filterModel)
                                .map { calls -> Triple(calls, total, page) }
                        },
                    // 可选筛选选项列表（全局不限过滤条件时的渠道与模型列表）
                    combine(
                        llmCallRecordDao.getProviderStats(start),
                        llmCallRecordDao.getModelStats(start),
                        providerRepository.getAllProviders()
                    ) { allProviders, allModels, configuredProviders ->
                        providerCache = configuredProviders
                        val providerList = (allProviders.mapNotNull { p ->
                            val id = p.providerId ?: return@mapNotNull null
                            id to (p.providerName ?: id)
                        } + configuredProviders.map { it.id to it.name }).distinctBy { it.first }
                        val modelList = allModels.mapNotNull { it.model }.distinct()
                        val keyCounts = configuredProviders.associate { it.id to it.apiKeys.size }
                        Triple(providerList, modelList, keyCounts)
                    }
                ) { (rawTrend, summary), (pProviders, pPage, pTotal), (modelPaging, costStats), (calls, total, page), (availableProviders, availableModels, keyCounts) ->
                    val trend = padTrend(period, rawTrend, tz)
                    val costs = withContext(Dispatchers.Default) {
                        val perCall = calls.associate {
                            it.record.id to callCostUsd(
                                it.record.providerId,
                                it.record.model,
                                it.record.inputTokens.toLong(),
                                it.record.cachedInputTokens.toLong(),
                                it.record.outputTokens.toLong(),
                                it.record.cacheCreationTokens.toLong()
                            )
                        }
                        val periodTotal = costStats.sumOf { s ->
                            callCostUsd(
                                s.providerId,
                                s.model,
                                s.inputTokens,
                                s.cachedInputTokens,
                                s.outputTokens,
                                s.cacheCreationTokens
                            ) ?: 0.0
                        }
                        perCall to periodTotal
                    }
                    TokenStatsUiState(
                        period = period,
                        summary = summary,
                        trend = trend,
                        providers = pProviders,
                        models = modelPaging.paged,
                        recentCalls = calls,
                        callsPage = page,
                        callsTotal = total,
                        providersPage = pPage,
                        providersTotal = pTotal,
                        modelsPage = modelPaging.page,
                        modelsTotal = modelPaging.total,
                        totalCostUsd = costs.second,
                        recentCallCosts = costs.first,
                        filterProviderId = filterProviderId,
                        filterModel = filterModel,
                        availableProviders = availableProviders,
                        availableModels = availableModels,
                        providerKeyCounts = keyCounts
                    )
                }
            }.collectLatest { _state.value = it }
        }
    }

    fun setPeriod(period: TokenStatsPeriod) {
        _period.value = period
        resetPages()
    }

    /** 设置供应商筛选（null 为全部）。 */
    fun setFilterProviderId(providerId: String?) {
        if (_filterProviderId.value == providerId) return
        _filterProviderId.value = providerId
        resetPages()
    }

    /** 设置模型筛选（null 为全部）。 */
    fun setFilterModel(model: String?) {
        if (_filterModel.value == model) return
        _filterModel.value = model
        resetPages()
    }

    /** 清除所有筛选条件。 */
    fun clearFilters() {
        if (_filterProviderId.value == null && _filterModel.value == null) return
        _filterProviderId.value = null
        _filterModel.value = null
        resetPages()
    }

    /** 调用明细翻页；越界时钳制到合法范围。 */
    fun setCallPage(page: Int) {
        val total = _state.value.callsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / CALLS_PAGE_SIZE
        _callsPage.value = page.coerceIn(0, lastPage)
    }

    /** 渠道统计翻页；越界时钳制到合法范围。 */
    fun setProviderPage(page: Int) {
        val total = _state.value.providersTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _providerPage.value = page.coerceIn(0, lastPage)
    }

    /** 模型统计翻页；越界时钳制到合法范围。 */
    fun setModelPage(page: Int) {
        val total = _state.value.modelsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _modelPage.value = page.coerceIn(0, lastPage)
    }

    /** 清空全部调用记录；Room Flow 自动把各聚合刷新为空。 */
    fun reset() {
        scope.launch {
            llmCallRecordDao.deleteAll()
            _filterProviderId.value = null
            _filterModel.value = null
            resetPages()
        }
    }

    private fun resetPages() {
        _callsPage.value = 0
        _providerPage.value = 0
        _modelPage.value = 0
    }

    /**
     * 单次调用的预估费用（USD）；模型无单价返回 null。
     * 单价解析与缓存价回退统一在 [ModelCostCalculator]。
     */
    private suspend fun callCostUsd(
        providerId: String?,
        model: String?,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val modelId = model ?: return null
        val provider = providerCache.firstOrNull { it.id == providerId }
        return modelCostCalculator.costUsd(
            providerId = providerId.orEmpty(),
            providerType = provider?.type ?: ProviderType.OPENAI,
            model = modelId,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens
        )
    }

    private companion object {
        const val CALLS_PAGE_SIZE = 10
        const val STATS_PAGE_SIZE = 5
    }
}

/**
 * 把趋势聚合补全为周期内的完整时间轴：无记录的天/小时补 0，避免周期内调用集中在同一天时
 * 趋势只有单个点而无法绘制折线图。「全部」周期从最早有记录的那天起补到当天。
 */
private fun padTrend(
    period: TokenStatsPeriod,
    trend: List<DayCallStats>,
    tzOffsetMillis: Long
): List<DayCallStats> {
    if (trend.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val byDay = trend.associateBy { it.day }
    val isHourly = period == TokenStatsPeriod.TODAY
    val bucketMillis = if (isHourly) 3_600_000L else 86_400_000L
    val endIndex = (now + tzOffsetMillis) / bucketMillis
    val startIndex = when (period) {
        TokenStatsPeriod.TODAY -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
        TokenStatsPeriod.ALL -> trend.first().day
        else -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
    }
    if (startIndex > endIndex) return trend
    return (startIndex..endIndex).map { index ->
        byDay[index] ?: DayCallStats(index, 0, 0, 0, 0, 0, null, null)
    }
}
