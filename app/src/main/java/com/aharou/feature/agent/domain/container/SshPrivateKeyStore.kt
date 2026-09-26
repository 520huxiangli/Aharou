package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.SSHClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 私钥文件的加密读写：私钥以 Keystore 加密后的密文落盘，读取时在内存解密后交给 sshj。
 *
 * 明文只存在于内存中，容器内进程即便读到私钥文件也只是密文。旧版明文文件在首次读取时惰性加密回写。
 */
@Singleton
class SshPrivateKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SshPrivateKeyStore"
    }

    /** 加密写入私钥文件（覆盖原内容）。 */
    fun write(path: String, plain: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(KeystoreCipher.encryptBytes(plain))
    }

    /** 读取私钥明文 PEM：密文解密，旧明文原样返回并惰性加密回写。 */
    fun readPem(path: String): String {
        val file = File(path)
        val bytes = file.readBytes()
        if (!KeystoreCipher.isEncryptedBytes(bytes)) {
            // 旧版明文文件：本次按明文使用，并尝试加密回写（失败不阻断连接）。
            runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
                .onFailure { FileLogger.w(TAG, "私钥明文迁移加密失败: ${file.name}", it) }
            return String(bytes, Charsets.UTF_8)
        }
        return String(KeystoreCipher.decryptBytes(bytes), Charsets.UTF_8)
    }

    /** 计算私钥对应公钥的 SHA-256 指纹；加密私钥（需口令）无法解析时返回 null。 */
    fun fingerprint(pem: String): String? = try {
        val keyProvider = SSHClient().loadKeys(pem, null, null)
        sshHostKeyFingerprint(keyProvider.getPublic())
    } catch (e: Exception) {
        null
    }
}
