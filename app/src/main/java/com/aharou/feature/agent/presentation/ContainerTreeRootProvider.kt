package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.container.ContainerOsDetector
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 「当前容器」根节点的显示名与图标来源：名称取当前选中的 profile，系统类型取已检测缓存，
 * 本地 / 远程看该 profile 的执行模式。
 *
 * 放在独立类里而非 ViewModel 内，是为了不再往已到行数上限的 AIAgentViewModel 里堆逻辑。
 */
@Singleton
class ContainerTreeRootProvider @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerOsDetector: ContainerOsDetector
) {
    val containerRoot: Flow<FileTreeRoot.Container> = combine(
        combine(
            containerSettingsRepository.activeProfileIdFlow,
            containerSettingsRepository.customProfilesFlow
        ) { activeId, profiles -> resolveActiveProfile(activeId, profiles) },
        containerOsDetector.osMap
    ) { profile, osMap ->
        FileTreeRoot.Container(
            label = profile.name,
            osId = osMap[profile.id],
            remote = profile.mode == ExecutionMode.REMOTE_SSH
        )
    }

    /**
     * 当前选中的 profile：id 已不在列表里（被删除）时回退列表首项，列表空回退内置 Alpine，
     * 保证调用方总能拿到一个可用 profile。
     */
    private fun resolveActiveProfile(activeId: String, profiles: List<ContainerProfile>): ContainerProfile =
        profiles.firstOrNull { it.id == activeId }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeId }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
}
