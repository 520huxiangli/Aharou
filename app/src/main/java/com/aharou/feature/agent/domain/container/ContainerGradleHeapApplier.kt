package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 把设置里的 Gradle 堆上限落到容器内用户级 `~/.gradle/gradle.properties`。
 *
 * 挂在每次启动容器进程前（幂等，内容没变不写盘）而不是设置页保存回调：rootfs 重建、换容器、
 * 旧版升级上来的容器都会在下一次命令时自动对齐，不用用户回设置页重新点一次。
 *
 * 值缓存自 [ContainerSettingsRepository]：启动进程的调用链上不能同步读 DataStore。
 */
@Singleton
class ContainerGradleHeapApplier @Inject constructor(
    private val settings: ContainerSettingsRepository,
    private val installer: ContainerInstaller,
) {
    /** 0 表示还没拿到设置值，此时不动容器内的 gradle.properties。 */
    @Volatile
    private var heapMb: Int = 0

    init {
        CoroutineScope(Dispatchers.IO).launch {
            // DataStore 读失败会在收集时抛出，未捕获会走线程默认处理器直接崩 App。
            settings.gradleHeapMbFlow
                .catch { FileLogger.w(TAG, "读取 Gradle 堆上限设置失败，沿用上次值", it) }
                .collect { mb ->
                    heapMb = mb
                    applyActiveProfile()
                }
        }
    }

    fun apply(profile: ContainerProfile) {
        if (profile.mode == ExecutionMode.REMOTE_SSH) return
        // 只设 rootfsSource 没设 mode 的 profile 也不能在本地建目录：远程通道没有本地 rootfs。
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return
        val heap = heapMb
        if (heap <= 0 || !installer.isInstalledFor(profile)) return
        runCatching { installer.syncGradleHeap(installer.rootfsDirFor(profile), heap) }
            .onFailure { FileLogger.w(TAG, "写入容器 Gradle 堆上限失败", it) }
    }

    /**
     * 设置变化后立刻对当前激活的 profile 落盘一次。
     *
     * 终端标签只在建标签时构建一次 argv，已打开的标签不会再读设置；只更新缓存要等下一次起进程才生效。
     */
    private suspend fun applyActiveProfile() {
        val profile = runCatching {
            val activeId = settings.activeProfileIdFlow.first()
            if (activeId == ContainerProfile.BUILTIN_ID) {
                ContainerProfile.BUILTIN_ALPINE
            } else {
                settings.customProfilesFlow.first().firstOrNull { it.id == activeId }
            }
        }.onFailure { FileLogger.w(TAG, "解析当前容器失败，跳过本次落盘", it) }.getOrNull() ?: return
        runCatching { apply(profile) }
            .onFailure { FileLogger.w(TAG, "立即写入 Gradle 堆上限失败", it) }
    }

    private companion object {
        const val TAG = "ContainerGradleHeap"
    }
}
