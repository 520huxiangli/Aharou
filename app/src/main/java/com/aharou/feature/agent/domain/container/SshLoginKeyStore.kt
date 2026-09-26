package com.aharou.feature.agent.domain.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** SSH 登录密钥（私钥）条目：连接时用于认证登录。 */
data class SshLoginKey(
    val id: String,
    val name: String,
    /** 私钥文件绝对路径（应用私有目录内）。 */
    val path: String,
    /** 公钥指纹（SHA256:...）；加密密钥无法解析时为 null。 */
    val fingerprint: String?
)

/** SSH 登录密钥（私钥）的持久化存储，私钥文件本身存放在应用私有目录。 */
interface SshLoginKeyStore {
    fun entries(): List<SshLoginKey>
    fun add(key: SshLoginKey)
    fun remove(id: String)
}

private const val PREFS_NAME = "ssh_login_keys"

/** 基于应用私有 SharedPreferences 的实现。 */
@Singleton
class SharedPrefsSshLoginKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : SshLoginKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun nameKey(id: String) = "key_${id}_name"
    private fun pathKey(id: String) = "key_${id}_path"
    private fun fingerprintKey(id: String) = "key_${id}_fingerprint"

    override fun entries(): List<SshLoginKey> =
        preferences.all.keys
            .filter { it.endsWith("_name") }
            .mapNotNull { raw ->
                val id = raw.removeSuffix("_name").removePrefix("key_")
                val name = preferences.getString(raw, null) ?: return@mapNotNull null
                val path = preferences.getString(pathKey(id), null) ?: return@mapNotNull null
                SshLoginKey(
                    id = id,
                    name = name,
                    path = path,
                    fingerprint = preferences.getString(fingerprintKey(id), null)
                )
            }

    override fun add(key: SshLoginKey) {
        preferences.edit()
            .putString(nameKey(key.id), key.name)
            .putString(pathKey(key.id), key.path)
            .putString(fingerprintKey(key.id), key.fingerprint)
            .apply()
    }

    override fun remove(id: String) {
        preferences.edit()
            .remove(nameKey(id))
            .remove(pathKey(id))
            .remove(fingerprintKey(id))
            .apply()
    }
}
