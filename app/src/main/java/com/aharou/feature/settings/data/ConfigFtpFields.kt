package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.workspace.domain.remote.ftp.FtpServerManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * 内置 FTP 服务端设置 → 配置通道。
 *
 * 存储由 [FtpServerManager] 独占（DataStore `ftp_server_prefs`），本文件不直接读写该
 * DataStore，一律经其 `saveConfig(...)` 写回——服务在运行时会先停后启，让端口 / 账号类
 * 改动即时生效；绕开它单独写 DataStore 会丢掉这段重启逻辑，且不会刷新对外展示的 serverUrl。
 *
 * [FtpServerManager.saveConfig] 一次性接收全部五个值，故每个字段写回时其余字段取当前
 * StateFlow 的实时值，保证只改动目标字段。
 */
@Singleton
class ConfigFtpFields @Inject constructor(
    private val ftp: FtpServerManager,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "ftp.port",
                displayName = "FTP 端口",
                description = "内置 FTP 服务端监听端口（1~65535），默认 2121。改动会重启正在运行的服务。",
                valueSchema = ConfigSchema.Int(min = 1, max = 65535),
                revertable = true,
                reader = { ConfigValue.Int(ftp.port.value) },
                writer = { v ->
                    val port = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("integer")
                    persist(port = port)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "ftp.username",
                displayName = "FTP 用户名",
                description = "登录用户名（匿名关闭时生效），默认 aicode。改动会重启正在运行的服务。",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                revertable = true,
                reader = { ConfigValue.Str(ftp.username.value) },
                writer = { v ->
                    val name = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    persist(username = name)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "ftp.password",
                displayName = "FTP 密码",
                description = "登录密码（匿名关闭时生效），加密存储。改动会重启正在运行的服务。",
                valueSchema = ConfigSchema.Str(maxLength = 256),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(ftp.password.value) },
                writer = { v ->
                    val pwd = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    persist(password = pwd)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "ftp.anonymous",
                displayName = "允许匿名登录",
                description = "开启后无需账号密码即可读写共享目录。改动会重启正在运行的服务。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(ftp.isAnonymous.value) },
                writer = { v ->
                    val anonymous = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    persist(anonymous = anonymous)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "ftp.auto_start",
                displayName = "开机自动启动",
                description = "App 启动时是否自动拉起 FTP 服务端。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(ftp.autoStart.value) },
                writer = { v ->
                    val autoStart = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    persist(autoStart = autoStart)
                },
            ),
        )
    }

    /** 未显式给出的参数取当前实时值，只改动调用方指定的那一项。 */
    private fun persist(
        port: Int = ftp.port.value,
        username: String = ftp.username.value,
        password: String = ftp.password.value,
        anonymous: Boolean = ftp.isAnonymous.value,
        autoStart: Boolean = ftp.autoStart.value,
    ) = runBlocking { ftp.saveConfig(port, username, password, anonymous, autoStart) }
}
