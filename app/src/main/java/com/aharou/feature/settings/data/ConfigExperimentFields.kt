package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.domain.ExperimentCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实验开关 → 配置通道：每个内置实验一个 `app.experiments.<key>` 字段，取值 follow/on/off。
 *
 * 存的是 SharedPreferences，所以用 [ClosureField] 桥接，读写都同步。
 */
@Singleton
class ConfigExperimentFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ExperimentSettings
) {

    fun registerInto(registry: ConfigRegistry) = with(registry) {
        ExperimentCatalog.ALL.forEach { experiment ->
            register(
                ClosureField(
                    path = "app.experiments.${experiment.key}",
                    displayName = context.getString(experiment.titleRes),
                    description = context.getString(experiment.descriptionRes) +
                        "；follow 跟随内置默认（当前默认 " +
                        (if (experiment.defaultEnabled) "开" else "关") + "）。",
                    valueSchema = ConfigSchema.StrEnum(ExperimentSettings.VALUES),
                    reader = { ConfigValue.Str(settings.value(experiment.key)) },
                    writer = { v ->
                        settings.setValue(
                            experiment.key,
                            (v as? ConfigValue.Str)?.value ?: ExperimentSettings.FOLLOW
                        )
                    }
                )
            )
        }
    }
}
