package com.aharou.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感数据的对称加密：密钥存放在 Android Keystore（不可导出，有 TEE 时受硬件保护），
 * 数据用 AES-256-GCM 加密。用于私钥文件与数据库里的密码/API Key 等字段。
 *
 * 密钥与设备绑定：清除应用数据、换机直拷数据目录后密文不可解，属预期代价；
 * 跨设备迁移走备份（备份导出为明文、导入时按新设备密钥重新加密）。
 *
 * 明文兼容：解密入口对非密文输入原样返回，便于旧明文数据的平滑迁移。
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aicode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 密钥生成重试次数与间隔：设备重启后 keystore2 还没就绪是最常见的失败原因，等一下就过了。 */
    private const val GENERATE_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 150L

    /** 密钥库不可用（重启未解锁、ROM 服务异常、密钥被作废且重建失败）。加密不能降级，由调用方提示。 */
    class KeystoreUnavailableException(cause: Throwable?) :
        IllegalStateException("系统密钥库不可用，密钥生成失败", cause)

    /** 字符串密文前缀；不含此前缀的值按旧明文处理。 */
    private const val STRING_PREFIX = "AICODEENC1:"

    /** 字节密文文件头；用于区分加密文件与旧明文文件。 */
    private val BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        // 读已有条目也可能抛 ProviderException（服务未就绪），一并按「需要生成」处理。
        val existing = runCatching {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
        existing ?: generateWithRetry()
    }

    /**
     * 生成密钥并重试一次。
     *
     * `generateKey()` 抛的是 `ProviderException`（RuntimeException），不是 `GeneralSecurityException`，
     * 下层那两处 catch 兜不住，所以必须在源头处理——否则密钥库一不可用就直接把 App 打挂
     * （群友 2.47.3 的崩溃就是这么来的）。
     */
    private fun generateWithRetry(): SecretKey {
        var last: Exception? = null
        repeat(GENERATE_ATTEMPTS) { attempt ->
            try {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                return generator.generateKey()
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "生成密钥失败（第 ${attempt + 1}/$GENERATE_ATTEMPTS 次）", e)
                if (attempt + 1 < GENERATE_ATTEMPTS) {
                    runCatching { Thread.sleep(RETRY_DELAY_MS) }
                }
            }
        }
        throw KeystoreUnavailableException(last)
    }

    fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return BYTE_MAGIC + iv + ciphertext
    }

    fun isEncryptedBytes(bytes: ByteArray): Boolean =
        bytes.size >= BYTE_MAGIC.size + IV_LEN && BYTE_MAGIC.indices.all { bytes[it] == BYTE_MAGIC[it] }

    fun decryptBytes(encrypted: ByteArray): ByteArray {
        require(isEncryptedBytes(encrypted)) { "不是加密数据" }
        return try {
            val iv = encrypted.copyOfRange(BYTE_MAGIC.size, BYTE_MAGIC.size + IV_LEN)
            val ciphertext = encrypted.copyOfRange(BYTE_MAGIC.size + IV_LEN, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // 密钥与密文不匹配（换机、清数据后 Keystore 重建、字段被截断）或密钥库暂时不可用时
            // 返回空数据而非抛出，避免单个坏字段把整条读取流程带崩。
            Log.w(TAG, "密文解密失败，按空数据降级", e)
            ByteArray(0)
        }
    }

    fun isEncryptedString(value: String?): Boolean = value != null && value.startsWith(STRING_PREFIX)

    /** 加密字符串；空串原样返回，避免把「未填写」变成非空密文。 */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 解密字符串；非密文（旧明文）原样返回，密文解不开时降为空串。 */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        return try {
            val body = Base64.getDecoder().decode(value.removePrefix(STRING_PREFIX))
            String(decryptBytes(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密文结构损坏（坏 base64、被截断）时按空串降级。
            Log.w(TAG, "密文字符串格式不合法，按空值降级", e)
            ""
        }
    }
}
