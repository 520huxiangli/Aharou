package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.languageDataStore by preferencesDataStore(
    name = "language_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

private const val PREFS_NAME = "language_prefs_sync"
private const val PREFS_KEY = "language_tag"

/**
 * 持久化用户选择的应用语言。
 *
 * 存储值为 BCP-47 language tag（如 "zh"、"en"），null 表示跟随系统。
 * 切换时写入同步 prefs，[com.aharou.MainActivity] 在 attachBaseContext 中读取并 recreate 生效。
 */
@Singleton
class LanguageSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val LANGUAGE_KEY = stringPreferencesKey("language_tag")
    }

    private val syncPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val languageFlow: Flow<String?> = context.languageDataStore.data.map { prefs ->
        prefs[LANGUAGE_KEY]
    }

    suspend fun setLanguage(tag: String?) {
        context.languageDataStore.edit {
            if (tag == null) it.remove(LANGUAGE_KEY) else it[LANGUAGE_KEY] = tag
        }
        syncPrefs.edit().apply {
            if (tag == null) remove(PREFS_KEY) else putString(PREFS_KEY, tag)
        }.apply()
    }

    suspend fun snapshot(): String? = languageFlow.first()

    /** 同步读取语言 tag，供 attachBaseContext 在 Activity 创建前调用（此时 Hilt 尚未注入）。 */
    fun getLanguageSync(): String? = syncPrefs.getString(PREFS_KEY, null)

    suspend fun restore(value: String?) {
        context.languageDataStore.edit {
            if (value == null) it.remove(LANGUAGE_KEY) else it[LANGUAGE_KEY] = value
        }
    }
}
