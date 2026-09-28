package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreEnumField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.settings.data.repository.ProxySettingsRepository
import com.aharou.feature.settings.data.repository.SyncSettingsRepository
import com.aharou.feature.settings.domain.model.ProxyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局网络代理与远程同步偏好 → 配置通道。
 *
 * 这两个仓库不是 DataStore 而是 SharedPreferences + StateFlow，但 StateFlow 本身也是 Flow，
 * 因此同样走 [DataStoreBoolField] 一族（字段类的 setter 本就是 suspend lambda，调同步函数没问题）。
 *
 * `network.proxy_password` 标为 SENSITIVE：确认弹窗与审计页会打码，但 `config get` 仍能读到真值。
 */
@Singleton
class ConfigNetworkFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proxySettings: ProxySettingsRepository,
    private val syncSettings: SyncSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreBoolField(
                path = "network.proxy_enabled",
                displayName = "启用全局代理",
                description = "所有 HTTP 请求是否走下面的代理配置。",
                flow = proxySettings.config.map { it.enabled },
                setter = { proxySettings.setEnabled(it) },
            ),
        )

        registry.register(
            DataStoreEnumField(
                path = "network.proxy_type",
                displayName = "代理类型",
                description = "http 或 socks5。",
                flow = proxySettings.config.map { it.type.name.lowercase() },
                setter = { proxySettings.setType(ProxyType.valueOf(it.uppercase())) },
                cases = listOf("http", "socks5"),
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "network.proxy_host",
                displayName = "代理主机",
                description = "代理服务器地址（域名或 IP）。",
                flow = proxySettings.config.map { it.host },
                setter = { proxySettings.setHost(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "network.proxy_port",
                displayName = "代理端口",
                description = "代理服务器端口。0 = 未设置。",
                flow = proxySettings.config.map { it.port },
                setter = { proxySettings.setPort(it) },
                minValue = 0,
                maxValue = 65535,
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "network.proxy_username",
                displayName = "代理用户名",
                description = "代理认证用户名，不需要认证时留空。",
                flow = proxySettings.config.map { it.username },
                setter = { proxySettings.setUsername(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "network.proxy_password",
                displayName = "代理密码",
                description = "代理认证密码。当前以明文存在 SharedPreferences 里。",
                flow = proxySettings.config.map { it.password },
                setter = { proxySettings.setPassword(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "network.proxy_no_proxy",
                displayName = "代理排除列表",
                description = "不走代理的地址，逗号分隔（默认已含 localhost 与内网网段）。",
                flow = proxySettings.config.map { it.noProxy },
                setter = { proxySettings.setNoProxy(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "network.sync_ignored_patterns",
                displayName = "同步忽略规则",
                description = "远程同步时忽略的目录 / 文件模式，逗号分隔。",
                flow = syncSettings.ignoredPatterns,
                setter = { syncSettings.setIgnoredPatterns(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "network.sync_use_gitignore",
                displayName = "同步遵循 .gitignore",
                description = "远程同步时按工作区的 .gitignore 跳过文件。默认开启。",
                flow = syncSettings.useGitIgnore,
                setter = { syncSettings.setUseGitIgnore(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "network.sync_max_batch_size",
                displayName = "同步批次大小",
                description = "单次远程同步最多处理的文件数。默认 5。",
                flow = syncSettings.maxSyncBatchSize,
                setter = { syncSettings.setMaxSyncBatchSize(it) },
                minValue = 1,
            ),
        )
    }
}
