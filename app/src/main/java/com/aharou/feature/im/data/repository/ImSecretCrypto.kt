package com.aharou.feature.im.data.repository

import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger

/**
 * 机器人凭据（Bot Token / App Secret）落盘前的加密收口。
 *
 * 这几个字段是输入框逐字符写入的，[KeystoreCipher.encryptString] 在密钥库不可用（重启未解锁、
 * ROM 服务异常）时抛 [KeystoreCipher.KeystoreUnavailableException]，异常一旦冒到 ViewModel
 * 协程里就是崩溃。这里统一转成 null，由调用方返回 false 交给界面提示。
 */
internal object ImSecretCrypto {

    private const val TAG = "ImSecretCrypto"

    /** 加密成功返回密文；密钥库不可用返回 null（调用方据此跳过写入并告知用户）。 */
    fun encryptOrNull(plain: String): String? = try {
        KeystoreCipher.encryptString(plain)
    } catch (e: KeystoreCipher.KeystoreUnavailableException) {
        FileLogger.w(TAG, "凭据加密失败（系统密钥库不可用），本次不写入：${e.message}")
        null
    }
}
