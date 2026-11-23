package com.aharou.feature.remotedevice.crypto

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpEncryption
import com.aharou.feature.agent.domain.mcp.McpEnvelope
import com.aharou.feature.agent.domain.mcp.McpException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 客户端侧的应用层加密通道：对着一台声明支持加密的对端做一次握手，之后把 `/mcp` 的请求体封成帧。
 *
 * 只在配置明确要求加密（`encrypted=true`）时才会被构造，因此失败一律上抛、不退回明文：
 * 静默降级等于每次重连都允许中间人把加密剥掉。错误消息面向连接状态，细节原因同时记日志，
 * 且绝不含 token 或明文片段。
 *
 * 服务端只存 token 的 SHA-256（见 `RemotePairingStore`），所以两端派生密钥时掺的是这份摘要，
 * 而不是明文 token——它同样是不上线路的预共享秘密，且摘要本身不是可用的 Bearer 凭据。
 */
internal class AharouSessionClient(
    private val handshakeUrl: String,
    private val token: String,
    private val peerLabel: String
) : McpEncryption {

    private companion object {
        const val TAG = "RemoteEncryption"
        const val TIMEOUT_MS = 8_000
        const val SESSION_HEADER = "X-Aharou-Session"
        const val ENC_HEADER = "X-Aharou-Enc"
    }

    /** 会话建立后的全部状态；计数器各自单调递增，加/解密成功才推进。 */
    private class Established(val key: ByteArray, val sessionId: String) {
        var c2s = 0L
        var s2c = 0L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var established: Established? = null

    override suspend fun prepare() {
        established?.let { return }
        mutex.withLock {
            established?.let { return@withLock }
            val attempt = runCatching { withContext(Dispatchers.IO) { handshake() } }
            val state = attempt.getOrNull()
                ?: throw McpException(
                    message = "这条连接要求应用层加密，但握手失败：" +
                        (attempt.exceptionOrNull()?.message ?: "未知原因")
                )
            established = state
            FileLogger.i(TAG, "与 $peerLabel 已建立应用层加密会话（${state.sessionId.take(8)}…）")
        }
    }

    override fun seal(plain: ByteArray): McpEnvelope {
        val state = established ?: throw McpException(message = "应用层加密会话尚未建立")
        return synchronized(state) { sealLocked(state, plain) }
    }

    private fun sealLocked(state: Established, plain: ByteArray): McpEnvelope {
        // token 封在密文最前面的单独一行：放在请求头里等于没加密（见 StreamableHttpTransport 的说明）。
        val payload = token.toByteArray(Charsets.UTF_8) + '\n'.code.toByte() + plain
        val frame = runCatching {
            AharouCrypto.seal(state.key, AharouCrypto.DIR_C2S, state.c2s, payload)
        }.getOrElse {
            // 会话已经不可用：丢掉它，让下一次 prepare 重新握手，别让后续每次调用都撞同一面墙。
            established = null
            throw McpException(message = "加密请求构造失败: ${it.message}", cause = it)
        }
        state.c2s += 1
        return McpEnvelope(
            body = frame,
            headers = mapOf(ENC_HEADER to "1", SESSION_HEADER to state.sessionId)
        )
    }

    override fun open(frame: ByteArray): ByteArray {
        val state = established ?: throw McpException(message = "应用层加密会话尚未建立")
        return synchronized(state) { openLocked(state, frame) }
    }

    private fun openLocked(state: Established, frame: ByteArray): ByteArray {
        val plain = AharouCrypto.open(state.key, AharouCrypto.DIR_S2C, state.s2c, frame)
            ?: run {
                // 计数器错位（对端重启/空闲回收）之后这条会话再也对不上：丢掉，下次重新握手。
                established = null
                throw McpException(
                    message = "响应解密失败（会话 ${state.sessionId.take(8)}…）：" +
                        "可能是计数器错位或对端重启，重新连接一次即可"
                )
            }
        // 计数器只在解密成功后推进：失败帧被丢弃，不会让后续帧整体错位。
        state.s2c += 1
        return plain
    }

    override fun reset() {
        established = null
    }

    /** 两步握手：先换临时公钥，再回确认值。任一阶段失败都以异常上抛，由 [prepare] 统一包装。 */
    private fun handshake(): Established {
        val keyPair = AharouCrypto.newEphemeralKeyPair()
        val nonceC = AharouCrypto.newRandom(AharouCrypto.NONCE_RANDOM_LEN)
        val open = buildJsonObject {
            put("ephemeral_pub", AharouCrypto.base64(AharouCrypto.encodePublicKey(keyPair.public)))
            put("nonce_c", AharouCrypto.base64(nonceC))
        }.toString()
        val (openStatus, openBody) = postJson(open)
        if (openStatus !in 200..299) {
            throw IllegalStateException("握手第一步被拒（HTTP $openStatus）")
        }
        val reply = runCatching { json.parseToJsonElement(openBody).jsonObject }.getOrNull()
            ?: throw IllegalStateException("握手第一步响应不是 JSON")
        val peerPublic = AharouCrypto.decodePublicKey(
            AharouCrypto.base64OrNull(reply["ephemeral_pub"]?.jsonPrimitive?.contentOrNull) ?: ByteArray(0)
        ) ?: throw IllegalStateException("对端临时公钥不是合法的 P-256 点")
        val nonceS = AharouCrypto.base64OrNull(reply["nonce_s"]?.jsonPrimitive?.contentOrNull)
            ?: throw IllegalStateException("握手响应缺少 nonce_s")
        val sessionId = reply["session"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isBlank()) throw IllegalStateException("握手响应缺少会话 id")

        val shared = AharouCrypto.sharedSecret(keyPair.private, peerPublic)
        val verifier = AharouCrypto.hexSha256(token).toByteArray(Charsets.UTF_8)
        val salt = AharouCrypto.sha256(nonceC + nonceS + verifier)
        val key = AharouCrypto.hkdfSha256(
            ikm = shared,
            salt = salt,
            info = AharouCrypto.HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = AharouCrypto.KEY_LEN
        )

        val confirm = buildJsonObject {
            put("session", sessionId)
            put("confirm", AharouCrypto.base64(AharouCrypto.confirmValue(key)))
        }.toString()
        val (confirmStatus, _) = postJson(confirm)
        if (confirmStatus !in 200..299) {
            throw IllegalStateException("握手的确认值被拒（HTTP $confirmStatus），多半是两端 token 不一致")
        }
        return Established(key, sessionId)
    }

    private fun postJson(payload: String): Pair<Int, String> {
        val connection = (URL(handshakeUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to body
        } finally {
            connection.disconnect()
        }
    }
}
