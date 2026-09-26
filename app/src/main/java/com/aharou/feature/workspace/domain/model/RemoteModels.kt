package com.aharou.feature.workspace.domain.model

enum class RemoteProtocol {
    SFTP,
    FTP
}

data class RemoteConnection(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "",
    /** 'password' 或 'key'，与 [RemoteConnectionEntity.authType] 对应。 */
    val authType: String = "password",
    /** 密码（authType=password）或私钥文件路径（authType=key）。 */
    val authData: String = "",
    /** 私钥口令（authType=key 且私钥加密时）。 */
    val passphrase: String? = null
)

data class RemoteMount(
    val id: String,
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    val autoConnect: Boolean = true,
    // Provide a convenient reference to the underlying connection when used in UI
    val connection: RemoteConnection? = null
)
