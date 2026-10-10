package com.aharou.core.identity

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户自己的聊天身份：[name] 为展示名，[icon] 为头像。
 *
 * [icon] 与 [com.aharou.core.soul.SoulMetadata.icon] 同一套格式 —— 短 emoji 字面量或
 * `data:image/png;base64,…`，所以聊天里两条身份行能用同一个渲染组件画出来。空串表示用默认头像。
 */
data class UserIdentity(
    val name: String = "",
    val icon: String = ""
)

private val Context.userIdentityDataStore by preferencesDataStore(
    name = "user_identity",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 用户身份的本地存储，与 [com.aharou.core.soul.SoulStore] 对称：
 * 助手那条身份行读 SOUL.md，用户这条读这里。
 *
 * 独立于账号体系 —— 未登录也该有自己的头像，登录与否都不影响这一行画不画得出来。
 */
object UserIdentityStore {

    private val NAME_KEY = stringPreferencesKey("display_name")
    private val ICON_KEY = stringPreferencesKey("icon")

    /** 观察用户身份；每次改动都会推给聊天页的身份行，无需重启。 */
    fun observe(context: Context): Flow<UserIdentity> = context.userIdentityDataStore.data.map { prefs ->
        UserIdentity(
            name = prefs[NAME_KEY].orEmpty(),
            icon = prefs[ICON_KEY].orEmpty()
        )
    }

    suspend fun setName(context: Context, name: String) {
        context.userIdentityDataStore.edit { it[NAME_KEY] = name.trim() }
    }

    suspend fun setIcon(context: Context, icon: String) {
        context.userIdentityDataStore.edit { it[ICON_KEY] = icon }
    }
}
