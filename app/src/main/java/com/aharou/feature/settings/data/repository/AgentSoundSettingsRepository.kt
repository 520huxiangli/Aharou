package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentSoundDataStore by preferencesDataStore(
    name = "agent_sound_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「agent 完成提示音」开关。默认关闭——需用户在软件权限页手动开启。
 * 开启后，agent 每轮对话完成且 App 不在前台时播放系统通知铃声。DataStore 用法与
 * [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class AgentSoundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("agent_sound_enabled")
    }

    /** 当前持久化的开关流；未设置时回退到 false（默认关闭）。 */
    val enabledFlow: Flow<Boolean> = context.agentSoundDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 写入开关。 */
    suspend fun setEnabled(enabled: Boolean) {
        context.agentSoundDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** 读取一次当前值（agent 完成时判断是否响铃用）。 */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** 备份快照：当前提示音开关是否开启。 */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** 从备份还原提示音开关。 */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}