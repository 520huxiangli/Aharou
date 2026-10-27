package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreEnumField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerWorkspaceKeys
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.presentation.ContainerDnsMode
import com.aharou.feature.settings.presentation.ContainerMirrorMode
import com.aharou.feature.settings.presentation.ContainerSourcesRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 容器设置 → 配置通道。
 *
 * 标量接「选哪个容器」；结构化部分 [ProfileCollection] 把自定义 / 远程 SSH profile 列表
 * （`container.profiles.<id>.*`）做成集合，增删走 [ContainerSettingsRepository] 既有方法。
 */
@Singleton
class ConfigContainerFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerSettings: ContainerSettingsRepository,
    private val containerSources: ContainerSourcesRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.registerSources()
        registry.register(
            DataStoreStrField(
                path = "container.active_profile",
                displayName = "当前容器",
                description = "终端 / 命令执行使用的容器 profile id，同时是「未单独指定的工作区」的默认值。" +
                    "默认 ${ContainerProfile.BUILTIN_ID}（内置 Alpine）；某个工作区要用别的容器，" +
                    "在 container.workspace_profiles 里加一条即可。" +
                    "可用 id 见 `config get container.profiles` 或设置页「容器与镜像」。",
                flow = containerSettings.activeProfileIdFlow,
                setter = { containerSettings.setActiveProfile(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "container.default_container",
                displayName = "远程模式默认容器",
                description = "远程工作区模式下，本地 MCP stdio 等服务跑在哪个容器。默认 ${ContainerProfile.BUILTIN_ID}（内置 Alpine）。",
                flow = containerSettings.defaultContainerIdFlow,
                setter = { containerSettings.setDefaultContainerId(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "container.gradle_heap_mb",
                displayName = "容器 Gradle 堆上限（MB）",
                description = "容器内编译 Gradle 工程时的 JVM 堆上限，写进容器的 ~/.gradle/gradle.properties" +
                    "（用户级优先于项目里写死的 org.gradle.jvmargs），改完下一次编译生效。" +
                    "范围 ${ContainerSettingsRepository.GRADLE_HEAP_MIN_MB}~${ContainerSettingsRepository.GRADLE_HEAP_MAX_MB}；" +
                    "默认按设备总内存自动推荐一档。" +
                    "堆上限超过实际可用内存会被系统 OOM 杀掉（编译无声中断），内存紧张时调低而不是调高。",
                flow = containerSettings.gradleHeapMbFlow,
                setter = { containerSettings.setGradleHeapMb(it) },
                minValue = ContainerSettingsRepository.GRADLE_HEAP_MIN_MB,
                maxValue = ContainerSettingsRepository.GRADLE_HEAP_MAX_MB,
            ),
        )

        registry.register(ProfileCollection())
        registry.register(WorkspaceProfileCollection())
    }

    private fun profiles(): List<ContainerProfile> =
        runBlocking { containerSettings.customProfilesFlow.first() }

    private fun profile(id: String): ContainerProfile? = profiles().firstOrNull { it.id == id }

    private fun require(id: String): ContainerProfile =
        profile(id) ?: throw ConfigError.InvalidValue("容器 profile 不存在：$id")

    private fun mutate(id: String, transform: (ContainerProfile) -> ContainerProfile) {
        runBlocking { containerSettings.upsertCustomProfile(transform(require(id))) }
    }

    private fun strOf(v: ConfigValue): String =
        (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")

    /** 把一串字符串数组读成 [ConfigValue.Arr]，逐项要求是字符串。 */
    private fun stringListOf(v: ConfigValue): List<String> {
        val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
        return arr.map { (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string") }
    }

    /** 容器 profile 列表（含内置 Alpine）。子字段按 [ContainerProfile] 真实结构给出。 */
    private inner class ProfileCollection : ConfigCollection {
        override val basePath = "container.profiles"
        override val displayName = "容器 profile"
        override val description =
            "自定义容器 / 远程 SSH 容器列表（含内置 Alpine）。子字段：name / mode / rootfs_kind / " +
                "rootfs_source / shell_path / extra_bindings / extra_args / env / is_builtin / created_at；" +
                "add 建一个新 profile、remove 删掉列表中的一项（分别走 upsertCustomProfile / deleteCustomProfile）。"

        override fun childIds(): List<String> = profiles().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            val p = profile(forId) ?: return emptyList()
            return listOf(
                ReadOnlyField(
                    "$basePath.$forId.id", "id（$forId）", "profile 唯一标识，即集合子项 id。",
                    ConfigSchema.Str(),
                ) { ConfigValue.Str(p.id) },

                ClosureField(
                    path = "$basePath.$forId.name",
                    displayName = "名称（$forId）",
                    description = "容器在列表 / 设置页里的显示名。",
                    valueSchema = ConfigSchema.Str(maxLength = 120),
                    revertable = true,
                    reader = { ConfigValue.Str(p.name) },
                    writer = { v -> mutate(forId) { it.copy(name = strOf(v)) } },
                ),

                ReadOnlyField(
                    "$basePath.$forId.mode", "执行模式（$forId）",
                    "local_proot=本地 PRoot 容器 / remote_ssh=远程 SSH；由 profile 创建时选定，与 rootfs 来源绑定，只读。",
                    ConfigSchema.StrEnum(listOf("local_proot", "remote_ssh")),
                ) { ConfigValue.Str(p.mode.name.lowercase()) },

                ReadOnlyField(
                    "$basePath.$forId.rootfs_kind", "镜像来源类型（$forId）",
                    "asset=内置 assets / local_file=导入的 tar.gz / remote_ssh=远程 SSH 通道；只读。",
                    ConfigSchema.StrEnum(listOf("asset", "local_file", "remote_ssh")),
                ) {
                    ConfigValue.Str(
                        when (p.rootfsSource) {
                            is RootfsSource.Asset -> "asset"
                            is RootfsSource.LocalFile -> "local_file"
                            is RootfsSource.RemoteSsh -> "remote_ssh"
                        },
                    )
                },

                ReadOnlyField(
                    "$basePath.$forId.rootfs_source", "镜像来源（$forId）",
                    "asset=assets 内路径 / local_file=content uri / remote_ssh=「连接 id:远程工作区路径」；只读。",
                    ConfigSchema.Str(),
                ) {
                    ConfigValue.Str(
                        when (val s = p.rootfsSource) {
                            is RootfsSource.Asset -> s.path
                            is RootfsSource.LocalFile -> s.uri
                            is RootfsSource.RemoteSsh -> "${s.connectionId}:${s.remoteWorkspacePath}"
                        },
                    )
                },

                ClosureField(
                    path = "$basePath.$forId.shell_path",
                    displayName = "Shell 路径（$forId）",
                    description = "自定义镜像用的 shell（如 /bin/sh、/bin/bash）；留空 = 按 provision 状态自动选择。",
                    valueSchema = ConfigSchema.Str(maxLength = 256),
                    revertable = true,
                    reader = { ConfigValue.Str(p.shellPath.orEmpty()) },
                    writer = { v -> mutate(forId) { it.copy(shellPath = strOf(v).ifBlank { null }) } },
                ),

                ClosureField(
                    path = "$basePath.$forId.extra_bindings",
                    displayName = "额外挂载（$forId）",
                    description = "额外的 -b 绑定列表，每项形如「宿主路径:容器路径」（都须为绝对路径）。",
                    valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                    revertable = true,
                    reader = { ConfigValue.Arr(p.extraBindings.map { ConfigValue.Str(it) }) },
                    writer = { v ->
                        val bindings = stringListOf(v)
                        bindings.forEach { b ->
                            ContainerProfile.validateBinding(b)?.let { reason ->
                                throw ConfigError.InvalidValue("挂载不合法（$b）：$reason")
                            }
                        }
                        mutate(forId) { it.copy(extraBindings = bindings) }
                    },
                ),

                ClosureField(
                    path = "$basePath.$forId.extra_args",
                    displayName = "额外 proot 参数（$forId）",
                    description = "追加到 proot 命令行的参数列表，如 [\"--link2symlink\", \"--kill-on-exit\"]。" +
                        "删掉默认项可能导致容器启动异常。",
                    valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                    revertable = true,
                    reader = { ConfigValue.Arr(p.extraArgs.map { ConfigValue.Str(it) }) },
                    writer = { v -> mutate(forId) { it.copy(extraArgs = stringListOf(v)) } },
                ),

                ClosureField(
                    path = "$basePath.$forId.env",
                    displayName = "环境变量（$forId）",
                    description = "注入容器内进程的键值对（JSON 对象，值须为字符串），同名默认值被覆盖。",
                    valueSchema = ConfigSchema.Json,
                    risk = ConfigRisk.SENSITIVE,
                    revertable = true,
                    reader = { ConfigValue.Obj(p.env.mapValues { ConfigValue.Str(it.value) }) },
                    writer = { v ->
                        val obj = (v as? ConfigValue.Obj)?.value
                            ?: throw ConfigError.TypeMismatch("object")
                        val env = obj.mapValues { (k, value) ->
                            (value as? ConfigValue.Str)?.value
                                ?: throw ConfigError.TypeMismatch("string（$k 的值）")
                        }
                        mutate(forId) { it.copy(env = env) }
                    },
                ),

                ReadOnlyField(
                    "$basePath.$forId.is_builtin", "是否内置（$forId）",
                    "内置 Alpine 为 true，其余为 false；只读。",
                    ConfigSchema.Bool,
                ) { ConfigValue.Bool(p.isBuiltin) },

                ReadOnlyField(
                    "$basePath.$forId.created_at", "添加时间（$forId）",
                    "epoch 毫秒；旧数据 / 内置默认可能为 0（列表排最后）；只读。",
                    ConfigSchema.Str(),
                ) { ConfigValue.Str(p.createdAt.toString()) },
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("container.profiles 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            if (name.isBlank()) throw ConfigError.InvalidValue("缺少 name")

            val connectionId = (obj["connection_id"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
            val remotePath = (obj["remote_workspace_path"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            val rootfsUri = (obj["rootfs_uri"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }

            val source: RootfsSource = when {
                connectionId != null -> {
                    if (remotePath.isBlank()) {
                        throw ConfigError.InvalidValue("远程 SSH profile 需要 remote_workspace_path")
                    }
                    RootfsSource.RemoteSsh(connectionId, remotePath)
                }
                rootfsUri != null -> RootfsSource.LocalFile(rootfsUri)
                else -> throw ConfigError.InvalidValue("缺少镜像来源：需提供 rootfs_uri（本地 tar.gz）或 connection_id（远程 SSH）")
            }
            val mode = if (source is RootfsSource.RemoteSsh) {
                ExecutionMode.REMOTE_SSH
            } else {
                ExecutionMode.LOCAL_PROOT
            }

            val bindings = (obj["extra_bindings"] as? ConfigValue.Arr)?.value
                ?.map { (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string") }
                .orEmpty()
            bindings.forEach { b ->
                ContainerProfile.validateBinding(b)?.let { reason ->
                    throw ConfigError.InvalidValue("挂载不合法（$b）：$reason")
                }
            }

            val args = (obj["extra_args"] as? ConfigValue.Arr)?.value
                ?.map { (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string") }
                ?.takeIf { it.isNotEmpty() }
                ?: (if (source is RootfsSource.LocalFile) ContainerProfile.DEFAULT_PROOT_ARGS else emptyList())

            val env = (obj["env"] as? ConfigValue.Obj)?.value
                ?.mapValues { (k, value) ->
                    (value as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string（$k 的值）")
                }
                .orEmpty()

            val profile = ContainerProfile(
                id = "custom-" + UUID.randomUUID().toString(),
                name = name,
                rootfsSource = source,
                shellPath = (obj["shell_path"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() },
                extraBindings = bindings,
                extraArgs = args,
                env = env,
                isBuiltin = false,
                mode = mode,
            )
            runBlocking { containerSettings.upsertCustomProfile(profile) }
            return profile.id
        }

        override fun remove(id: String) {
            require(id)
            runBlocking { containerSettings.deleteCustomProfile(id) }
        }
    }

    /**
     * 工作区 → 容器 profile 覆盖表（`container.workspace_profiles.<归一化工作区路径>`）。
     *
     * 只对「表里有的工作区」生效；没配的工作区继续走 [ConfigContainerFields] 的 container.active_profile，
     * 所以默认行为与加这个集合之前完全一致。执行引擎每跑一条命令按传入的 projectPath 现查一次。
     */
    private inner class WorkspaceProfileCollection : ConfigCollection {
        override val basePath = "container.workspace_profiles"
        override val displayName = "工作区容器覆盖"
        override val description =
            "按工作区指定容器。子字段：workspace（归一化后的绝对路径，即子项 id）/ profile（容器 profile id）；" +
                "add 传 {\"workspace\":\"<工作区绝对路径>\",\"profile\":\"<profile id>\"}，remove 传该路径。" +
                "表里没有的工作区仍用 container.active_profile（全局默认）。"

        override fun childIds(): List<String> = overrides().keys.toList()

        override fun fields(forId: String): List<ConfigField> = listOf(
            ReadOnlyField(
                path = "$basePath.$forId.workspace",
                displayName = "工作区（$forId）",
                description = "归一化后的工作区绝对路径（去尾斜杠、解掉符号链接）；即集合子项 id。",
                valueSchema = ConfigSchema.Str(),
            ) { ConfigValue.Str(forId) },

            ClosureField(
                path = "$basePath.$forId.profile",
                displayName = "容器 profile（$forId）",
                description = "该工作区执行命令 / 开终端时使用的容器；须为 container.profiles 里的 id。",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                revertable = true,
                reader = { ConfigValue.Str(overrides()[forId].orEmpty()) },
                writer = { v ->
                    val target = strOf(v).trim()
                    require(target)
                    runBlocking { containerSettings.putWorkspaceProfile(forId, target) }
                },
            ),
        )

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("container.workspace_profiles 的新项需为 JSON 对象")
            val workspace = (obj["workspace"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            if (workspace.isBlank()) throw ConfigError.InvalidValue("缺少 workspace（工作区绝对路径）")
            if (!workspace.startsWith("/")) throw ConfigError.InvalidValue("workspace 须为绝对路径：$workspace")
            val profileId = (obj["profile"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            if (profileId.isBlank()) throw ConfigError.InvalidValue("缺少 profile（容器 profile id）")
            // 不存在的 id 先拦住：写进去只会让引擎回退到全局默认，用户却以为生效了。
            require(profileId)
            val key = ContainerWorkspaceKeys.normalize(workspace)
                ?: throw ConfigError.InvalidValue("工作区路径不合法：$workspace")
            runBlocking { containerSettings.putWorkspaceProfile(key, profileId) }
            return key
        }

        override fun remove(id: String) {
            val key = ContainerWorkspaceKeys.normalize(id)
                ?: throw ConfigError.InvalidValue("工作区路径不合法：$id")
            val removed = runBlocking { containerSettings.removeWorkspaceProfile(key) }
            if (!removed) throw ConfigError.InvalidValue("该工作区没有容器覆盖：$id")
        }
    }

    private fun overrides(): Map<String, String> =
        runBlocking { containerSettings.workspaceProfilesFlow.first() }

    /**
     * 容器软件源与 DNS。
     *
     * 这两个字段只是「记录值」：真正换源/改 DNS 要写进容器（跑 setup_mirror、重建时重放），
     * 是设置页里带进度的动作，不适合从配置通道直接触发，所以说明里写清楚。
     */
    private fun ConfigRegistry.registerSources() {
        register(
            DataStoreEnumField(
                path = "container.sources.mirror",
                displayName = "容器软件源",
                description = "OFFICIAL 用发行版自带源，CHINA 自动探测国内镜像。改这里只改记录值；" +
                    "真正生效要在「设置 → 镜像与 DNS」里点切换（会写进容器，重建后自动重放）。",
                flow = containerSources.settingsFlow.map { it.mirror.name },
                setter = { value ->
                    ContainerMirrorMode.entries.firstOrNull { it.name == value }?.let { mode ->
                        containerSources.save(containerSources.read().copy(mirror = mode))
                    }
                },
                cases = ContainerMirrorMode.entries.map { it.name },
            ),
        )
        register(
            DataStoreEnumField(
                path = "container.sources.dns",
                displayName = "容器 DNS",
                description = "SYSTEM 跟随手机当前网络，另两档用固定公共 DNS。同样只是记录值，生效要回设置页点切换。",
                flow = containerSources.settingsFlow.map { it.dns.name },
                setter = { value ->
                    ContainerDnsMode.entries.firstOrNull { it.name == value }?.let { mode ->
                        containerSources.save(containerSources.read().copy(dns = mode))
                    }
                },
                cases = ContainerDnsMode.entries.map { it.name },
            ),
        )
        register(
            DataStoreStrField(
                path = "container.sources.mirror_host",
                displayName = "容器镜像站",
                description = "自动探测到的镜像站域名；留空表示没探测过或已回退官方源。",
                flow = containerSources.settingsFlow.map { it.mirrorHost },
                setter = { value ->
                    containerSources.save(containerSources.read().copy(mirrorHost = value))
                },
            ),
        )
    }
}
