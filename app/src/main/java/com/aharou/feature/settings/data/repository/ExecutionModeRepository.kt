package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.executionModeDataStore by preferencesDataStore(
    name = "execution_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 执行环境模式。 */
enum class ExecutionMode {
    /** 本地 PRoot 容器（原有行为）。 */
    LOCAL_PROOT,
    /** 远程 SSH 服务器。 */
    REMOTE_SSH
}

/** 远程 SSH 连接配置的持久化形式。 */
data class RemoteConnectionSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val remoteWorkspacePath: String
)

/**
 * 持久化当前执行模式（本地 PRoot / 远程 SSH）与远程连接配置。
 *
 * 本地模式下远程配置被忽略；远程模式下 [remoteConnectionFlow] 提供连接参数。
 * 切换模式时由 DI 层据此注入对应的 [com.aharou.feature.agent.domain.container.CommandEngine]
 * 与 [com.aharou.feature.workspace.domain.FileAccessProvider] 实现。
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MODE_KEY = stringPreferencesKey("execution_mode")
        val HOST_KEY = stringPreferencesKey("remote_host")
        val PORT_KEY = stringPreferencesKey("remote_port")
        val USERNAME_KEY = stringPreferencesKey("remote_username")
        val PASSWORD_KEY = stringPreferencesKey("remote_password")
        val REMOTE_PATH_KEY = stringPreferencesKey("remote_workspace_path")
    }

    /** 当前执行模式；无值时默认本地 PRoot。 */
    val executionModeFlow: Flow<ExecutionMode> = context.executionModeDataStore.data.map { prefs ->
        prefs[MODE_KEY]?.let {
            runCatching { ExecutionMode.valueOf(it) }.getOrNull()
        } ?: ExecutionMode.LOCAL_PROOT
    }

    /** 远程 SSH 连接配置。 */
    val remoteConnectionFlow: Flow<RemoteConnectionSettings?> = context.executionModeDataStore.data.map { prefs ->
        val host = prefs[HOST_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        val username = prefs[USERNAME_KEY] ?: ""
        RemoteConnectionSettings(
            host = host,
            port = prefs[PORT_KEY]?.toIntOrNull() ?: 22,
            username = username,
            password = prefs[PASSWORD_KEY] ?: "",
            remoteWorkspacePath = normalizeRemoteWorkspacePath(prefs[REMOTE_PATH_KEY], username)
        )
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        context.executionModeDataStore.edit { it[MODE_KEY] = mode.name }
    }

    suspend fun setRemoteConnection(settings: RemoteConnectionSettings) {
        context.executionModeDataStore.edit {
            it[HOST_KEY] = settings.host
            it[PORT_KEY] = settings.port.toString()
            it[USERNAME_KEY] = settings.username
            it[PASSWORD_KEY] = settings.password
            it[REMOTE_PATH_KEY] = settings.remoteWorkspacePath
        }
    }
}

/**
 * 归一化远程工作区根路径。旧版把默认值硬编码为 `/home/<用户名>/workspace`（对 home 不在
 * `/home/<用户名>` 的用户，如 root 的 `/root`，是错的）；`~/workspace` 则是符号链接占用的路径，
 * 不能当工作区根（会与链接相撞形成自引用）。这两类残留值与空值一律改回默认值。
 */
internal fun normalizeRemoteWorkspacePath(stored: String?, username: String): String {
    val v = stored?.trim().orEmpty()
    return if (v.isEmpty() || v == "/home/$username/workspace" || v == "~/workspace") {
        DEFAULT_REMOTE_WORKSPACE_ROOT
    } else {
        v
    }
}

/**
 * 远程工作区根目录的默认值。**不能用 `~/workspace`**——那是「当前工作区」符号链接占用的路径，
 * 同名会让根目录与链接相撞。
 */
internal const val DEFAULT_REMOTE_WORKSPACE_ROOT = "~/.aicode/workspaces"
