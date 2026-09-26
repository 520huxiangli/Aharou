package com.aharou.feature.editor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.editorSettingsDataStore by preferencesDataStore(
    name = "editor_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

const val MIN_EDITOR_FONT_SIZE_SP = 10
const val MAX_EDITOR_FONT_SIZE_SP = 28
const val DEFAULT_EDITOR_FONT_SIZE_SP = 14

/** 编辑器可持久化的显示设置。 */
data class EditorSettings(
    val fontSizeSp: Int = DEFAULT_EDITOR_FONT_SIZE_SP,
    val wordWrap: Boolean = false,
    val showIndentGuide: Boolean = true,
    val showWrapArrow: Boolean = false,
    val showWhitespace: Boolean = false
)

/** 持久化编辑器的字体大小与自动换行偏好，跨会话保留。 */
@Singleton
class EditorSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val FONT_SIZE_KEY = intPreferencesKey("font_size_sp")
        val WORD_WRAP_KEY = booleanPreferencesKey("word_wrap")
        val SHOW_INDENT_GUIDE_KEY = booleanPreferencesKey("show_indent_guide")
        val SHOW_WRAP_ARROW_KEY = booleanPreferencesKey("show_wrap_arrow")
        val SHOW_WHITESPACE_KEY = booleanPreferencesKey("show_whitespace")
    }

    val settingsFlow: Flow<EditorSettings> = context.editorSettingsDataStore.data.map { prefs ->
        EditorSettings(
            fontSizeSp = prefs[FONT_SIZE_KEY] ?: DEFAULT_EDITOR_FONT_SIZE_SP,
            wordWrap = prefs[WORD_WRAP_KEY] ?: false,
            showIndentGuide = prefs[SHOW_INDENT_GUIDE_KEY] ?: true,
            showWrapArrow = prefs[SHOW_WRAP_ARROW_KEY] ?: false,
            showWhitespace = prefs[SHOW_WHITESPACE_KEY] ?: false
        )
    }

    suspend fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(MIN_EDITOR_FONT_SIZE_SP, MAX_EDITOR_FONT_SIZE_SP)
        context.editorSettingsDataStore.edit { it[FONT_SIZE_KEY] = clamped }
    }

    suspend fun setWordWrap(enabled: Boolean) {
        context.editorSettingsDataStore.edit { it[WORD_WRAP_KEY] = enabled }
    }

    suspend fun setShowIndentGuide(enabled: Boolean) {
        context.editorSettingsDataStore.edit { it[SHOW_INDENT_GUIDE_KEY] = enabled }
    }

    suspend fun setShowWrapArrow(enabled: Boolean) {
        context.editorSettingsDataStore.edit { it[SHOW_WRAP_ARROW_KEY] = enabled }
    }

    suspend fun setShowWhitespace(enabled: Boolean) {
        context.editorSettingsDataStore.edit { it[SHOW_WHITESPACE_KEY] = enabled }
    }
}
