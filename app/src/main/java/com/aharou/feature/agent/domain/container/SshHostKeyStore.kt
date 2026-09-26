package com.aharou.feature.agent.domain.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

@Singleton
class SshHostKeyVerifier @Inject constructor(
    private val store: SshHostKeyStore
) : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    /** 最近一次校验失败（待确认）详情；sshj 会把 verify 抛出的异常包装成 TransportException，
     *  连接层无法按异常类型捕获，故通过此字段传递。consumePending 读取后清空。 */
    @Volatile
    private var pending: SshHostKeyPendingException? = null

    /** 全局待确认提醒：任意路径（工作区连接 / 文件层 / 同步 / 测试）撞到未确认指纹都会更新，
     *  由 App 根部「温柔提示」观察，引导用户确认而不是默默失败。 */
    private val _globalPending = MutableStateFlow<SshHostKeyPendingException?>(null)
    val globalPending: StateFlow<SshHostKeyPendingException?> = _globalPending.asStateFlow()

    /** 用户点过「拒绝/暂不」的指纹 → 时间戳；冷却期内不再打扰（按 host:port:fingerprint 去重）。 */
    private val dismissedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun alertKey(e: SshHostKeyPendingException) = "${e.host}:${e.port}:${e.fingerprint}"

    /** 登记全局提醒：同一条已在展示、或刚被「暂不」不久，都不重复打扰。 */
    private fun notifyGlobal(e: SshHostKeyPendingException) {
        val cur = _globalPending.value
        if (cur != null && alertKey(cur) == alertKey(e)) return
        val last = dismissedAt[alertKey(e)] ?: 0L
        if (System.currentTimeMillis() - last < DISMISS_COOLDOWN_MS) return
        _globalPending.value = e
    }

    fun consumePending(): SshHostKeyPendingException? {
        val p = pending
        pending = null
        // 本地弹窗（测试连通性路径）即将接手：全局提醒让位，避免双弹窗。
        _globalPending.value = null
        return p
    }

    /** 全局提醒里用户点了「信任」：保存指纹并清掉待确认状态。 */
    fun confirmGlobalPending() {
        val e = _globalPending.value ?: return
        store.save(e.host, e.port, e.fingerprint)
        pending = null
        _globalPending.value = null
    }

    /** 全局提醒里用户点了「拒绝/暂不」：记冷却，之后重试仍会再提醒。 */
    fun dismissGlobalPending() {
        val e = _globalPending.value ?: return
        dismissedAt[alertKey(e)] = System.currentTimeMillis()
        _globalPending.value = null
    }

    private companion object {
        const val DISMISS_COOLDOWN_MS = 3 * 60 * 1000L
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        pending = null
        val fingerprint = sshHostKeyFingerprint(key)
        val saved = store.get(hostname, port)
        if (saved == null) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = false)
            pending = e
            notifyGlobal(e)
            throw e
        }
        if (saved != fingerprint) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = true)
            pending = e
            notifyGlobal(e)
            throw e
        }
        return true
    }
}
