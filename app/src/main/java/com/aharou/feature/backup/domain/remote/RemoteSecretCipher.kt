package com.aharou.feature.backup.domain.remote

import com.aharou.core.security.KeystoreCipher

/**
 * 网络备份目标敏感字段（口令 / Secret Key / 会话令牌）的加解密口子。
 *
 * 抽出接口只为让 [com.aharou.feature.backup.data.BackupTargetRepository] 能在纯 JVM 单测里
 * 注入一个不依赖 Android Keystore 的实现；生产注入 [KeystoreRemoteSecretCipher]。
 */
interface RemoteSecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(value: String): String
}

/** 生产实现：委托 [KeystoreCipher]（AES-256-GCM，密文前缀 `AICODEENC1:`）。 */
object KeystoreRemoteSecretCipher : RemoteSecretCipher {
    override fun encrypt(plain: String): String = KeystoreCipher.encryptString(plain)
    override fun decrypt(value: String): String = KeystoreCipher.decryptString(value)
}
