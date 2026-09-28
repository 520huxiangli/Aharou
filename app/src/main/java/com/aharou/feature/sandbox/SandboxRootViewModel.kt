package com.aharou.feature.sandbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

/**
 * 沙箱文件页的浏览根：当前选中容器的 rootfs 宿主目录（即容器内的 `/`）。
 *
 * 以前这里写死内置 Alpine 的 `filesDir/rootfs`，用自定义镜像的人点进去永远是「空文件夹」——
 * 内置那份压根没装过。改为跟随 [ContainerSettingsRepository.activeProfileIdFlow]。
 */
@HiltViewModel
class SandboxRootViewModel @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    containerSettingsRepository: ContainerSettingsRepository,
) : ViewModel() {

    val rootfsDir: StateFlow<File> = combine(
        containerSettingsRepository.activeProfileIdFlow,
        containerSettingsRepository.customProfilesFlow,
    ) { id, profiles ->
        val profile = profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
        containerInstaller.rootfsDirFor(profile)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        containerInstaller.rootfsDirFor(ContainerProfile.BUILTIN_ALPINE),
    )
}
