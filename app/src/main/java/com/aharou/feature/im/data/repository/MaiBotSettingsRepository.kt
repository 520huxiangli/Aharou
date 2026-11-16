package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.maibotDataStore by preferencesDataStore(
    name = "maibot_mode",
    corruptionHandler = preferencesCorruptionHandler,
)

/** MaiBot 的工作模式：daily 日常（麦麦自己聊），work 工作（消息交给 Aharou，会话进软件）。 */
@Singleton
class MaiBotSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    enum class Mode { DAILY, WORK }

    val mode: Flow<Mode> = context.maibotDataStore.data.map { prefs ->
        if (prefs[KEY] == Mode.WORK.name) Mode.WORK else Mode.DAILY
    }

    suspend fun current(): Mode = mode.first()

    suspend fun setMode(mode: Mode) {
        context.maibotDataStore.edit { it[KEY] = mode.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("mode")
    }
}
