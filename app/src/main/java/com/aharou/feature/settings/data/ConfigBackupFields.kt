package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份导出范围 → 配置通道。
 *
 * 存储是 `SharedPreferences("backup_options")` 里的 8 个布尔开关：备份页勾选即持久化，
 * 下次进入页面沿用（见 BackupViewModel 的 updateExportOptions / loadExportOptions）。
 * 该存储的写入方只有 [com.aharou.feature.backup.presentation.BackupViewModel]，没有独立
 * repository；这里直接复用同一个 prefs 文件与同一组键名（常量值逐个对齐），保证 UI 勾选与
 * 配置通道读到、写到的是同一份数据，不存在第二处存储。
 *
 * 注意：键名与默认值须与 BackupViewModel 的私有常量保持同步；若日后把该 prefs 抽成
 * repository，本文件应改为走 repository 方法而不是直接操作 prefs。
 */
@Singleton
class ConfigBackupFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val PREFS_NAME = "backup_options"
        const val KEY_PROVIDERS = "providers"
        const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        const val KEY_CHAT_HISTORY = "chat_history"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_PERMISSION_RULES = "permission_rules"
        const val KEY_APP_SETTINGS = "app_settings"
        const val KEY_WORKSPACE_FILES = "workspace_files"
        const val KEY_MEMORY_AND_SOUL = "memory_and_soul"
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerInto(registry: ConfigRegistry) {
        registry.register(toggle("backup.providers", "导出 AI 供应商",
            "备份时是否包含 AI 供应商配置（含 API Key）。", KEY_PROVIDERS, true))
        registry.register(toggle("backup.remote_connections", "导出远程连接",
            "备份时是否包含 SSH/SFTP 远程连接与挂载。", KEY_REMOTE_CONNECTIONS, true))
        registry.register(toggle("backup.chat_history", "导出聊天记录",
            "备份时是否包含会话与消息历史。", KEY_CHAT_HISTORY, true))
        registry.register(toggle("backup.mcp_servers", "导出 MCP 服务器",
            "备份时是否包含 MCP server 配置。", KEY_MCP_SERVERS, true))
        registry.register(toggle("backup.permission_rules", "导出工具授权规则",
            "备份时是否包含工具授权 / 审批规则。", KEY_PERMISSION_RULES, true))
        registry.register(toggle("backup.app_settings", "导出应用设置",
            "备份时是否包含本机应用设置段。", KEY_APP_SETTINGS, true))
        registry.register(toggle("backup.workspace_files", "导出工作区文件",
            "备份时是否打包工作区文件（体积大，默认关闭）。", KEY_WORKSPACE_FILES, false))
        registry.register(toggle("backup.memory_and_soul", "导出记忆与人格",
            "备份时是否包含记忆与人格文件（SOUL.md / 核心档案 / 全局记忆等）。", KEY_MEMORY_AND_SOUL, true))
    }

    /** 一个导出范围开关：读默认值与 BackupViewModel.loadExportOptions 一致。 */
    private fun toggle(
        path: String,
        displayName: String,
        description: String,
        key: String,
        default: Boolean,
    ): ClosureField = ClosureField(
        path = path,
        displayName = displayName,
        description = description,
        valueSchema = ConfigSchema.Bool,
        revertable = true,
        reader = { ConfigValue.Bool(prefs.getBoolean(key, default)) },
        writer = { v ->
            val enabled = (v as? ConfigValue.Bool)?.value
                ?: throw ConfigError.TypeMismatch("boolean")
            prefs.edit().putBoolean(key, enabled).apply()
        },
    )
}
