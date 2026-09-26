package com.aharou.feature.agent.domain.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** SSH 主机指纹的持久化存储，key 为 `host:port`。 */
interface SshHostKeyStore {
    fun get(host: String, port: Int): String?
    fun save(host: String, port: Int, fingerprint: String)
    fun remove(host: String, port: Int)
    fun entries(): Map<String, String>
}

private const val PREFS_NAME = "ssh_host_keys"

/** 基于应用私有 SharedPreferences 的实现。 */
@Singleton
class SharedPrefsSshHostKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : SshHostKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(host: String, port: Int): String = "$host:$port"

    override fun get(host: String, port: Int): String? = preferences.getString(key(host, port), null)

    override fun save(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString(key(host, port), fingerprint).apply()
    }

    override fun remove(host: String, port: Int) {
        preferences.edit().remove(key(host, port)).apply()
    }

    override fun entries(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()
}

/** 计算主机公钥的 SHA-256 指纹，形如 `SHA256:<base64>`，与 ssh-keygen -lf 的格式一致。 */
fun sshHostKeyFingerprint(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** 主机密钥需要用户确认（首次连接或已保存指纹变化）。changed=false 首次，true 指纹变化。 */
class SshHostKeyPendingException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
) : Exception("SSH 主机密钥需要确认: $host:$port")

class SshHostKeyVerifier @Inject constructor(
    private val store: SshHostKeyStore
) : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    /** 最近一次校验失败（待确认）详情；sshj 会把 verify 抛出的异常包装成 TransportException，
     *  连接层无法按异常类型捕获，故通过此字段传递。consumePending 读取后清空。 */
    @Volatile
    private var pending: SshHostKeyPendingException? = null

    fun consumePending(): SshHostKeyPendingException? {
        val p = pending
        pending = null
        return p
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        pending = null
        val fingerprint = sshHostKeyFingerprint(key)
        val saved = store.get(hostname, port)
        if (saved == null) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = false)
            pending = e
            throw e
        }
        if (saved != fingerprint) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = true)
            pending = e
            throw e
        }
        return true
    }
}
