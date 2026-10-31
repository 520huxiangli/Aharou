package com.aharou.feature.agent.domain.subagent

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 并发上限的读取口。
 *
 * [SubAgentEventBus] 只依赖它，不直接依赖带 Android Context 的 [SubAgentSettings]：
 * 事件总线的单元测试可以传一个固定值的 lambda，不必为此把纯逻辑测试拖进 Robolectric。
 */
fun interface MaxRunningSource {
    fun maxRunning(): Int
}

/**
 * 子代理通信总开关的读取口。
 *
 * 与 [MaxRunningSource] 同理：[SubAgentEventBus] 只依赖它，不直接依赖带 Android Context 的
 * [SubAgentSettings]，纯逻辑测试可以传一个固定值。
 */
fun interface CommunicationEnabledSource {
    fun communicationEnabled(): Boolean
}

/**
 * 子代理的运行设置：同时运行的子代理上限。
 *
 * 这个名额是**整个应用共享**的（不按父会话分组，嵌套派发也计入），所以存在单例里。
 * 存 SharedPreferences 而不是记忆目录：这是本机的运行开关，写进记忆目录会被同步到别的设备。
 */
@Singleton
class SubAgentSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) : MaxRunningSource, CommunicationEnabledSource {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _maxRunning = MutableStateFlow(readMaxRunning())

    private val _communicationEnabled = MutableStateFlow(readCommunicationEnabled())

    /**
     * 子代理间通信（team / tell）的总开关。
     *
     * 关掉后 task 工具的 team / tell 一律拒绝；主代理对子代理的 send / read / stop 不受影响
     * （那是上下级通道，不是子代理之间的对等通信）。
     */
    val communicationEnabledFlow: StateFlow<Boolean> = _communicationEnabled.asStateFlow()

    /** 上限的变化流：设置页与配置通道改完都能立刻看到新值。 */
    val maxRunningFlow: StateFlow<Int> = _maxRunning.asStateFlow()

    /** 当前上限，供 [SubAgentEventBus] 判定「是否已满」。 */
    override fun maxRunning(): Int = _maxRunning.value

    /** 改上限；超出 [MIN_RUNNING]..[MAX_RUNNING] 的值会被收敛，读到的一定是合法值。 */
    fun setMaxRunning(value: Int) {
        val clamped = value.coerceIn(MIN_RUNNING, MAX_RUNNING)
        prefs.edit().putInt(KEY_MAX_RUNNING, clamped).apply()
        _maxRunning.value = clamped
    }

    private fun readMaxRunning(): Int =
        prefs.getInt(KEY_MAX_RUNNING, SubAgentEventBus.DEFAULT_MAX_RUNNING)
            .coerceIn(MIN_RUNNING, MAX_RUNNING)

    /** 子代理之间的对等通信是否开启；默认开（与上游行为一致，用户不主动关就不变）。 */
    override fun communicationEnabled(): Boolean = _communicationEnabled.value

    /** 改通信总开关；设置页与配置通道共用这一份单例，改完两边都会立刻看到。 */
    fun setCommunicationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMMUNICATION_ENABLED, enabled).apply()
        _communicationEnabled.value = enabled
    }

    private fun readCommunicationEnabled(): Boolean =
        prefs.getBoolean(KEY_COMMUNICATION_ENABLED, true)

    companion object {
        const val MIN_RUNNING = 1
        const val MAX_RUNNING = 15

        private const val PREFS = "aharou_subagent_prefs"
        private const val KEY_MAX_RUNNING = "subagent_max_running"
        private const val KEY_COMMUNICATION_ENABLED = "subagent_communication_enabled"
    }
}

/** 把 [SubAgentSettings] 绑到事件总线依赖的 [MaxRunningSource]。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SubAgentSettingsModule {

    @Binds
    abstract fun bindMaxRunningSource(settings: SubAgentSettings): MaxRunningSource

    @Binds
    abstract fun bindCommunicationEnabledSource(settings: SubAgentSettings): CommunicationEnabledSource
}
