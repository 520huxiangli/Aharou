package com.aharou.feature.agent.domain.container

import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.normalizeRemoteWorkspacePath
import com.aharou.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aharou.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前激活容器 profile 对应的远程连接配置解析器。
 *
 * 取代旧的 execution_mode_prefs 连接参数副本：激活 profile 变化、profile 内容变化（编辑远程路径或换绑通道）
 * 或连接表变化（改 host/端口/账号密码）都会重新解析，产出可直接交给 [RemoteSshConnection.connect] 的
 * [RemoteConnectionConfig]，因此改连接即时生效，无需切换容器再切回。
 */
@Singleton
class ActiveRemoteConnectionResolver @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val dao: RemoteConnectionDao
) {
    /** 激活 profile 解析出的连接配置；本地 profile、通道缺失或连接被删时为 null。 */
    val activeConfigFlow: Flow<RemoteConnectionConfig?> = combine(
        containerSettingsRepository.activeProfileIdFlow,
        containerSettingsRepository.customProfilesFlow,
        dao.getAllConnections()
    ) { activeId, profiles, connections ->
        resolve(activeId, profiles, connections)
    }.distinctUntilChanged()

    private fun resolve(
        activeId: String,
        profiles: List<ContainerProfile>,
        connections: List<RemoteConnectionEntity>
    ): RemoteConnectionConfig? {
        val profile = profiles.firstOrNull { it.id == activeId }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeId }
            ?: return null
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return null
        val conn = connections.firstOrNull { it.id == ssh.connectionId } ?: return null
        return RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = conn.toRemoteAuth(),
            remoteWorkspacePath = normalizeRemoteWorkspacePath(ssh.remoteWorkspacePath, conn.username)
        )
    }

    /**
     * authData 的语义随 authType 变：密码连接存密文，密钥连接存明文私钥路径，故只对密码解密。
     * 这里读的是连接表原始值，取值已在迁移 58 归一成 "password"/"key"。
     */
    private fun RemoteConnectionEntity.toRemoteAuth(): RemoteAuth =
        if (authType == "key") {
            RemoteAuth.PrivateKey(authData, passphrase?.let { KeystoreCipher.decryptString(it) })
        } else {
            RemoteAuth.Password(KeystoreCipher.decryptString(authData))
        }
}
