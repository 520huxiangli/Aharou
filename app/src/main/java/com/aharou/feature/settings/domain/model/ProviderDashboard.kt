package com.aharou.feature.settings.domain.model

/**
 * 自定义面板查询结果。
 */
data class ProviderDashboardResult(
    val card: AdaptiveCardRoot = AdaptiveCardRoot(),
    val rawOutput: String = ""
)

/**
 * 自定义面板状态。
 */
sealed interface ProviderDashboardState {
    data object Idle : ProviderDashboardState
    data object Loading : ProviderDashboardState
    data class Success(val result: ProviderDashboardResult) : ProviderDashboardState
    data class Error(val message: String, val rawOutput: String = "") : ProviderDashboardState
}
