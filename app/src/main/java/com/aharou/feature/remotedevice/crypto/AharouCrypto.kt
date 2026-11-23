package com.aharou.feature.remotedevice.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 远端协作应用层加密用到的原语拼装。
 *
 * 只用 JDK / Android 自带实现，不引入第三方密码库、也不在这里发明算法：
 * P-256（secp256r1）ECDH → HKDF-SHA256 派生会话密钥 → AES-256-GCM 加密正文 → 常量时间比较确认值。
 *
 * 这里刻意只做「原语 + 线协议的固定常量」，会话生命周期、超时与回退策略分别在
 * 客户端 `AharouSessionClient` 与服务端 `RemoteEncryptionSessions` 里。
 *
 * 阶段常量与方向标识属于线协议：两端不一致就握不上手，改动前先确认对端版本。
 */
internal object AharouCrypto {
    /** HKDF 的 info：把这条会话的用途钉死，防止同一把 ECDH 结果被挪去派生别的密钥。 */
    const val HKDF_INFO = "aharou-remote-v1"

    /** 客户端→服务端方向标识；同时进 nonce 前缀与 AAD，两个方向的 nonce 空间因此不会重叠。 */
    const val DIR_C2S = 0x00000001

    /** 服务端→客户端方向标识。 */
    const val DIR_S2C = 0x00000002

    const val NONCE_LEN = 12
    const val KEY_LEN = 32
    const val CONFIRM_LEN = 16
    const val NONCE_RANDOM_LEN = 32

    private const val TAG_BITS = 128
    private const val CURVE = "secp256r1"
    private const val CONFIRM_LABEL = "confirm"
    private const val UNCOMPRESSED_POINT_PREFIX = 0x04

    private val random = SecureRandom()

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec(CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun newRandom(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    fun newEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }
            .generateKeyPair()

    /** 公钥编码成未压缩点 `0x04 || X(32) || Y(32)`：线协议里不传 DER，省掉两端解析结构差异。 */
    fun encodePublicKey(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(UNCOMPRESSED_POINT_PREFIX.toByte()) +
            toFixed32(point.affineX) + toFixed32(point.affineY)
    }

    /** 解析对端的未压缩点；长度或前缀不对一律返回 null（由调用方按握手失败处理）。 */
    fun decodePublicKey(raw: ByteArray): PublicKey? {
        if (raw.size != 65 || raw[0].toInt() != UNCOMPRESSED_POINT_PREFIX) return null
        return runCatching {
            val point = ECPoint(
                BigInteger(1, raw.copyOfRange(1, 33)),
                BigInteger(1, raw.copyOfRange(33, 65))
            )
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256))
        }.getOrNull()
    }

    fun sharedSecret(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peerPublic, true)
            generateSecret()
        }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** 与 `RemotePairingStore` 落盘用的 token 摘要同形：两端都能算出同一个值。 */
    fun hexSha256(text: String): String =
        sha256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-SHA256（RFC 5869）：extract + expand。L 只用到 32，但仍按标准循环写，免得以后改长度时出错。 */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - offset)
            System.arraycopy(block, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    /** 解锁确认值：只回「知道正确 token」这一个事实，别的什么都不证明。 */
    fun confirmValue(key: ByteArray): ByteArray =
        hmacSha256(key, CONFIRM_LABEL.toByteArray(Charsets.UTF_8)).copyOf(CONFIRM_LEN)

    fun nonce(direction: Int, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        nonce[0] = (direction ushr 24 and 0xFF).toByte()
        nonce[1] = (direction ushr 16 and 0xFF).toByte()
        nonce[2] = (direction ushr 8 and 0xFF).toByte()
        nonce[3] = (direction and 0xFF).toByte()
        for (i in 0 until 8) {
            nonce[4 + i] = (counter ushr (56 - 8 * i) and 0xFF).toByte()
        }
        return nonce
    }

    fun directionBytes(direction: Int): ByteArray = byteArrayOf(
        (direction ushr 24 and 0xFF).toByte(),
        (direction ushr 16 and 0xFF).toByte(),
        (direction ushr 8 and 0xFF).toByte(),
        (direction and 0xFF).toByte()
    )

    /** 加密一帧：`nonce(12) || ciphertext+tag`。AAD 与 nonce 前缀共用方向标识。 */
    fun seal(key: ByteArray, direction: Int, counter: Long, plaintext: ByteArray): ByteArray {
        val nonce = nonce(direction, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(directionBytes(direction))
        return nonce + cipher.doFinal(plaintext)
    }

    /**
     * 解一帧。帧里的 nonce 必须**逐字节等于本端按期望计数器算出的那一个**：
     * 只比对解密是否成功不够——攻击者可以自带 nonce 重放旧帧。对不上直接丢，计数器不推进。
     */
    fun open(key: ByteArray, direction: Int, counter: Long, frame: ByteArray): ByteArray? {
        if (frame.size < NONCE_LEN + TAG_BITS / 8) return null
        val nonce = frame.copyOfRange(0, NONCE_LEN)
        if (!MessageDigest.isEqual(nonce, nonce(direction, counter))) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(directionBytes(direction))
            cipher.doFinal(frame, NONCE_LEN, frame.size - NONCE_LEN)
        }.getOrNull()
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun base64OrNull(text: String?): ByteArray? {
        if (text.isNullOrBlank()) return null
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            // BigInteger 会带一个符号位前导 0，裁回定长。曲线点本身不可能超过 32 字节。
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
