package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「这次执行该用哪个容器」的唯一解析口。
 *
 * 两件事分开：
 *  - [defaultProfile]：全局默认容器（`container.active_profile`），给没有工作区参数的调用方用
 *    （isContainerInstalled / defaultShell / notReadyHint / ensureInstalled）。
 *  - [forWorkspace]：按工作区解析，覆盖表（`container.workspace_profiles`）里有该工作区就用它，
 *    没有就走全局默认。**默认行为与加覆盖表之前完全一致**。
 *
 * 并发安全靠「无共享可变状态」：三份来源都是 [@Volatile] 的不可变快照（整表替换，从不在原地改），
 * 解析是纯函数查表，不加锁。两个工作区并发执行时各自拿到自己的 profile，沿调用链传参，
 * 不会互相覆盖。
 */
@Singleton
class ContainerProfileResolver @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerInstaller: ContainerInstaller
) {
    /** 全局默认 profile；启动首帧为内置 Alpine（等同改动前），flow 发射后更新。 */
    @Volatile
    private var defaultProfileCache: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    /** 当前选中的 profile id（`container.active_profile` 最新值）。 */
    @Volatile
    private var activeProfileId: String = ContainerProfile.BUILTIN_ID

    /** 自定义 profile 列表快照，让同步执行路径不读 DataStore 也能按 id 解析。 */
    @Volatile
    private var customProfiles: List<ContainerProfile> = emptyList()

    /** 工作区 → profile id 覆盖表快照（键已归一化）；空表即「全部走全局默认」。 */
    @Volatile
    private var workspaceOverrides: Map<String, String> = emptyMap()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                activeProfileId = id
                refreshDefaultProfile()
            }
        }
        // 列表增删改后要重算默认 profile：它可能就是列表里那个自定义 profile，
        // 只在 activeProfileId 发射时算一次会永远停在启动时的内置 Alpine。
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.customProfilesFlow.collect { profiles ->
                customProfiles = profiles
                refreshDefaultProfile()
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.workspaceProfilesFlow.collect { overrides ->
                workspaceOverrides = overrides
            }
        }
    }

    /** 全局默认容器（没有指定工作区时用的那个）。 */
    fun defaultProfile(): ContainerProfile = defaultProfileCache

    /**
     * 本次执行该用的 profile：工作区覆盖优先，没配覆盖 → 全局默认。
     *
     * 归属查找（精确匹配 + 父目录上溯）见 [ContainerWorkspaceKeys.overrideFor]。
     * 覆盖项指向的 profile 已被删除时退回全局默认，避免落到列表里第一个、完全无关的容器。
     */
    fun forWorkspace(projectPath: String?): ContainerProfile {
        if (workspaceOverrides.isEmpty()) return defaultProfileCache
        val id = ContainerWorkspaceKeys.overrideFor(workspaceOverrides, projectPath) ?: return defaultProfileCache
        return customProfiles.firstOrNull { it.id == id } ?: defaultProfileCache
    }

    /**
     * 终端 PTY 用的 profile：同 [forWorkspace]，但覆盖容器的 rootfs 还没安装时退回全局默认。
     *
     * 终端页的初始化与就绪门禁（ensureInstalled / isContainerInstalled）没有工作区参数、始终面向
     * 全局默认容器；这里若硬用未安装的覆盖容器，proot 会因 rootfs 不存在直接起不来（终端一片空白）。
     * 退回去至少终端可用，原因写进日志。
     */
    fun forTerminal(projectPath: String?): ContainerProfile {
        val profile = forWorkspace(projectPath)
        if (profile.id == defaultProfileCache.id || containerInstaller.isInstalledFor(profile)) return profile
        FileLogger.w(
            "ContainerProfileResolver",
            "工作区 $projectPath 指定的容器 ${profile.id}(${profile.name}) 尚未安装，终端暂用全局默认容器 " +
                "${defaultProfileCache.id}；在该容器完成初始化后自动生效"
        )
        return defaultProfileCache
    }

    /**
     * 按 id 解析 profile：从持久化列表找，找不到回退列表第一个，再兜底内置 Alpine（保证引擎始终可用）。
     * 列表里持久化的 Alpine 覆盖项优先于 [ContainerProfile.BUILTIN_ALPINE] 常量（用户编辑过的配置生效）。
     */
    private fun byId(id: String): ContainerProfile =
        customProfiles.firstOrNull { it.id == id }
            ?: customProfiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE

    private fun refreshDefaultProfile() {
        defaultProfileCache = byId(activeProfileId)
    }
}
