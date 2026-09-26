package com.aharou.feature.agent.domain.provider

import com.aharou.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import retrofit2.HttpException

/**
 * 判断一次 LLM 调用失败是否可归因于当前使用的 API Key——即是否应触发多 Key 自动切换。
 *
 * 只认鉴权/权限、计费/额度与限流类失败：HTTP 状态码默认 401/402/403/429（可按提供商覆盖，
 * 见 [AIProviderConfig.keySwitchStatusCodes]），以及流内的额度/鉴权错误码。5xx、408、超时、
 * DNS/连接故障属于服务端或链路问题，换 Key 无益，不应触发切换。
 *
 * [enrichWithHttpErrorBody] 会把 [HttpException] 包成 IllegalStateException 并把原异常挂在
 * cause 上，所以这里要沿 cause 链找状态码，找不到才退回消息文本匹配。
 */
fun Throwable.isKeySwitchFailure(statusCodes: Set<Int> = DEFAULT_KEY_SWITCH_STATUS_CODES): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() in statusCodes) return true
            is StreamApiException -> {
                if (current.code?.lowercase() in KEY_FAILURE_STREAM_CODES) return true
            }
        }
        current = current.cause
        depth++
    }
    val text = message?.lowercase() ?: return false
    // HTTP 状态码的文本兜底同样受 [statusCodes] 约束，否则自定义码表无法真正收窄触发范围
    if (statusCodes.any { text.contains("http $it") }) return true
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

private const val MAX_CAUSE_DEPTH = 5

private val KEY_FAILURE_STREAM_CODES = setOf(
    "insufficient_quota",
    "quota_exceeded",
    "usage_limit_reached",
    "usage_not_included",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_api_key",
    "authentication_error",
    "permission_error",
    "permission_denied"
)

private val KEY_FAILURE_MESSAGES = listOf(
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "insufficient_quota",
    "insufficient balance",
    "quota exceeded",
    "authentication_error",
    "permission_denied"
)
