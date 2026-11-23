package com.aharou.feature.remotedevice.server

import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.remotedevice.crypto.AharouCrypto
import java.security.MessageDigest
import java.security.PrivateKey

/**
 * 服务端的握手会话表：只管临时密钥、会话密钥、计数器与生命周期，**不做鉴权**。
 *
 * 为什么鉴权不在这里：握手只能证明「对方算得出同一把 key」，而算得出只意味着它知道
 * pairing.json 里那份 token 摘要；真正能不能调动作，仍由每条加密请求里带进来的明文 token
 * 决定（见 `RemoteMcpServer.handleEncryptedMcp`）。否则，能读到配对文件的本地攻击者就能
 * 直接拿到动作执行权——那比现在的明文通道更糟。
 *
 * 线程模型：连接是并发处理的，所有状态变更都走 [lock]；密码学运算也放在锁内（单次几十微秒）。
 */
internal class RemoteEncryptionSessions(
    /** 已授权设备的 token 摘要（`RemotePairingStore` 落盘用的那种形态）；确认值逐个候选验。 */
    private val tokenHashes: suspend () -> List<String>
) {
    private companion object {
        const val TAG = "RemoteEncryption"

        /** 握手第一步到第二步之间允许的间隔：够一次局域网往返，又不给挂起会话堆积的机会。 */
        const val PENDING_TTL_MS = 30_000L

        /** 会话空闲多久回收：MCP 调用是低频的，10 分钟足够，也让长期不用的密钥材料尽早消失。 */
        const val SESSION_IDLE_MS = 10 * 60 * 1000L

        /** 同时保留的会话上限：超出丢最旧的，避免一条连接不动却长期占着密钥材料。 */
        const val MAX_SESSIONS = 8
        const val MAX_PENDING = 8
    }

    internal class ActiveSession internal constructor(
        val id: String,
        internal val key: ByteArray
    ) {
        internal var c2s = 0L
        internal var s2c = 0L
        internal var lastUsedAt = System.currentTimeMillis()
    }

    private class Pending(
        val id: String,
        val createdAt: Long,
        val privateKey: PrivateKey,
        val shared: ByteArray,
        val nonceC: ByteArray,
        val nonceS: ByteArray
    )

    internal sealed interface BeginOutcome {
        class Ok(val sessionId: String, val ephemeralPub: ByteArray, val nonceS: ByteArray) : BeginOutcome

        class Rejected(val code: String, val reason: String) : BeginOutcome
    }

    internal sealed interface ConfirmOutcome {
        data object Ok : ConfirmOutcome

        class Rejected(val code: String, val reason: String) : ConfirmOutcome
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val sessions = LinkedHashMap<String, ActiveSession>()

    /**
     * 握手第一步：收下对端临时公钥与随机数，回自己的临时公钥、随机数与一个不透明会话 id。
     * 此时还没有任何鉴权发生，所以这里不做「放行/拒绝动作」的判断，只做输入合法性检查。
     */
    fun begin(clientPublicRaw: ByteArray, nonceC: ByteArray): BeginOutcome {
        if (nonceC.size != AharouCrypto.NONCE_RANDOM_LEN) {
            return BeginOutcome.Rejected("enc_handshake_failed", "nonce_c_must_be_32_bytes")
        }
        val peerPublic = AharouCrypto.decodePublicKey(clientPublicRaw)
            ?: return BeginOutcome.Rejected("enc_handshake_failed", "invalid_ephemeral_pub")
        val keyPair = AharouCrypto.newEphemeralKeyPair()
        val shared = runCatching { AharouCrypto.sharedSecret(keyPair.private, peerPublic) }.getOrNull()
            ?: return BeginOutcome.Rejected("enc_handshake_failed", "ecdh_failed")
        val nonceS = AharouCrypto.newRandom(AharouCrypto.NONCE_RANDOM_LEN)
        val sessionId = newSessionId()
        val entry = Pending(sessionId, System.currentTimeMillis(), keyPair.private, shared, nonceC, nonceS)
        synchronized(lock) {
            pruneLocked()
            while (pending.size >= MAX_PENDING) {
                val oldest = pending.keys.firstOrNull() ?: break
                pending.remove(oldest)
            }
            pending[sessionId] = entry
        }
        return BeginOutcome.Ok(sessionId, AharouCrypto.encodePublicKey(keyPair.public), nonceS)
    }

    /**
     * 握手第二步：拿确认值逐个候选 token 摘要试派生密钥。
     *
     * 逐个试而不是要求对端再传 token：token 不上线路是本协议的目的，而摘要撞上才算同一条凭据。
     * 无论成败都作废这条待确认会话（一次性），避免被拿来反复试。
     */
    suspend fun confirm(sessionId: String, confirmValue: ByteArray): ConfirmOutcome {
        if (confirmValue.size != AharouCrypto.CONFIRM_LEN) {
            return ConfirmOutcome.Rejected("enc_handshake_failed", "confirm_must_be_16_bytes")
        }
        val entry = synchronized(lock) {
            pruneLocked()
            pending.remove(sessionId)
        } ?: return ConfirmOutcome.Rejected("enc_handshake_unknown", "session_expired_or_unknown")

        if (System.currentTimeMillis() - entry.createdAt > PENDING_TTL_MS) {
            return ConfirmOutcome.Rejected("enc_handshake_unknown", "session_expired")
        }

        val candidates = runCatching { tokenHashes() }.getOrDefault(emptyList())
        for (hash in candidates) {
            val key = deriveKey(entry, hash)
            val expected = AharouCrypto.confirmValue(key)
            // 常量时间比较：逐个候选的耗时不能随前缀匹配长度变化。
            if (MessageDigest.isEqual(expected, confirmValue)) {
                synchronized(lock) {
                    pruneLocked()
                    while (sessions.size >= MAX_SESSIONS) {
                        val oldest = sessions.minByOrNull { it.value.lastUsedAt }?.key ?: break
                        sessions.remove(oldest)
                    }
                    sessions[entry.id] = ActiveSession(entry.id, key)
                }
                FileLogger.i(TAG, "加密会话已建立（${entry.id.take(8)}…）")
                return ConfirmOutcome.Ok
            }
        }
        FileLogger.w(TAG, "握手确认值不匹配（会话 ${sessionId.take(8)}…），未建立会话")
        return ConfirmOutcome.Rejected("enc_handshake_rejected", "token_mismatch")
    }

    /** 按会话 id 取一条仍然有效的会话；空闲超时的顺手清掉。 */
    fun active(sessionId: String): ActiveSession? = synchronized(lock) {
        pruneLocked()
        sessions[sessionId]?.also { it.lastUsedAt = System.currentTimeMillis() }
    }

    /** 解一帧请求体；解不开（计数器不匹配 / 认证失败）返回 null，且**不推进**计数器。 */
    fun open(session: ActiveSession, frame: ByteArray): ByteArray? {
        var plain: ByteArray? = null
        synchronized(lock) {
            val opened = AharouCrypto.open(session.key, AharouCrypto.DIR_C2S, session.c2s, frame)
            if (opened != null) {
                session.c2s += 1
                session.lastUsedAt = System.currentTimeMillis()
                plain = opened
            }
        }
        return plain
    }

    /** 封一帧响应体；失败返回 null（调用方记录并以明文回，但会带不上加密头）。 */
    fun seal(session: ActiveSession, plaintext: ByteArray): ByteArray? {
        var frame: ByteArray? = null
        synchronized(lock) {
            val sealed = runCatching {
                AharouCrypto.seal(session.key, AharouCrypto.DIR_S2C, session.s2c, plaintext)
            }.getOrNull()
            if (sealed != null) {
                session.s2c += 1
                session.lastUsedAt = System.currentTimeMillis()
                frame = sealed
            }
        }
        return frame
    }

    /** 清空全部握手与会话（用户关掉加密总开关时调用）：之后任何旧会话都会得到 enc_session_unknown。 */
    fun clear() {
        synchronized(lock) {
            pending.clear()
            sessions.clear()
        }
    }

    private fun deriveKey(entry: Pending, tokenHash: String): ByteArray {
        val verifier = tokenHash.toByteArray(Charsets.UTF_8)
        val salt = AharouCrypto.sha256(entry.nonceC + entry.nonceS + verifier)
        return AharouCrypto.hkdfSha256(
            ikm = entry.shared,
            salt = salt,
            info = AharouCrypto.HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = AharouCrypto.KEY_LEN
        )
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        pending.values.removeAll { now - it.createdAt > PENDING_TTL_MS }
        sessions.values.removeAll { now - it.lastUsedAt > SESSION_IDLE_MS }
    }

    private fun newSessionId(): String =
        Base64.encodeToString(
            AharouCrypto.newRandom(32),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
