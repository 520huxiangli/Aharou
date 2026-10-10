package com.aharou.feature.account.domain.repository

import com.aharou.feature.account.domain.model.AccountProfile
import com.aharou.feature.account.domain.model.AccountSession
import kotlinx.coroutines.flow.Flow

/**
 * 自建账号后端（New API 站点）的会话与充值入口。
 *
 * 会话凭证只存在本机（DataStore + Keystore 加密），后端签发的 access token 有效期很短，
 * 过期后所有接口都会返回未授权，由调用方引导重新登录。
 */
interface AccountRepository {
    /** 当前登录态；未登录时发射 null。 */
    val session: Flow<AccountSession?>

    /** 发送邮箱验证码。 */
    suspend fun sendVerificationCode(email: String): Result<Unit>

    /** 邮箱 + 验证码 + 密码注册；成功后仍需再走一次 [login]。 */
    suspend fun register(email: String, password: String, verificationCode: String): Result<Unit>

    /** 邮箱 + 密码登录；成功即落盘会话。 */
    suspend fun login(email: String, password: String): Result<AccountSession>

    /** 清除本机会话（后端令牌保留，重新登录可复用）。 */
    suspend fun logout()

    /** 拉取当前账户余额与用量。 */
    suspend fun fetchProfile(): Result<AccountProfile>

    /** 兑换码充值，返回本次到账额度。 */
    suspend fun redeem(redemptionCode: String): Result<Long>

    /**
     * 确保后端令牌存在，并把拿到的 API Key 写进一个「Aharou 内置AI」provider，返回 provider id。
     *
     * 换账号时会另建 provider，避免把上一个账号的 Key 留在配置里被新账号误用。
     */
    suspend fun provisionProvider(): Result<String>
}
