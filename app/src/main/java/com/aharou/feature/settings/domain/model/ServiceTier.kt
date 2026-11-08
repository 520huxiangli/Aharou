package com.aharou.feature.settings.domain.model

/**
 * 模型级服务档位。
 *
 * [FOLLOW] 的 apiValue 为 null：请求里不带 `service_tier`，交给上游默认。
 * 这既是默认值，也是老用户升级后的行为——不选就不发，请求体逐字节不变。
 */
enum class ServiceTier(val apiValue: String?) {
    FOLLOW(null),
    STANDARD("default"),
    FAST("fast"),
    ULTRAFAST("ultrafast");

    companion object {
        /** 从持久化的枚举名解析；认不出（含空串）一律当没记忆过。 */
        fun fromName(name: String?): ServiceTier? =
            name?.takeIf { it.isNotBlank() }?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
