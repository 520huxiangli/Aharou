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
 * 子代理的运行设置：同时运行的子代理上限。
 *
 * 这个名额是**整个应用共享**的（不按父会话分组，嵌套派发也计入），所以存在单例里。
 * 存 SharedPreferences 而不是记忆目录：这是本机的运行开关，写进记忆目录会被同步到别的设备。
 */
@Singleton
class SubAgentSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) : MaxRunningSource {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _maxRunning = MutableStateFlow(readMaxRunning())

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

    companion object {
        const val MIN_RUNNING = 1
        const val MAX_RUNNING = 10

        private const val PREFS = "aharou_subagent_prefs"
        private const val KEY_MAX_RUNNING = "subagent_max_running"
    }
}

/** 把 [SubAgentSettings] 绑到事件总线依赖的 [MaxRunningSource]。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SubAgentSettingsModule {

    @Binds
    abstract fun bindMaxRunningSource(settings: SubAgentSettings): MaxRunningSource
}
