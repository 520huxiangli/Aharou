package com.aharou.feature.workspace.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.workspace.domain.model.RemoteConnection
import com.aharou.feature.workspace.domain.model.RemoteMount
import com.aharou.feature.workspace.domain.model.RemoteProtocol
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.workspace.domain.repository.RemoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程 SSH 连接与挂载目录 → 配置通道（集合式）。
 *
 * 两个集合：[ConnectionCollection]（`remote.connections.<id>.*`）与
 * [MountCollection]（`remote.mounts.<id>.*`）。写连接时要把 authType/authData
 * 还原成 [RemoteAuth] 才能落库（仓库接口收 Auth 对象而非散字段），还原逻辑与
 * RemoteRepository 内部 entity→domain 的约定保持一致。
 */
@Singleton
class ConfigRemoteFields @Inject constructor(
    private val remote: RemoteRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(ConnectionCollection())
        registry.register(MountCollection())
    }

    private fun connections(): List<RemoteConnection> =
        runBlocking { remote.getConnections().first() }

    private fun mounts(): List<RemoteMount> = runBlocking { remote.getMounts().first() }

    private fun connection(id: String) = connections().firstOrNull { it.id == id }

    private fun mount(id: String) = mounts().firstOrNull { it.id == id }

    private fun authOf(conn: RemoteConnection): RemoteAuth =
        if (conn.authType == "key") {
            RemoteAuth.PrivateKey(conn.authData, conn.passphrase)
        } else {
            RemoteAuth.Password(conn.authData.ifEmpty { conn.password })
        }

    /** 连接里的一项，写回时整体走 updateConnection。 */
    private fun connectionField(
        id: String,
        segment: String,
        label: String,
        desc: String,
        schema: ConfigSchema = ConfigSchema.Str(),
        risk: ConfigRisk = ConfigRisk.NORMAL,
        read: (RemoteConnection) -> String,
        apply: (RemoteConnection, String) -> RemoteConnection,
    ): ConfigField = ClosureField(
        path = "remote.connections.$id.$segment",
        displayName = "$label（$id）",
        description = desc,
        valueSchema = schema,
        risk = risk,
        revertable = true,
        reader = { ConfigValue.Str(connection(id)?.let(read).orEmpty()) },
        writer = { v ->
            val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            val current = connection(id) ?: throw ConfigError.InvalidValue("连接不存在：$id")
            val updated = apply(current, s)
            runBlocking { remote.updateConnection(updated, authOf(updated)) }
        },
    )

    private inner class ConnectionCollection : ConfigCollection {
        override val basePath = "remote.connections"
        override val displayName = "远程连接"
        override val description =
            "SSH/SFTP 连接配置。子字段：name / host / port / username / password / auth_type / auth_data。"

        override fun childIds(): List<String> = connections().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            if (connection(forId) == null) return emptyList()
            return listOf(
                connectionField(
                    forId, "name", "连接名", "显示用名称。",
                    read = { it.name },
                    apply = { c, s -> c.copy(name = s) },
                ),
                connectionField(
                    forId, "host", "主机", "服务器地址或域名。",
                    read = { it.host },
                    apply = { c, s -> c.copy(host = s) },
                ),
                connectionField(
                    forId, "port", "端口", "SSH 默认 22。",
                    schema = ConfigSchema.Int(min = 1, max = 65535),
                    read = { it.port.toString() },
                    apply = { c, s -> c.copy(port = s.toIntOrNull() ?: c.port) },
                ),
                connectionField(
                    forId, "username", "用户名", "登录账户。",
                    read = { it.username },
                    apply = { c, s -> c.copy(username = s) },
                ),
                connectionField(
                    forId, "password", "密码", "auth_type=password 时使用。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.password },
                    apply = { c, s -> c.copy(password = s, authData = s) },
                ),
                connectionField(
                    forId, "auth_type", "认证方式", "password 或 key。",
                    schema = ConfigSchema.StrEnum(listOf("password", "key")),
                    read = { it.authType },
                    apply = { c, s -> c.copy(authType = s) },
                ),
                connectionField(
                    forId, "auth_data", "认证数据",
                    "password 时为密码，key 时为私钥文件路径。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.authData },
                    apply = { c, s -> c.copy(authData = s) },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("remote.connections 的新项需为 JSON 对象")
            val host = (obj["host"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 host")
            val authType = (obj["authType"] as? ConfigValue.Str)?.value ?: "password"
            val authData = (obj["authData"] as? ConfigValue.Str)?.value.orEmpty()
            val conn = RemoteConnection(
                id = UUID.randomUUID().toString(),
                name = (obj["name"] as? ConfigValue.Str)?.value ?: host,
                protocol = RemoteProtocol.entries.firstOrNull {
                    it.name.equals((obj["protocol"] as? ConfigValue.Str)?.value, ignoreCase = true)
                } ?: RemoteProtocol.SFTP,
                host = host,
                port = (obj["port"] as? ConfigValue.Str)?.value?.toIntOrNull() ?: 22,
                username = (obj["username"] as? ConfigValue.Str)?.value.orEmpty(),
                password = if (authType == "password") authData else "",
                authType = authType,
                authData = authData,
            )
            runBlocking { remote.addConnection(conn, authOf(conn)) }
            return conn.id
        }

        override fun remove(id: String) {
            if (connection(id) == null) throw ConfigError.InvalidValue("连接不存在：$id")
            runBlocking { remote.deleteConnection(id) }
        }
    }

    /** 挂载里的一项；[apply] 为 null 表示只读。 */
    private fun mountField(
        id: String,
        segment: String,
        label: String,
        desc: String,
        read: (RemoteMount) -> String,
        apply: ((RemoteMount, String) -> RemoteMount)? = null,
    ): ConfigField = ClosureField(
        path = "remote.mounts.$id.$segment",
        displayName = "$label（$id）",
        description = desc,
        valueSchema = ConfigSchema.Str(),
        access = if (apply == null) ConfigAccess.READONLY else ConfigAccess.READWRITE,
        revertable = apply != null,
        reader = { ConfigValue.Str(mount(id)?.let(read).orEmpty()) },
        writer = { v ->
            val fn = apply ?: throw ConfigError.PermissionDenied("$segment 为只读字段")
            val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            val current = mount(id) ?: throw ConfigError.InvalidValue("挂载不存在：$id")
            runBlocking { remote.updateMount(fn(current, s)) }
        },
    )

    private inner class MountCollection : ConfigCollection {
        override val basePath = "remote.mounts"
        override val displayName = "挂载目录"
        override val description =
            "本地与远程目录的同步挂载。子字段：connection_id / remote_path / local_path / auto_connect；is_active 为运行时状态，只读。"

        override fun childIds(): List<String> = mounts().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            if (mount(forId) == null) return emptyList()
            return listOf(
                mountField(
                    forId, "connection_id", "所属连接", "对应 remote.connections 里的 id。",
                    read = { it.connectionId },
                    apply = { m, s -> m.copy(connectionId = s) },
                ),
                mountField(
                    forId, "remote_path", "远程路径", "服务器上的目录。",
                    read = { it.remotePath },
                    apply = { m, s -> m.copy(remotePath = s) },
                ),
                mountField(
                    forId, "local_path", "本地挂载点", "工作区内的落地目录。",
                    read = { it.localMountPath },
                    apply = { m, s -> m.copy(localMountPath = s) },
                ),
                ClosureField(
                    path = "remote.mounts.$forId.auto_connect",
                    displayName = "自动连接（$forId）",
                    description = "打开工作区时自动建立同步。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(mount(forId)?.autoConnect ?: false) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        val current = mount(forId)
                            ?: throw ConfigError.InvalidValue("挂载不存在：$forId")
                        runBlocking { remote.updateMount(current.copy(autoConnect = b)) }
                    },
                ),
                mountField(
                    forId, "is_active", "当前状态", "是否已连接（运行时状态，只读）。",
                    read = { it.isActive.toString() },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("remote.mounts 的新项需为 JSON 对象")
            val connectionId = (obj["connectionId"] as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 connectionId")
            if (connection(connectionId) == null) {
                throw ConfigError.InvalidValue("连接不存在：$connectionId")
            }
            val remotePath = (obj["remotePath"] as? ConfigValue.Str)?.value.orEmpty()
            val mount = RemoteMount(
                id = UUID.randomUUID().toString(),
                connectionId = connectionId,
                remotePath = remotePath,
                localMountPath = (obj["localMountPath"] as? ConfigValue.Str)?.value ?: remotePath,
                autoConnect = (obj["autoConnect"] as? ConfigValue.Bool)?.value ?: true,
            )
            runBlocking { remote.addMount(mount) }
            return mount.id
        }

        override fun remove(id: String) {
            if (mount(id) == null) throw ConfigError.InvalidValue("挂载不存在：$id")
            runBlocking { remote.deleteMount(id) }
        }
    }
}
