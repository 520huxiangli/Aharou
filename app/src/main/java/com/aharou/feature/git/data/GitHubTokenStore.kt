package com.aharou.feature.git.data

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub API 专用令牌的本地存储。
 *
 * 与 git 推送凭据（`feature/credentials` 的 git-credentials 文件）分开保存：那份由容器里的
 * `credential.helper=store` 直接读取，格式固定；这里只是「读 CI/PR 状态」用的 API token，
 * 存进独立 SharedPreferences，值经 [KeystoreCipher] 加密，不污染 git 凭据文件。
 */
@Singleton
class GitHubTokenStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取某 host 的令牌明文；未配置或解密失败返回 null。 */
    fun token(host: String): String? = prefs.getString(key(host), null)
        ?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    /** 保存令牌；空串表示清除。 */
    fun save(host: String, token: String) {
        val trimmed = token.trim()
        val editor = prefs.edit()
        if (trimmed.isEmpty()) {
            editor.remove(key(host))
        } else {
            editor.putString(key(host), KeystoreCipher.encryptString(trimmed))
        }
        editor.apply()
    }

    private fun key(host: String): String = "token_${host.lowercase()}"

    private companion object {
        const val PREFS_NAME = "git_pr_github_tokens"
    }
}
