package com.aharou.feature.account.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.account.data.remote.AccountApiException
import com.aharou.feature.account.domain.model.AccountProfile
import com.aharou.feature.account.domain.model.AccountSession
import com.aharou.feature.account.domain.model.quotaToCny
import com.aharou.feature.account.domain.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * 一条待展示的提示。
 *
 * 能对上本地文案就带 [localRes]（可选 [formatArg] 填进 `%1$s`）；对不上（后端业务错误）就只带
 * [rawText] 原样展示。界面按这个优先级渲染，避免 ViewModel 持有 Context。
 */
data class AccountNotice(
    @param:StringRes val localRes: Int? = null,
    val formatArg: String? = null,
    val rawText: String? = null
)

/** 账号页界面状态。 */
data class AccountUiState(
    val profile: AccountProfile? = null,
    val busy: Boolean = false,
    val notice: AccountNotice? = null,
    /** 注册时填过的邮箱，用于登录表单回填。 */
    val prefilledEmail: String = ""
)

/**
 * 「内置AI」账号页的状态持有者：登录 / 注册 / 兑换 / 余额 / 自动配 Key。
 *
 * 后端失败原因分两类：本地文案（未登录、取 Key 失败）与后端英文原文（兑换码无效、邮箱已注册等），
 * 统一收敛成 [AccountNotice] 交给界面渲染。
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    val session: StateFlow<AccountSession?> = repository.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // 恢复会话后对齐一次余额，让进页面就有数据可看。
        viewModelScope.launch {
            if (repository.session.first() != null) refreshInternal()
        }
    }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }

    fun sendVerificationCode(email: String, onSent: () -> Unit = {}) = runJob {
        repository.sendVerificationCode(email)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        notice = AccountNotice(localRes = R.string.account_code_sent),
                        prefilledEmail = email
                    )
                }
                onSent()
            }
            .onFailure(::report)
    }

    /** 注册接口不返回凭证，成功后直接接着登录，省掉用户再输一次密码。 */
    fun register(email: String, verificationCode: String, password: String) = runJob {
        repository.register(email, password, verificationCode)
            .onSuccess { loginInternal(email, password) }
            .onFailure(::report)
    }

    fun login(email: String, password: String) = runJob {
        loginInternal(email, password)
    }

    fun logout() = runJob {
        repository.logout()
        _uiState.update {
            it.copy(profile = null, notice = AccountNotice(localRes = R.string.account_logged_out))
        }
    }

    fun refresh() = runJob { refreshInternal() }

    /** 兑换成功后立刻刷新余额，让「到账多少」一眼可见。 */
    fun redeem(code: String) = runJob {
        repository.redeem(code)
            .onSuccess { amount ->
                _uiState.update {
                    it.copy(
                        notice = AccountNotice(
                            localRes = R.string.account_redeem_success,
                            formatArg = amount.toCnyText()
                        )
                    )
                }
                refreshInternal()
            }
            .onFailure(::report)
    }

    /** 把后端令牌取回来写进 provider；失败不阻塞用户，只提示。 */
    fun provision() = runJob {
        repository.provisionProvider()
            .onSuccess {
                _uiState.update { it.copy(notice = AccountNotice(localRes = R.string.account_provider_ready)) }
            }
            .onFailure(::report)
    }

    private suspend fun loginInternal(email: String, password: String) {
        repository.login(email, password)
            .onSuccess {
                _uiState.update { it.copy(notice = AccountNotice(localRes = R.string.account_login_success)) }
                provision()
                refreshInternal()
            }
            .onFailure(::report)
    }

    private suspend fun refreshInternal() {
        repository.fetchProfile()
            .onSuccess { profile -> _uiState.update { it.copy(profile = profile) } }
            .onFailure(::report)
    }

    private fun runJob(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun report(error: Throwable) {
        _uiState.update {
            it.copy(
                notice = AccountNotice(
                    // 本地异常自带本地化文案，后端返回的只有英文原文，靠类型区分来源。
                    localRes = if (error is AccountApiException) null else R.string.account_error_generic,
                    rawText = error.message
                )
            )
        }
    }
}

/** 额度换算成人民币并保留两位小数；货币符号留在文案资源里，这里只出数字。 */
private fun Long.toCnyText(): String = String.format(Locale.getDefault(), "%.2f", quotaToCny())
