package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.theme.CustomSkin
import com.aharou.core.theme.CustomSkinJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(
    name = "theme_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class AppThemeMode(val labelRes: Int) {
    AUTO(R.string.theme_auto),
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light);

    companion object {
        fun fromPersisted(value: String?): AppThemeMode? = entries.firstOrNull { it.name == value }
    }
}

/** 持久化 App 外观主题。默认跟随系统，也兼容旧版深色开关。 */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val DARK_THEME_KEY = booleanPreferencesKey("dark_theme_enabled")
        val THEME_PRESET_KEY = stringPreferencesKey("theme_preset_id")
        val THEME_SKIN_KEY = stringPreferencesKey("theme_skin_id")
        val CUSTOM_SKINS_KEY = stringPreferencesKey("theme_custom_skins")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    }

    val themeModeFlow: Flow<AppThemeMode> = context.themeDataStore.data.map { prefs ->
        AppThemeMode.fromPersisted(prefs[THEME_MODE_KEY])
            ?: prefs[DARK_THEME_KEY]?.let { if (it) AppThemeMode.DARK else AppThemeMode.LIGHT }
            ?: AppThemeMode.AUTO
    }

    /**
     * 皮肤 id，对应 `AppThemePreset.id`；未设置时为 null，由调用方回退默认。
     *
     * 读取时优先新键、回退旧的配色预设键：升级前选过配色（default / coffee / graphite / forest）
     * 的用户由 `AppSkins` 按色相平滑接到新皮肤，不会突然掉回默认。
     */
    val themePresetIdFlow: Flow<String?> = context.themeDataStore.data.map {
        it[THEME_SKIN_KEY] ?: it[THEME_PRESET_KEY]
    }

    /** 是否启用系统莫奈取色。开启后配色主题的选择不再生效。 */
    val dynamicColorFlow: Flow<Boolean> =
        context.themeDataStore.data.map { it[DYNAMIC_COLOR_KEY] == true }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.themeDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setThemePresetId(id: String) {
        context.themeDataStore.edit {
            it[THEME_SKIN_KEY] = id
            it.remove(THEME_PRESET_KEY)
        }
    }

    /** 用户自己调出来的皮肤；JSON 损坏时按空列表处理，不让取色页崩掉。 */
    val customSkinsFlow: Flow<List<CustomSkin>> = context.themeDataStore.data.map { prefs ->
        prefs[CUSTOM_SKINS_KEY]?.let { raw ->
            runCatching { CustomSkinJson.decodeFromString<List<CustomSkin>>(raw) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** 新建或覆盖一条自定义皮肤，并立即选中它。 */
    suspend fun upsertCustomSkin(skin: CustomSkin) {
        val existing = customSkinsFlow.first()
        val updated = existing.filterNot { it.id == skin.id } + skin
        context.themeDataStore.edit {
            it[CUSTOM_SKINS_KEY] = CustomSkinJson.encodeToString(updated)
            it[THEME_SKIN_KEY] = skin.id
            it.remove(THEME_PRESET_KEY)
        }
    }

    /** 删除一条自定义皮肤；若正被选中则回退默认皮肤。 */
    suspend fun deleteCustomSkin(id: String) {
        val remaining = customSkinsFlow.first().filterNot { it.id == id }
        context.themeDataStore.edit { prefs ->
            prefs[CUSTOM_SKINS_KEY] = CustomSkinJson.encodeToString(remaining)
            if (prefs[THEME_SKIN_KEY] == id) prefs.remove(THEME_SKIN_KEY)
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled }
    }

    /** 备份快照：返回当前持久化的主题模式名（未设置时为 null，导入时回退默认）。 */
    suspend fun snapshot(): String? = themeModeFlow.first().name

    /** 从备份还原主题模式；null 时清除键回退默认。 */
    suspend fun restore(value: String?) {
        context.themeDataStore.edit {
            if (value == null) it.remove(THEME_MODE_KEY) else it[THEME_MODE_KEY] = value
        }
    }

    /** 备份快照：当前配色主题 id。 */
    suspend fun presetSnapshot(): String? = themePresetIdFlow.first()

    /** 备份快照：莫奈取色开关。 */
    suspend fun dynamicColorSnapshot(): Boolean = dynamicColorFlow.first()

    /** 从备份还原配色主题与莫奈开关；id 为 null 时清除键回退默认。 */
    suspend fun restoreColors(presetId: String?, dynamicColor: Boolean) {
        context.themeDataStore.edit {
            if (presetId == null) {
                it.remove(THEME_SKIN_KEY)
                it.remove(THEME_PRESET_KEY)
            } else {
                it[THEME_SKIN_KEY] = presetId
                it.remove(THEME_PRESET_KEY)
            }
            it[DYNAMIC_COLOR_KEY] = dynamicColor
        }
    }
}
