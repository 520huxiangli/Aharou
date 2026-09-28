package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 容器设置 → 配置通道。
 *
 * 只接「选哪个容器」这类标量；自定义 profile 本体是结构化的镜像定义，走
 * [com.aharou.core.config.ConfigCollection] 增删（见后续批次），不在这里展开成字段。
 */
@Singleton
class ConfigContainerFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerSettings: ContainerSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreStrField(
                path = "container.active_profile",
                displayName = "当前容器",
                description = "终端 / 命令执行使用的容器 profile id。默认 ${ContainerProfile.BUILTIN_ID}（内置 Alpine）。" +
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
    }
}
