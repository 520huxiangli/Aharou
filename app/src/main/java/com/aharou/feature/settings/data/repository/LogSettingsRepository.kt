package com.aharou.feature.settings.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.logDataStore by preferencesDataStore(
    name = "log_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「日志最低记录等级」。等级以枚举名（字符串）存取。
 *
 * 默认等级按构建类型区分：debug 构建（FLAG_DEBUGGABLE）为 [LogLevel.VERBOSE]（开发期全量记录），
 * release 构建为 [LogLevel.INFO]（正式版过滤调试噪音）。用户手动设置后始终以用户设置为准。
 *
 * 取值由 `AIEditorApp` 在启动与设置变更时 collect [levelFlow] 同步到 [com.aharou.core.util.FileLogger]。
 * DataStore 用法与 `WorkspaceRepository` 一致（构造注入即可，无需 DI module）。
 */
@Singleton
class LogSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val LEVEL_KEY = stringPreferencesKey("log_min_level")

    /** 默认等级：debug 全量记录，release 过滤调试噪音。 */
    private val defaultLevel: LogLevel =
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            LogLevel.VERBOSE
        } else {
            LogLevel.INFO
        }

    /** 当前持久化的日志等级流；解析失败/未设置时回退到默认等级。 */
    val levelFlow: Flow<LogLevel> = context.logDataStore.data.map { prefs ->
        prefs[LEVEL_KEY]?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: defaultLevel
    }

    /** 写入新的日志等级。 */
    suspend fun setLevel(level: LogLevel) {
        context.logDataStore.edit { it[LEVEL_KEY] = level.name }
    }

    /** 备份快照：当前日志等级名。 */
    suspend fun snapshot(): String = levelFlow.first().name

    /** 从备份还原日志等级；解析失败时忽略。 */
    suspend fun restore(value: String?) {
        val level = value?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: return
        setLevel(level)
    }
}
