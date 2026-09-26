package com.aharou.feature.backup.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val password = "correct horse battery staple".toCharArray()

    private fun encrypt(plain: ByteArray, pw: CharArray = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(plain), out, pw)
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray, pw: CharArray = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.decryptStream(ByteArrayInputStream(data), out, pw)
        return out.toByteArray()
    }

    @Test
    fun encryptStream_and_decryptStream_roundTrip() {
        val plain = "AiCode encrypted backup payload".toByteArray(Charsets.UTF_8)
        val encryptedBytes = encrypt(plain)

        assertTrue(encryptedBytes.size > BackupCrypto.SALT_LEN + BackupCrypto.IV_LEN)
        assertArrayEquals(plain, decrypt(encryptedBytes))
    }

    @Test
    fun encryptStream_writesVersionedHeader() {
        val encryptedBytes = encrypt("payload".toByteArray(Charsets.UTF_8))

        assertArrayEquals("AICODEBK".toByteArray(Charsets.US_ASCII), encryptedBytes.copyOfRange(0, 8))
        assertEquals(2, encryptedBytes[8].toInt())
    }

    @Test
    fun encryptStream_multiChunk_roundTrip() {
        val plain = ByteArray(700_000) { (it % 251).toByte() }

        val encryptedBytes = encrypt(plain)

        // 分块开销 = 每块 (last 1 + 长度 4 + tag 16)，远小于明文
        assertTrue(encryptedBytes.size > plain.size)
        assertTrue(encryptedBytes.size < plain.size + 1024)
        assertArrayEquals(plain, decrypt(encryptedBytes))
    }

    @Test
    fun encryptStream_emptyInput_roundTrip() {
        assertArrayEquals(ByteArray(0), decrypt(encrypt(ByteArray(0))))
    }

    @Test
    fun decryptStream_wrongPassword_throwsBackupDecryptionException() {
        val encryptedBytes = encrypt("encrypted payload".toByteArray(Charsets.UTF_8))

        assertThrows(BackupDecryptionException::class.java) {
            decrypt(encryptedBytes, "wrong-password".toCharArray())
        }
    }

    @Test
    fun decryptStream_dataTooShortForHeader_throwsIllegalArgumentException() {
        val shortData = ByteArray(BackupCrypto.SALT_LEN + BackupCrypto.IV_LEN - 1)

        assertThrows(IllegalArgumentException::class.java) {
            decrypt(shortData)
        }
    }

    @Test
    fun decryptStream_truncated_throwsIllegalArgumentException() {
        // 空明文只产生一个块（last 1 + 长度 4 + tag 16 = 21 字节），整块丢弃后读端应报截断
        val encryptedBytes = encrypt(ByteArray(0))
        val truncated = encryptedBytes.copyOf(encryptedBytes.size - 21)

        assertThrows(IllegalArgumentException::class.java) {
            decrypt(truncated)
        }
    }

    @Test
    fun decryptStream_tamperedCiphertext_throwsBackupDecryptionException() {
        val encryptedBytes = encrypt("payload to be tampered".toByteArray(Charsets.UTF_8))
        encryptedBytes[encryptedBytes.size - 1] = (encryptedBytes[encryptedBytes.size - 1].toInt() xor 0x01).toByte()

        assertThrows(BackupDecryptionException::class.java) {
            decrypt(encryptedBytes)
        }
    }

    /** 旧版格式：salt + IV + 单次 GCM，应仍能解密。 */
    @Test
    fun decryptStream_legacyFormat_stillSupported() {
        val plain = "legacy encrypted payload".toByteArray(Charsets.UTF_8)
        val legacyPassword = "legacy-pw".toCharArray()
        val salt = ByteArray(BackupCrypto.SALT_LEN) { it.toByte() }
        val iv = ByteArray(BackupCrypto.IV_LEN) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            BackupCrypto.deriveKey(legacyPassword, salt),
            GCMParameterSpec(128, iv)
        )
        val legacy = salt + iv + cipher.doFinal(plain)

        assertArrayEquals(plain, decrypt(legacy, legacyPassword))
    }

    @Test
    fun decryptStream_legacyFormat_wrongPassword_throwsBackupDecryptionException() {
        val salt = ByteArray(BackupCrypto.SALT_LEN) { it.toByte() }
        val iv = ByteArray(BackupCrypto.IV_LEN) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            BackupCrypto.deriveKey("right-password".toCharArray(), salt),
            GCMParameterSpec(128, iv)
        )
        val legacy = salt + iv + cipher.doFinal("legacy payload".toByteArray(Charsets.UTF_8))

        assertThrows(BackupDecryptionException::class.java) {
            decrypt(legacy, "wrong-password".toCharArray())
        }
    }
}
