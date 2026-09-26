package com.aharou.feature.credentials.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「自动注入 git 凭证到远程服务器」开关的持久化，按服务器（IP+端口）隔离。
 *
 * 每台远程服务器独立记忆：开关状态与「是否已询问过」互不影响，避免用户对 A 服务器开启后，
 * 连 B 服务器时被同一状态误伤，也保证首次询问每台服务器只弹一次。
 */
@Singleton
class CredentialInjectSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS = "credential_inject_prefs"
        const val KEY_AUTO_INJECT = "auto_inject_cred_"
        const val KEY_ASKED = "asked_auto_inject_"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 服务器标识哈希：MD5("host:port") 前 8 位，与记忆目录哈希同风格。 */
    fun serverKey(host: String, port: Int): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest("$host:$port".toByteArray(Charsets.UTF_8))
        return java.math.BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
    }

    fun isAutoInjectEnabled(serverKey: String): Boolean =
        prefs.getBoolean(KEY_AUTO_INJECT + serverKey, false)

    fun setAutoInject(serverKey: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_INJECT + serverKey, enabled).apply()
    }

    fun hasAsked(serverKey: String): Boolean =
        prefs.getBoolean(KEY_ASKED + serverKey, false)

    fun markAsked(serverKey: String) {
        prefs.edit().putBoolean(KEY_ASKED + serverKey, true).apply()
    }
}