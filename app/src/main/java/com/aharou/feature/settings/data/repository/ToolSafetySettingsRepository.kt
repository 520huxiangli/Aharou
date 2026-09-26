package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(name = "tool_safety_prefs")

/**
 * 持久化「禁用安全拦截」开关，默认关闭。
 *
 * 开启后，AUTO（自动）模式下连灾难性 `rm`（删除根目录、系统关键目录、工作区根目录等）
 * 也不再拦截，命令一律放行，`Shizuku` 工具也不再逐次弹窗。仅作用于 AUTO 模式：
 * BUILD / PLAN 模式的安全拦截不受影响，Shizuku 在 BUILD 下的「不可记忆」也不受影响。
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
    }

    /** 当前开关流；未设置时回退到 false（默认保留安全拦截）。 */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** 读取一次当前值（权限评估时用）。 */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()
}