package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerWorkspaceKeys
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一个工作区的「运行环境」行：[profileId] 为 null 表示跟随全局默认。 */
data class WorkspaceProfileRow(
    val workspaceName: String,
    val workspacePath: String,
    /** 覆盖到的容器 id；null = 跟随全局。 */
    val profileId: String?,
    /** 覆盖容器的显示名；跟随全局时为 null。 */
    val profileName: String?,
)

data class ContainerWorkspaceProfilesUiState(
    val rows: List<WorkspaceProfileRow> = emptyList(),
    /** 可选容器（含内置 Alpine：列表就绪前先补一项内置，避免下拉空着）。 */
    val profiles: List<ContainerProfile> = emptyList(),
    /** 全局默认容器的显示名，「跟随全局（X）」里那个 X。 */
    val defaultProfileName: String = ContainerProfile.BUILTIN_ALPINE.name,
)

/**
 * 「容器 → 工作区运行环境」分区状态。
 *
 * 只读 `container.workspace_profiles` 覆盖表，写也只写这张表；全局默认（`container.active_profile`）
 * 在别处维护，这里只拿来显示「跟随全局（X）」。远程工作区走 SSH 执行，本地容器覆盖对它没有意义，
 * 列表里不出现。
 */
@HiltViewModel
class ContainerWorkspaceProfilesViewModel @Inject constructor(
    private val containerSettings: ContainerSettingsRepository,
    workspaceRepository: WorkspaceRepository,
) : ViewModel() {

    val state: StateFlow<ContainerWorkspaceProfilesUiState> = combine(
        workspaceRepository.workspaces,
        containerSettings.customProfilesFlow,
        containerSettings.activeProfileIdFlow,
        containerSettings.workspaceProfilesFlow,
    ) { workspaces, customProfiles, activeId, overrides ->
        val profiles = if (customProfiles.any { it.id == ContainerProfile.BUILTIN_ID }) {
            customProfiles
        } else {
            listOf(ContainerProfile.BUILTIN_ALPINE) + customProfiles
        }
        val defaultName = profiles.firstOrNull { it.id == activeId }?.name
            ?: profiles.firstOrNull()?.name
            ?: ContainerProfile.BUILTIN_ALPINE.name
        val rows = workspaces
            .filter { it.type != WorkspaceType.REMOTE }
            .map { workspace ->
                val overrideId = ContainerWorkspaceKeys.overrideFor(overrides, workspace.path)
                    ?.takeIf { id -> profiles.any { it.id == id } }
                WorkspaceProfileRow(
                    workspaceName = workspace.name,
                    workspacePath = workspace.path,
                    profileId = overrideId,
                    profileName = overrideId?.let { id -> profiles.firstOrNull { it.id == id }?.name },
                )
            }
        ContainerWorkspaceProfilesUiState(rows = rows, profiles = profiles, defaultProfileName = defaultName)
    }
        // 归一化工作区路径要查文件系统（canonicalPath），不放在主线程上做。
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContainerWorkspaceProfilesUiState())

    /** 指定工作区的容器；[profileId] 传 null 表示恢复「跟随全局」（从覆盖表删掉这条）。 */
    fun setWorkspaceProfile(workspacePath: String, profileId: String?) {
        viewModelScope.launch {
            if (profileId == null) {
                containerSettings.removeWorkspaceProfile(workspacePath)
            } else {
                containerSettings.putWorkspaceProfile(workspacePath, profileId)
            }
        }
    }
}
