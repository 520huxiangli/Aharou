package com.aharou.feature.agent.domain.tool.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「命令输出里隐藏环境变量值」开关的存储。
 *
 * 为什么不放 `ContainerSettingsRepository` 的 DataStore：本开关在**每条命令输出**上都要读一次，
 * DataStore 的 Flow 读要么挂协程、要么 runBlocking；SharedPreferences 在构造时读一次进内存，
 * 之后是纯内存读，热路径上不多一次调度。
 *
 * 默认开启：关掉只会把凭据漏给模型，不会让任何功能更好用。
 */
@Singleton
class ContainerEnvRedactionSetting @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private companion object {
        const val PREFS = "aharou_container_env_redaction"
        const val KEY_ENABLED = "enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** 供配置通道展示与写入。 */
    val enabledFlow: StateFlow<Boolean> = state.asStateFlow()

    /** 同步读，命令输出路径上直接用，不额外切协程。 */
    val enabled: Boolean get() = state.value

    fun setEnabled(value: Boolean) {
        if (state.value == value) return
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        state.value = value
    }
}
