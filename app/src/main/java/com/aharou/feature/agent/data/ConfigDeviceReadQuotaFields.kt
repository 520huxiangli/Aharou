package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.personal.DeviceReadCategory
import com.aharou.feature.agent.domain.personal.DeviceReadQuota
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备读取配额 → 配置通道。
 *
 * 每类数据源三个字段：24 小时上限、最小间隔（都可写），当前已读次数（只读）。
 * 路径前缀固定为 `deviceReadQuota.<category>`，类别 id 与 [DeviceReadCategory.id] 一致，
 * 换类名不用改这里。
 *
 * 上限可调而不是写死，是因为「一天读 60 次位置」这种数属于使用习惯，给用户留出口比替用户
 * 拍板更稳妥；0 表示不设上限，也就是说这套机制随时可以被用户整条关掉。
 */
@Singleton
class ConfigDeviceReadQuotaFields @Inject constructor(
    private val quota: DeviceReadQuota,
) {

    fun registerInto(registry: ConfigRegistry) {
        DeviceReadCategory.values().forEach { category ->
            val label = labelOf(category)
            registry.register(
                ClosureField(
                    path = "deviceReadQuota.${category.id}.daily_limit",
                    displayName = "$label 24 小时读取上限",
                    description = "后台自动触发的运行里，滚动 24 小时内最多读几次$label；" +
                        "0 表示不设上限、只保留最小间隔。用户在对话里手动让它读不受这个限制。",
                    valueSchema = ConfigSchema.Int(min = 0),
                    reader = { ConfigValue.Int(quota.limitOf(category)) },
                    writer = { value -> quota.setLimit(category, (value as ConfigValue.Int).value) },
                )
            )
            registry.register(
                ClosureField(
                    path = "deviceReadQuota.${category.id}.min_interval_seconds",
                    displayName = "$label 最小读取间隔（秒）",
                    description = "后台自动触发时，两次读$label 之间至少隔多少秒；0 表示不限制间隔。",
                    valueSchema = ConfigSchema.Int(min = 0),
                    reader = { ConfigValue.Int(quota.minIntervalOf(category)) },
                    writer = { value -> quota.setMinInterval(category, (value as ConfigValue.Int).value) },
                )
            )
            registry.register(
                ReadOnlyField(
                    path = "deviceReadQuota.${category.id}.used_24h",
                    displayName = "$label 24 小时已读次数",
                    description = "滚动 24 小时窗口内已经读过几次，只读。",
                    valueSchema = ConfigSchema.Int(min = 0),
                    reader = {
                        val status = quota.status().firstOrNull { it.category == category }
                        ConfigValue.Int(status?.used ?: 0)
                    },
                )
            )
        }
    }

    private fun labelOf(category: DeviceReadCategory): String = when (category) {
        DeviceReadCategory.LOCATION -> "位置"
        DeviceReadCategory.NOTIFICATION -> "通知"
        DeviceReadCategory.SMS -> "短信"
    }
}
