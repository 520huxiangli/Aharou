package com.aharou.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aicode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 字符串密文前缀；不含此前缀的值按旧明文处理。 */
    private const val STRING_PREFIX = "AICODEENC1:"

    /** 字节密文文件头；用于区分加密文件与旧明文文件。 */
    private val BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
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
            generator.generateKey()
        }
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
        val iv = encrypted.copyOfRange(BYTE_MAGIC.size, BYTE_MAGIC.size + IV_LEN)
        val ciphertext = encrypted.copyOfRange(BYTE_MAGIC.size + IV_LEN, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun isEncryptedString(value: String?): Boolean = value != null && value.startsWith(STRING_PREFIX)

    /** 加密字符串；空串原样返回，避免把「未填写」变成非空密文。 */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 解密字符串；非密文（旧明文）原样返回。 */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        val body = Base64.getDecoder().decode(value.removePrefix(STRING_PREFIX))
        return String(decryptBytes(body), Charsets.UTF_8)
    }
}
