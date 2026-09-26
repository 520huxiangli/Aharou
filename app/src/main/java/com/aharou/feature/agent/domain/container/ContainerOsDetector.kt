package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 容器系统类型检测结果的缓存（profile id → os id，如 `alpine`/`centos`）。
 *
 * 检测由 [LinuxContainerEngine] 在容器首次运行后执行（读 /etc/os-release 的 ID 字段）并写入；
 * [ContainerInstaller] 在 rootfs 被覆盖（重置/重装/删除/换镜像）时清掉对应缓存，下次运行重新检测。
 * 缓存文件存放在 app 缓存目录 `cacheDir/container_os/`，系统清理缓存后自动重新检测，无脏数据残留。
 */
@Singleton
class ContainerOsDetector @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cacheDir = File(context.cacheDir, "container_os")

    /** 已识别的系统类型：profile id → os id。UI 据此展示容器列表的系统图标。 */
    private val _osMap = MutableStateFlow(loadAll())
    val osMap: StateFlow<Map<String, String>> = _osMap.asStateFlow()

    private fun fileFor(profileId: String): File = File(cacheDir, profileId)

    /** 读取某 profile 已缓存的系统类型，无则 null。 */
    fun cachedOs(profileId: String): String? = _osMap.value[profileId]

    /** 写入检测结果（调用方应在 IO 上下文；写失败仅告警，不影响使用）。 */
    fun cacheOs(profileId: String, osId: String) {
        runCatching {
            cacheDir.mkdirs()
            fileFor(profileId).writeText(osId)
        }.onFailure { FileLogger.w(TAG, "写入容器系统缓存失败: $profileId -> $osId", it) }
        _osMap.value = _osMap.value + (profileId to osId)
    }

    /** 清掉某 profile 的缓存（rootfs 被覆盖后调用，下次运行重新检测）。 */
    fun clear(profileId: String) {
        runCatching { fileFor(profileId).delete() }
        _osMap.value = _osMap.value - profileId
    }

    private fun loadAll(): Map<String, String> {
        val files = runCatching { cacheDir.listFiles()?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        return files.filter { it.isFile }
            .mapNotNull { file ->
                val osId = runCatching { file.readText().trim().ifBlank { null } }.getOrNull()
                if (osId != null) file.name to osId else null
            }
            .toMap()
    }

    private companion object {
        const val TAG = "ContainerOsDetector"
    }
}
