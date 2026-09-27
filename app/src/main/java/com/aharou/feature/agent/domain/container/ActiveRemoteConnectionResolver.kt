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
 *
 * 仅支持密码认证（与远程镜像通道既有约束一致）：非密码类型的连接解出空密码。
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
        val password = if (conn.authType == "PASSWORD") KeystoreCipher.decryptString(conn.authData) else ""
        return RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = RemoteAuth.Password(password),
            remoteWorkspacePath = normalizeRemoteWorkspacePath(ssh.remoteWorkspacePath, conn.username)
        )
    }
}
