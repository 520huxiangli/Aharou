package com.aharou.feature.pet.data

import android.content.Context
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.pet.PetDailyBrief
import com.aharou.feature.pet.PetOverlay
import com.aharou.feature.pet.PetOverlayService
import com.aharou.feature.pet.PetRoles
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小染（桌宠）设置 → 配置通道。
 *
 * 所有字段都落在同一个 SharedPreferences 文件 `aharou_pet_prefs`（[PetOverlay.PREFS]）。
 * 写入一律走 [PetOverlayService] / [PetOverlay] 的既有入口，与设置页 [com.aharou.feature.settings.presentation.component.PetSection]
 * 的行为保持一致：服务在跑就广播通知立即生效，没在跑就下次 attach 生效。
 *
 * 运行时的「隐藏」状态（`hidden`）与心情好感度属于临时状态而非设置，刻意不接入。
 */
@Singleton
class ConfigPetFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "pet.enabled",
                displayName = "桌宠总开关",
                description = "是否让小染作为悬浮窗常驻。开启会顺带清掉「隐藏」状态并启动悬浮服务，关闭则停掉服务。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(PetOverlayService.isEnabled(context)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    if (on) {
                        PetOverlayService.setEnabled(context, true)
                        PetOverlayService.applyHidden(context, false)
                        PetOverlayService.start(context)
                    } else {
                        PetOverlayService.setEnabled(context, false)
                        PetOverlayService.stop(context)
                    }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.always",
                displayName = "始终显示小染",
                description = "不只在 AI 工作时露面，而是常驻屏幕（关闭即退回「仅 Agent 工作时出现」）。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(prefs.getBoolean(PetOverlay.KEY_ALWAYS, true)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    PetOverlayService.applyAlways(context, on)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.size",
                displayName = "小染大小",
                description = "悬浮窗高度档位：small（100dp）/ medium（140dp，默认）/ large（190dp）。",
                valueSchema = ConfigSchema.StrEnum(listOf("small", "medium", "large")),
                revertable = true,
                reader = { ConfigValue.Str(PetOverlay.readSize(context).key) },
                writer = { v ->
                    val key = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    PetOverlayService.applySize(context, PetOverlay.Size.from(key))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.role",
                displayName = "小染角色",
                description = "角色素材 id（对应 assets/pet/<id>/），可选值见 assets/pet/roles.json。默认 xiaoran。",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                revertable = true,
                reader = { ConfigValue.Str(PetOverlay.readRole(context)) },
                writer = { v ->
                    val id = (v as? ConfigValue.Str)?.value?.trim()
                        ?: throw ConfigError.TypeMismatch("string")
                    if (id.isEmpty()) throw ConfigError.InvalidValue("角色 id 不能为空")
                    if (!PetRoles.exists(context, id)) throw ConfigError.InvalidValue("角色不存在：$id")
                    PetOverlayService.applyRole(context, id)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.locked",
                displayName = "锁定位置",
                description = "锁定后不能拖动小染。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(PetOverlay.readLocked(context)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    PetOverlay.writeLocked(context, on)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.pass_through",
                displayName = "摆件模式",
                description = "整个悬浮窗不接触摸，点不到小染、点击穿透到下层应用。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(PetOverlay.readPassThrough(context)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    PetOverlayService.applyPassThrough(context, on)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "pet.brief_enabled",
                displayName = "每日问候",
                description = "每天早上第一次现身时报一句（节日祝福优先，其次天气提醒）。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(PetDailyBrief.readEnabled(context)) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    PetDailyBrief.writeEnabled(context, on)
                },
            ),
        )
    }
}
