package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.steward.StewardSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管（Steward Mode）→ 配置通道。
 *
 * 开关与轮数存在 SharedPreferences（`aharou_steward_prefs`）而不是 DataStore，
 * 所以用 [ClosureField] 桥接；那三个属性都是同步读写，正好对得上。
 */
@Singleton
class ConfigStewardFields @Inject constructor(
    private val steward: StewardSettings,
) {

    fun registerInto(registry: ConfigRegistry) = with(registry) {
        register(
            ClosureField(
                path = "steward.enabled",
                displayName = "智能托管",
                description = "开启后每轮回答结束会旁路判断最初任务是否做完，没做完就把下一步指令续发进同一会话。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(steward.enabled) },
                writer = { v -> steward.enabled = (v as? ConfigValue.Bool)?.value ?: false },
            ),
        )
        register(
            ClosureField(
                path = "steward.max_loops",
                displayName = "托管轮数上限",
                description = "自动续发最多几轮，范围 ${StewardSettings.MIN_LOOPS}..${StewardSettings.MAX_LOOPS}，" +
                    "默认 ${StewardSettings.DEFAULT_MAX_LOOPS}。超过范围会被自动收敛。",
                valueSchema = ConfigSchema.Int(StewardSettings.MIN_LOOPS, StewardSettings.MAX_LOOPS),
                reader = { ConfigValue.Int(steward.maxLoops) },
                writer = { v ->
                    steward.maxLoops = (v as? ConfigValue.Int)?.value ?: StewardSettings.DEFAULT_MAX_LOOPS
                },
            ),
        )
        register(
            ClosureField(
                path = "steward.unlimited",
                displayName = "不限轮数",
                description = "开启后不受上面的轮数上限限制，但仍受控制器里的硬上限与超时约束。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(steward.unlimited) },
                writer = { v -> steward.unlimited = (v as? ConfigValue.Bool)?.value ?: false },
            ),
        )
    }
}
