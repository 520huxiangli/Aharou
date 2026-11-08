package com.aharou.feature.im.domain.napcat

data class NapCatRuntimeState(
    val phase: String = "not_installed",
    val progress: Float = 0f,
    val version: String = "",
    val error: String = "",
    val stage: String = ""
)

data class NapCatLoginState(
    val loggedIn: Boolean = false,
    val accountId: String = "",
    val nickname: String = "",
    val qrCode: String = "",
    val error: String = "",
    val status: String = "unknown"
) {
    companion object {
        internal fun recoveryAccounts(accounts: List<String>): List<String> {
            require(accounts.all { it.matches(Regex("[0-9]{5,20}")) }) { "NapCat returned invalid recovery identity" }
            return accounts.distinct()
        }
    }
}
