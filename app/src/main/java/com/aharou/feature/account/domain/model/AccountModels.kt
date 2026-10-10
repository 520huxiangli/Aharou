package com.aharou.feature.account.domain.model

/** 登录成功后持有的账号会话；[accessToken] 是后端签发的短期凭证（约 15 分钟有效）。 */
data class AccountSession(
    val accessToken: String,
    val userId: Int,
    val username: String,
    val email: String,
    val displayName: String
) {
    /** 展示用名称：优先昵称，退回邮箱前缀，最后退回用户名。 */
    val displayLabel: String
        get() = displayName.ifBlank { email.ifBlank { username }.substringBefore('@') }
}

/** 账户概况。余额与用量都用后端额度单位，展示前经 [quotaToCny] 换算。 */
data class AccountProfile(
    val userId: Int,
    val username: String,
    val email: String,
    val displayName: String,
    val quota: Long,
    val usedQuota: Long,
    val requestCount: Long
)

/**
 * 后端额度换算成人民币的系数。
 *
 * 后端「额度」是整数计数，站点侧配置为 1 美元 = 500000 额度、美元汇率 7.3，
 * 折合 ¥1 ≈ 68493.1 额度。站点改配置时这里要同步，否则余额显示会偏。
 */
const val QUOTA_PER_CNY: Double = 68493.1

/** 后端额度换算成人民币元。 */
fun Long.quotaToCny(): Double = this / QUOTA_PER_CNY
