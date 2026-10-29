package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.memory.MemoryAutoCompactSettings
import com.aharou.feature.agent.domain.memory.MemorySyncScheduler
import com.aharou.feature.agent.domain.memory.MemorySyncSettings
import com.aharou.feature.credentials.data.repository.FileCredentialRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「记忆同步」→ 配置通道。
 *
 * 这些配置原先只存在 App 私有的 SharedPreferences 里，只有设置页一个入口——AI 够不着
 * （正式版既不能 run-as 也没 root），于是「帮我把仓库地址填上」这种小事也只得让用户手点。
 * 登记进来之后 AI 可以直接读写，改动同样进配置审计。
 */
@Singleton
class ConfigMemorySyncFields @Inject constructor(
    private val settings: MemorySyncSettings,
    private val scheduler: MemorySyncScheduler,
    private val credentials: FileCredentialRepository,
    private val autoCompactSettings: MemoryAutoCompactSettings,
) {

    fun registerInto(registry: ConfigRegistry) {
        // 同属记忆后台任务的配置，放这里而不是 ConfigBuiltins：那边拿不到 Hilt 注入的单例。
        registry.register(
            ClosureField(
                path = "memory.auto_organize",
                displayName = "自动整理记忆",
                description = "开启后后台每天最多跑一次深度整理：让模型按主题归拢讲同一件事的条目、把没写清「何时该读」的摘要重写。" +
                    "每次都要花模型调用，默认关闭；关闭时不影响每日的归档、压缩与字面去重。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(autoCompactSettings.autoOrganize) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    autoCompactSettings.autoOrganize = flag
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.sync.repo_url",
                displayName = "记忆仓库地址",
                description = "记忆同步到哪个 git 仓库：仓库是收集桶，全局记忆进 `global/`、各工作区的项目记忆进 `projects/<项目名-短哈希>/`（含远程工作区，前缀 `remote-`）。留空表示没配、同步功能不可用。" +
                    "远端仓库需自行创建，凭据走容器里已有的 git 凭据。",
                valueSchema = ConfigSchema.Str(maxLength = 512),
                revertable = true,
                reader = { ConfigValue.Str(settings.repoUrl) },
                writer = { v ->
                    val url = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    settings.repoUrl = url
                    // 排不排周期任务取决于「开关 + 地址」两者，地址改了要重排。
                    scheduler.apply(force = true)
                },
            ),
        )

        registry.register(repoTokenField())

        registry.register(
            ClosureField(
                path = "memory.sync.branch",
                displayName = "记忆仓库分支",
                description = "推送分支，默认 main。",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                revertable = true,
                reader = { ConfigValue.Str(settings.branch) },
                writer = { v ->
                    val branch = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    settings.branch = branch
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.sync.auto",
                displayName = "自动同步",
                description = "开启后由后台周期任务推送记忆（需先填仓库地址）。关闭后不再后台推送，重新打开开关时会立刻推一次。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(settings.autoSync) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    settings.autoSync = flag
                    scheduler.apply(force = true)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.sync.interval_minutes",
                displayName = "自动同步间隔（分钟）",
                description = "后台同步的间隔，下限 ${MemorySyncScheduler.MIN_INTERVAL_MINUTES}（WorkManager 周期任务的下限）。默认 60。",
                valueSchema = ConfigSchema.Int(min = MemorySyncScheduler.MIN_INTERVAL_MINUTES, max = 24 * 60),
                revertable = true,
                reader = { ConfigValue.Int(settings.autoSyncIntervalMinutes) },
                writer = { v ->
                    val minutes = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("integer")
                    settings.autoSyncIntervalMinutes = minutes
                    scheduler.apply(force = true)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.sync.last_result",
                displayName = "上次同步结果",
                description = "最近一次同步的一句话结果（成功或失败原因）。只读。",
                valueSchema = ConfigSchema.Str(maxLength = 400),
                access = ConfigAccess.READONLY,
                revertable = false,
                reader = { ConfigValue.Str(settings.lastResult) },
                writer = { throw ConfigError.PermissionDenied("同步结果为只读字段") },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.sync.last_sync_at",
                displayName = "上次同步时间",
                description = "最近一次同步完成的时间。只读。",
                valueSchema = ConfigSchema.Str(maxLength = 32),
                access = ConfigAccess.READONLY,
                revertable = false,
                reader = { ConfigValue.Str(formatTime(settings.lastSyncAt)) },
                writer = { throw ConfigError.PermissionDenied("同步时间为只读字段") },
            ),
        )
    }

    private fun formatTime(epochMs: Long): String =
        if (epochMs <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    /**
     * 记忆仓库的访问令牌。
     *
     * 容器里的 git **只读** `~/.aharou/git-credentials`（credential helper 直读该文件），**不读凭据库**，
     * 所以这里直接写 git 凭据；主机从仓库地址解析，用户名按 cnb 平台惯例固定。
     */
    private fun repoTokenField(): ConfigField = ClosureField(
        path = "memory.sync.token",
        displayName = "访问令牌",
        description = "推送记忆仓库用的访问令牌，按仓库地址的主机写入 git 凭据（容器里的 git 只读 " +
            "~/.aharou/git-credentials，不读凭据库）。留空即清除该主机的凭据。",
        valueSchema = ConfigSchema.Str(maxLength = 512),
        risk = ConfigRisk.SENSITIVE,
        revertable = true,
        reader = {
            ConfigValue.Str(
                runBlocking { repoHost()?.let { credentials.findForHost(it) }?.token }.orEmpty(),
            )
        },
        writer = { v ->
            val token = (v as? ConfigValue.Str)?.value
                ?: throw ConfigError.TypeMismatch("string")
            val host = repoHost() ?: throw ConfigError.InvalidValue("请先填写记忆仓库地址")
            runBlocking {
                val existing = credentials.findForHost(host)
                if (token.isBlank()) {
                    existing?.let { credentials.delete(it.id) }
                } else {
                    credentials.save(
                        GitCredential(id = host, host = host, username = GIT_USERNAME, token = token),
                    )
                }
            }
        },
    )

    /** 仓库地址的主机名；没填或解析不出来时返回 null。 */
    private fun repoHost(): String? = runCatching {
        java.net.URI(settings.repoUrl.trim()).host?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        /** cnb 平台的 git 用户名。 */
        const val GIT_USERNAME = "cnb"
    }
}
