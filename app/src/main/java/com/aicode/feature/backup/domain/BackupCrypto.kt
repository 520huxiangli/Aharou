package com.aicode.feature.backup.domain

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的对称加密：PBKDF2WithHmacSHA256 派生密钥 + 分块 AES/GCM/NoPadding。
 *
 * Android 的 AES/GCM（Conscrypt 的 OpenSSLAeadCipher）不支持流式输出：`Cipher.update` 恒返回空数组、
 * 把喂进去的数据全量攒进内部缓冲，直到 `doFinal` 才一次性产出。所以无法靠「多次 update」写出大文件——
 * 整个明文都会堆在 cipher 里。这里改为把明文切成固定大小的块，每块各自做一次独立的 AES-GCM（STREAM 结构）：
 *
 * - 整个文件只派生一把密钥（随机 salt），每块 nonce = 随机 8 字节基值 || 块序号（4 字节），保证同密钥不重用 nonce；
 * - 每块把「是否末块」写进 AAD，读端必须读到末块，防止密文被截断；块序号由 nonce 绑定，重排/换块会 tag 校验失败；
 * - 每块 doFinal 的产出有界，内存峰值与备份总大小无关。
 *
 * 文件头为 [MAGIC] + 版本号 + salt + nonce 基值，随后是分块序列。开头不带 MAGIC 的文件按旧版
 * 「salt + IV + 单次 GCM」格式解密（[decryptLegacy]），旧加密备份仍可导入。
 *
 * 口令不落盘、不记忆。GCM 自带完整性校验，口令错误或文件被篡改时解密抛 [BackupDecryptionException]。
 */
class BackupDecryptionException(cause: Throwable? = null) : IllegalArgumentException(
    "备份口令错误，或加密备份文件已损坏；若备份未加密，请留空口令",
    cause
)

object BackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val TAG_BYTES = GCM_TAG_BITS / 8

    /** 每块明文大小；每块独立做一次 GCM，单次 doFinal 产出有界。 */
    private const val CHUNK_SIZE = 256 * 1024

    private val MAGIC = "AICODEBK".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 0x02
    private const val NONCE_BASE_LEN = 8

    const val SALT_LEN = 16

    /** 旧格式的单次 GCM IV 长度；仅 [decryptLegacy] 使用。 */
    const val IV_LEN = 12

    private val random = SecureRandom()

    internal fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LEN_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /** 分块 nonce：随机基值（8 字节）+ 大端块序号（4 字节），共 96 位。 */
    private fun chunkNonce(nonceBase: ByteArray, index: Long): ByteArray {
        val nonce = ByteArray(nonceBase.size + 4)
        System.arraycopy(nonceBase, 0, nonce, 0, nonceBase.size)
        val o = nonceBase.size
        nonce[o] = (index ushr 24).toByte()
        nonce[o + 1] = (index ushr 16).toByte()
        nonce[o + 2] = (index ushr 8).toByte()
        nonce[o + 3] = index.toByte()
        return nonce
    }

    private fun aad(isLast: Boolean): ByteArray = byteArrayOf((if (isLast) 1 else 0).toByte())

    /** 流式加密：先写 v2 文件头，再把 [input] 分块加密写入 [output]。 */
    fun encryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonceBase = ByteArray(NONCE_BASE_LEN).also { random.nextBytes(it) }
        output.write(MAGIC)
        output.write(FORMAT_VERSION)
        output.write(salt)
        output.write(nonceBase)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buffer = ByteArray(CHUNK_SIZE)
        var pending = -1
        var index = 0L
        while (true) {
            var n = 0
            if (pending >= 0) {
                buffer[0] = pending.toByte()
                pending = -1
                n = 1
            }
            n += readUpTo(input, buffer, n, CHUNK_SIZE - n)

            // 读满一整块时需预读 1 字节才能判断是否末块，多出的字节留到下一块开头
            val isLast: Boolean
            if (n < CHUNK_SIZE) {
                isLast = true
            } else {
                val b = input.read()
                if (b < 0) isLast = true else { pending = b; isLast = false }
            }

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(nonceBase, index)))
            cipher.updateAAD(aad(isLast))
            val chunk = cipher.doFinal(buffer, 0, n)
            output.write(if (isLast) 1 else 0)
            writeInt(output, chunk.size)
            output.write(chunk)

            if (isLast) break
            index++
            check(index <= 0xFFFFFFFFL) { "备份文件过大，超出加密格式支持范围" }
        }
        output.flush()
    }

    /** 流式解密：v2 文件按分块解密，其余按旧格式（单次 GCM）兼容解密。 */
    fun decryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val header = readFully(input, MAGIC.size + 1)
        var isV2 = true
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                isV2 = false
                break
            }
        }
        if (!isV2) {
            decryptLegacy(input, output, password, header)
            return
        }
        if (header[MAGIC.size].toInt() != FORMAT_VERSION) {
            throw IllegalArgumentException("不支持的加密备份文件版本")
        }

        val salt = readFully(input, SALT_LEN)
        val nonceBase = readFully(input, NONCE_BASE_LEN)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var index = 0L
        while (true) {
            val flag = input.read()
            if (flag < 0) throw IllegalArgumentException("加密备份文件已截断")
            val len = readInt(input)
            if (len < TAG_BYTES || len > CHUNK_SIZE + TAG_BYTES) {
                throw IllegalArgumentException("加密备份文件已损坏")
            }
            val chunk = readFully(input, len)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(nonceBase, index)))
            cipher.updateAAD(aad(flag == 1))
            val plain = try {
                cipher.doFinal(chunk)
            } catch (e: BadPaddingException) {
                throw BackupDecryptionException(e)
            }
            output.write(plain)
            if (flag == 1) break
            index++
            check(index <= 0xFFFFFFFFL) { "加密备份文件已损坏" }
        }
        output.flush()
    }

    /** 旧格式：salt + IV + 单次 AES-GCM。仅用于兼容旧版导出的加密备份。 */
    private fun decryptLegacy(input: InputStream, output: OutputStream, password: CharArray, header: ByteArray) {
        val salt = ByteArray(SALT_LEN)
        val fromHeader = minOf(header.size, SALT_LEN)
        System.arraycopy(header, 0, salt, 0, fromHeader)
        if (fromHeader < SALT_LEN) readFully(input, salt, fromHeader, SALT_LEN - fromHeader)
        val iv = readFully(input, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) output.write(cipher.update(buffer, 0, n))
        }
        try {
            output.write(cipher.doFinal())
        } catch (e: BadPaddingException) {
            throw BackupDecryptionException(e)
        }
        output.flush()
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun readInt(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IllegalArgumentException("加密备份文件已截断")
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** 尽量读满 [len] 字节，只有流结束时才会少于 [len]。 */
    private fun readUpTo(input: InputStream, buffer: ByteArray, offset: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buffer, offset + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val result = ByteArray(len)
        readFully(input, result, 0, len)
        return result
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buffer, offset + read, len - read)
            if (n < 0) throw IllegalArgumentException("不是有效的加密 Aharou 备份文件")
            read += n
        }
    }
}
