package com.aharou.feature.settings.data.repository

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerWorkspaceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.containerDataStore by preferencesDataStore(
    name = "container_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 下载镜像页里已下载/已安装的镜像记录，跨重启保留。 */
@Serializable
data class DownloadedImageRecord(
    val entryId: String,
    val fileUri: String,
    /**
     * 下载当时的版本号（如 `3.21.8` / `12.15`）。目录里的版本会随上游刷新，磁盘上的文件不会跟着变，
     * 靠它才能判断「已下载的是不是旧补丁」。空串表示升级前留下的旧记录，此时不参与「有新版」判定。
     */
    val version: String = "",
    val installed: Boolean = false
)

/**
 * PRoot 的 seccomp 处理方式。
 *
 * 陌生 ROM 上 proot 的 seccomp 加速可能翻译不了客户机 execve（表现为 `execve: Operation not
 * permitted`），此时容器里一条命令都跑不起来，只能退回全量 ptrace（PROOT_NO_SECCOMP=1）。
 * 但该变量在本设备触发过 `ptrace(PEEKDATA) I/O error`，所以默认绝不加：
 * - [AUTO]：容器就绪时探测一次，只有默认模式失败且失败特征确实是 seccomp 相关才切兼容；
 * - [FORCE_DEFAULT]：永远不加（加此功能前的行为）；
 * - [COMPAT]：永远加（用户已确认自己的机器需要它）。
 */
enum class ProotSeccompMode {
    AUTO,
    FORCE_DEFAULT,
    COMPAT,
}

/**
 * 持久化当前选中的容器 profile 与用户自定义 profile 列表。
 *
 * DataStore 用法与 [ThemeSettingsRepository] 一致（构造注入即可，无需 DI module）。
 * 无 [ACTIVE_PROFILE_ID_KEY] 时返回 [ContainerProfile.BUILTIN_ID]，等同改动前——默认内置 Alpine。
 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        /** Gradle 堆上限可调范围与步进（设置页滑条与配置通道共用）。 */
        const val GRADLE_HEAP_MIN_MB = 2048
        const val GRADLE_HEAP_MAX_MB = 8192
        const val GRADLE_HEAP_STEP_MB = 512

        /**
         * 远程模式并发 SFTP 通道上限：默认 3，范围 1-20。
         *
         * OpenSSH 默认 MaxSessions=10，还要留给 exec、终端与端口转发，所以默认给 3：够并行工具调用
         * 同时读写，又不会因为一次批量扫描就撞上服务器的会话上限。
         */
        const val DEFAULT_SFTP_CHANNEL_LIMIT = 3
        const val MIN_SFTP_CHANNEL_LIMIT = 1
        const val MAX_SFTP_CHANNEL_LIMIT = 20

        /** 设备总内存（MB）；拿不到时按 4GB 计。 */
        fun deviceTotalMemMb(context: Context): Int = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // ActivityManager 没有 memoryInfo 属性：getMemoryInfo 要求传入结果对象
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)).toInt()
        }.getOrDefault(4096)

        /**
         * 按设备总内存推荐的堆上限。
         *
         * 刻意比「总内存的几分之一」更保守：容器能拿到的可用内存远小于总内存（宿主上还有一堆应用），
         * 堆上限超过实际可用内存时 JVM 迟迟不 GC，最终被内核 OOM 杀掉、编译无声中断。
         * 内存宽裕的用户可以在设置页往上调。
         *
         * 阈值按 11/7GB 而非标称的 12/8GB：`totalMem` 报的是内核可用物理内存，标称值总要扣掉
         * 保留内存与 GPU 共享段，12GB 机器通常只报 11.1~11.5GB、8GB 只报 7.4~7.8GB。
         */
        fun recommendedGradleHeapMb(totalMemMb: Int): Int = when {
            totalMemMb >= 11 * 1024 -> 3072
            totalMemMb >= 7 * 1024 -> 2560
            else -> GRADLE_HEAP_MIN_MB
        }

        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CUSTOM_PROFILES_KEY = stringPreferencesKey("custom_profiles_json")
        /** 远程工作区模式下本地 MCP stdio 等服务的运行容器；无值时默认内置 Alpine。 */
        val DEFAULT_CONTAINER_ID_KEY = stringPreferencesKey("default_container_profile_id")
        /** 首次启动是否已写入内置 Alpine 默认项；置位后用户删光列表不再自动补回。 */
        val INITIALIZED_KEY = booleanPreferencesKey("initialized")
        /** 列表顺序是否已归一为「新建在前」的显示序；见 [ensureProfileDisplayOrder]。 */
        val ORDER_MIGRATED_KEY = booleanPreferencesKey("profile_order_migrated")
        /** 「容器与镜像」页使用说明公告已展示内容的哈希；无值或与当前内容哈希不一致时重新弹出。 */
        val ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("container_announcement_shown_hash")
        /** 下载镜像页已下载/已安装的记录（JSON 列表）。 */
        val DOWNLOADED_IMAGES_KEY = stringPreferencesKey("downloaded_images_json")
        /** 容器内编译 Gradle 工程时的 JVM 堆上限（MB）；无值时按设备内存推荐。 */
        val GRADLE_HEAP_MB_KEY = intPreferencesKey("gradle_heap_mb")
        /** 远程模式并发 SFTP 通道上限；无值时用 [DEFAULT_SFTP_CHANNEL_LIMIT]。 */
        val SFTP_CHANNEL_LIMIT_KEY = intPreferencesKey("sftp_channel_limit")
        /** 工作区路径 → profile id 的覆盖表（JSON 对象）；键已由 [ContainerWorkspaceKeys] 归一化。 */
        val WORKSPACE_PROFILES_KEY = stringPreferencesKey("workspace_profiles_json")
        /** PRoot seccomp 兼容模式；无值时 AUTO。 */
        val PROOT_SECCOMP_MODE_KEY = stringPreferencesKey("proot_seccomp_mode")
        /** seccomp 探测结论缓存（JSON 对象：缓存键 → COMPAT / DEFAULT）。缓存键见 [seccompCacheKey]。 */
        val PROOT_SECCOMP_VERDICTS_KEY = stringPreferencesKey("proot_seccomp_verdicts_json")

        /** 系统指纹（ROM / OTA 一换就变）：seccomp 结论缓存按它失效，避免把旧 ROM 的结论带到新 ROM 上。 */
        fun deviceFingerprint(): String =
            "${Build.FINGERPRINT}|${Build.VERSION.SDK_INT}|${Build.VERSION.INCREMENTAL}"

        val downloadedImageSerializer = ListSerializer(DownloadedImageRecord.serializer())
        val profileSerializer = ListSerializer(ContainerProfile.serializer())
        val workspaceProfilesSerializer = MapSerializer(String.serializer(), String.serializer())
        val seccompVerdictsSerializer = MapSerializer(String.serializer(), String.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /** 当前选中的 profile id；无值时默认内置 Alpine。 */
    val activeProfileIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /**
     * 工作区 → profile id 覆盖表（键为归一化后的工作区绝对路径）。
     *
     * 表里没有某个工作区时，该工作区的命令走 [activeProfileIdFlow] 的全局默认——与改动前完全一致；
     * 只有用户显式 add 过的工作区才会用别的容器。
     */
    val workspaceProfilesFlow: Flow<Map<String, String>> = context.containerDataStore.data.map { prefs ->
        decodeWorkspaceProfiles(prefs[WORKSPACE_PROFILES_KEY])
    }

    /** 远程模式下的默认容器 id；无值时默认内置 Alpine。 */
    val defaultContainerIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[DEFAULT_CONTAINER_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** PRoot seccomp 模式；无值（含加此功能前的历史数据）时 [ProotSeccompMode.AUTO]。 */
    val prootSeccompModeFlow: Flow<ProotSeccompMode> = context.containerDataStore.data.map { prefs ->
        prefs[PROOT_SECCOMP_MODE_KEY]
            ?.let { name -> ProotSeccompMode.entries.firstOrNull { it.name == name } }
            ?: ProotSeccompMode.AUTO
    }

    suspend fun setProotSeccompMode(mode: ProotSeccompMode) {
        context.containerDataStore.edit { it[PROOT_SECCOMP_MODE_KEY] = mode.name }
    }

    /** 探测结论的缓存键：profile + 发行版 + 系统指纹。换 ROM / OTA 后指纹变化，旧结论自然失效。 */
    fun seccompCacheKey(profileId: String, distroId: String?): String =
        "$profileId|${distroId?.takeIf { it.isNotBlank() } ?: "unknown"}|${deviceFingerprint()}"

    /** 读缓存结论：null = 没探过；true = 用兼容模式；false = 保持默认。 */
    suspend fun seccompVerdict(key: String): Boolean? {
        val raw = context.containerDataStore.data.first()[PROOT_SECCOMP_VERDICTS_KEY] ?: return null
        return decodeSeccompVerdicts(raw)[key]?.let { it == "COMPAT" }
    }

    /** 写入一条结论；顺带清掉其它系统指纹下的旧结论（与当前 ROM 无关，留着只会越积越多）。 */
    suspend fun putSeccompVerdict(key: String, compat: Boolean) {
        val suffix = "|${deviceFingerprint()}"
        context.containerDataStore.edit { prefs ->
            val kept = decodeSeccompVerdicts(prefs[PROOT_SECCOMP_VERDICTS_KEY])
                .filterKeys { it.endsWith(suffix) }
            prefs[PROOT_SECCOMP_VERDICTS_KEY] = json.encodeToString(
                seccompVerdictsSerializer,
                kept + (key to if (compat) "COMPAT" else "DEFAULT"),
            )
        }
    }

    /**
     * 清掉某个容器已缓存的探测结论（用户在设置里切了 PRoot 兼容模式后调用，保证新模式立即生效）。
     *
     * 键的格式是「profileId|发行版|系统指纹」，所以按前缀匹配就能把该容器在各发行版下的条目都删掉；
     * 另一容器的结论不动——那是「这台机器上那个容器能不能跑默认模式」的事实，与本次模式选择无关。
     */
    suspend fun clearSeccompVerdicts(profileId: String) {
        val prefix = "$profileId|"
        context.containerDataStore.edit { prefs ->
            val kept = decodeSeccompVerdicts(prefs[PROOT_SECCOMP_VERDICTS_KEY])
                .filterKeys { !it.startsWith(prefix) }
            if (kept.isEmpty()) {
                prefs.remove(PROOT_SECCOMP_VERDICTS_KEY)
            } else {
                prefs[PROOT_SECCOMP_VERDICTS_KEY] = json.encodeToString(seccompVerdictsSerializer, kept)
            }
        }
    }

    /** 解析结论缓存；历史 / 损坏数据回退空表（等同没探过，重新探测）。 */
    private fun decodeSeccompVerdicts(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(seccompVerdictsSerializer, it) }.getOrNull() }
            ?: emptyMap()

    /** 用户自定义 profile 列表（不含内置）。解析失败回退空列表。 */
    val customProfilesFlow: Flow<List<ContainerProfile>> = context.containerDataStore.data.map { prefs ->
        prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    /** 设备总内存（MB），只查一次：binder IPC，不必每次 flow 发射都问。 */
    private val totalMemMb: Int by lazy { deviceTotalMemMb(context) }

    /** Gradle 堆上限（MB）；未设置过时按设备内存给推荐值。 */
    val gradleHeapMbFlow: Flow<Int> = context.containerDataStore.data.map { prefs ->
        prefs[GRADLE_HEAP_MB_KEY]?.coerceIn(GRADLE_HEAP_MIN_MB, GRADLE_HEAP_MAX_MB)
            ?: recommendedGradleHeapMb(totalMemMb)
    }

    /** 写入前对齐到滑条步进：配置通道可以传任意值，3000 这种会让滑条停在两刻度之间。 */
    suspend fun setGradleHeapMb(mb: Int) {
        val aligned = (mb / GRADLE_HEAP_STEP_MB) * GRADLE_HEAP_STEP_MB
        context.containerDataStore.edit {
            it[GRADLE_HEAP_MB_KEY] = aligned.coerceIn(GRADLE_HEAP_MIN_MB, GRADLE_HEAP_MAX_MB)
        }
    }

    /** 远程模式并发 SFTP 通道上限；未设置过时用 [DEFAULT_SFTP_CHANNEL_LIMIT]。 */
    val sftpChannelLimitFlow: Flow<Int> = context.containerDataStore.data.map { prefs ->
        (prefs[SFTP_CHANNEL_LIMIT_KEY] ?: DEFAULT_SFTP_CHANNEL_LIMIT)
            .coerceIn(MIN_SFTP_CHANNEL_LIMIT, MAX_SFTP_CHANNEL_LIMIT)
    }

    suspend fun setSftpChannelLimit(limit: Int) {
        context.containerDataStore.edit {
            it[SFTP_CHANNEL_LIMIT_KEY] = limit.coerceIn(MIN_SFTP_CHANNEL_LIMIT, MAX_SFTP_CHANNEL_LIMIT)
        }
    }

    suspend fun setActiveProfile(id: String) {
        context.containerDataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    suspend fun setDefaultContainerId(id: String) {
        context.containerDataStore.edit { it[DEFAULT_CONTAINER_ID_KEY] = id }
    }

    /**
     * 为某个工作区指定容器 profile（键归一化后落盘）。已存在则覆盖。
     * 传无意义的工作区路径时什么都不做，避免写入一条永远匹配不上的记录。
     */
    suspend fun putWorkspaceProfile(workspacePath: String, profileId: String) {
        val key = ContainerWorkspaceKeys.normalize(workspacePath) ?: return
        context.containerDataStore.edit { prefs ->
            val current = decodeWorkspaceProfiles(prefs[WORKSPACE_PROFILES_KEY])
            prefs[WORKSPACE_PROFILES_KEY] = json.encodeToString(
                workspaceProfilesSerializer, current + (key to profileId)
            )
        }
    }

    /** 去掉某个工作区的容器覆盖（归一化后匹配）；返回是否真的删掉了一条。 */
    suspend fun removeWorkspaceProfile(workspacePath: String): Boolean {
        val key = ContainerWorkspaceKeys.normalize(workspacePath) ?: return false
        var removed = false
        context.containerDataStore.edit { prefs ->
            val current = decodeWorkspaceProfiles(prefs[WORKSPACE_PROFILES_KEY])
            if (key !in current) return@edit
            removed = true
            prefs[WORKSPACE_PROFILES_KEY] = json.encodeToString(
                workspaceProfilesSerializer, current - key
            )
        }
        return removed
    }

    /** 解析覆盖表 JSON；历史/损坏数据回退空表（等同于没配覆盖，走全局默认）。 */
    private fun decodeWorkspaceProfiles(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(workspaceProfilesSerializer, it) }.getOrNull() }
            ?: emptyMap()

    /**
     * 首次启动（或旧版本升级）兜底：把内置 Alpine 补入列表（若缺），保证列表始终有内置项。
     * 置位初始化标记后不再自动补回——用户后续删光列表由空态手动恢复。
     * 存量设备已配自定义镜像时同样补入（旧版列表始终含内置项，升级不能让它消失）。
     */
    suspend fun ensureBuiltinDefault() {
        context.containerDataStore.edit { prefs ->
            if (prefs[INITIALIZED_KEY] == true) return@edit
            prefs[INITIALIZED_KEY] = true
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            if (current.none { it.id == ContainerProfile.BUILTIN_ID }) {
                prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(
                    profileSerializer, listOf(ContainerProfile.BUILTIN_ALPINE) + current
                )
            }
        }
    }

    /**
     * 存量用户顺序归一：列表历史上「新建在前」是靠读取时按 createdAt 倒序排出来的，
     * 存储顺序其实是插入序（旧在前）。改用显式拖拽顺序后不再实时排序，
     * 故首次升级把存储顺序按 createdAt 倒序重写一次，保证升级前后用户看到的一致。
     */
    suspend fun ensureProfileDisplayOrder() {
        context.containerDataStore.edit { prefs ->
            if (prefs[ORDER_MIGRATED_KEY] == true) return@edit
            prefs[ORDER_MIGRATED_KEY] = true
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(
                profileSerializer, current.sortedByDescending { it.createdAt }
            )
        }
    }

    /**
     * 按传入 id 顺序重排列表（只改顺序，不碰内容）。
     * 未出现在 [orderedIds] 里的项（并发新增）按原相对顺序追加到末尾，不会丢配置。
     */
    suspend fun reorderCustomProfiles(orderedIds: List<String>) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val byId = current.associateBy { it.id }
            val ordered = orderedIds.mapNotNull { byId[it] } + current.filterNot { it.id in orderedIds }
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(profileSerializer, ordered)
        }
    }

    /** 已展示公告内容的哈希；无值表示从未展示过。 */
    val announcementShownHashFlow: Flow<String?> = context.containerDataStore.data.map { prefs ->
        prefs[ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markAnnouncementShown(hash: String) {
        context.containerDataStore.edit { it[ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** 已下载镜像记录列表（按 entryId 去重，最新覆盖旧的）。 */
    val downloadedImagesFlow: Flow<List<DownloadedImageRecord>> = context.containerDataStore.data.map { prefs ->
        prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun upsertDownloadedImage(record: DownloadedImageRecord) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val merged = current.filterNot { it.entryId == record.entryId } + record
            prefs[DOWNLOADED_IMAGES_KEY] = json.encodeToString(downloadedImageSerializer, merged)
        }
    }

    suspend fun removeDownloadedImage(entryId: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[DOWNLOADED_IMAGES_KEY] =
                json.encodeToString(downloadedImageSerializer, current.filterNot { it.entryId == entryId })
        }
    }

    /** 批量移除已下载镜像记录：只做一次读改写，避免逐条写触发多次 DataStore 发射。 */
    suspend fun removeDownloadedImages(entryIds: Collection<String>) {
        if (entryIds.isEmpty()) return
        val targets = entryIds.toSet()
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[DOWNLOADED_IMAGES_KEY] =
                json.encodeToString(downloadedImageSerializer, current.filterNot { it.entryId in targets })
        }
    }

    /**
     * 新增或覆盖同名 id 的自定义 profile。
     * createdAt 规则：传入非 0 则用传入值；为 0 时若原记录存在（编辑场景，表单重建丢了时间）保留原时间。
     * 位置规则：列表顺序由用户拖拽决定（存储顺序即显示顺序），故新增插到最前（沿用旧的「新建在前」观感），
     * 编辑原地替换、不动位置。
     */
    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val existing = current.firstOrNull { it.id == profile.id }
            val final = when {
                profile.createdAt != 0L -> profile
                existing != null && existing.createdAt != 0L -> profile.copy(createdAt = existing.createdAt)
                else -> profile.copy(createdAt = System.currentTimeMillis())
            }
            val merged = if (existing != null) {
                current.map { if (it.id == final.id) final else it }
            } else {
                listOf(final) + current
            }
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(profileSerializer, merged)
        }
    }

    suspend fun deleteCustomProfile(id: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[CUSTOM_PROFILES_KEY] =
                json.encodeToString(profileSerializer, current.filterNot { it.id == id })
        }
    }
}
