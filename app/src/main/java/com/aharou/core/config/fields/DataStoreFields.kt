package com.aharou.core.config.fields

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * DataStore 支撑的设置字段。
 *
 * 与 [PrefsBoolField] 等（SharedPreferences 后端）互补：本 App 的设置绝大多数存在
 * DataStore 里，而 [ConfigField.read] 是同步接口。app.language 的做法是额外维护一份
 * SharedPreferences 镜像供同步读；这里不为每个设置都做镜像（几十个字段、每加一个都要
 * 双写），直接 runBlocking 取一次，写入同理。
 */

class DataStoreBoolField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val flow: Flow<Boolean>,
    private val setter: suspend (Boolean) -> Unit,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema get() = ConfigSchema.Bool
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Bool(runBlocking { flow.first() })

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        runBlocking { setter((value as ConfigValue.Bool).value) }
    }
}

class DataStoreIntField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val flow: Flow<Int>,
    private val setter: suspend (Int) -> Unit,
    private val minValue: Int? = null,
    private val maxValue: Int? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Int(runBlocking { flow.first() })

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        runBlocking { setter((value as ConfigValue.Int).value) }
    }
}

class DataStoreStrField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val flow: Flow<String>,
    private val setter: suspend (String) -> Unit,
    private val maxLength: Int? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Str(maxLength = maxLength)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Str(runBlocking { flow.first() })

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        runBlocking { setter((value as ConfigValue.Str).value) }
    }
}

/**
 * DataStore 里以字符串（枚举名）存储的取值。Schema 用 [ConfigSchema.StrEnum]，
 * 写入前由它校验是否落在 [cases] 内。
 */
class DataStoreEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val flow: Flow<String>,
    private val setter: suspend (String) -> Unit,
    private val cases: List<String>,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Str(runBlocking { flow.first() })

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        runBlocking { setter((value as ConfigValue.Str).value) }
    }
}

/**
 * 浮点字段。Flow 侧是 Float（如背景透明度），Schema 侧用 Double——ConfigSchema 没有
 * Float 类型，且 JSON 数值没有单双精度之分。
 */
class DataStoreDoubleField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val flow: Flow<Float>,
    private val setter: suspend (Float) -> Unit,
    private val minValue: Double? = null,
    private val maxValue: Double? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Double(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Double(runBlocking { flow.first() }.toDouble())

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        runBlocking { setter((value as ConfigValue.Double).value.toFloat()) }
    }
}
