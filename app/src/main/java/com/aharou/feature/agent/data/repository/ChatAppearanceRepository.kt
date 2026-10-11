package com.aharou.feature.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatAppearanceDataStore by preferencesDataStore(
    name = "chat_appearance_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 聊天气泡外观。
 *
 * 默认全部「跟随皮肤」：换皮肤时聊天区自动跟着变，不配置也不会出现两套观感。
 * 助手气泡默认关闭——对话区是扁平的文档流（助手正文直接铺在页面底色上），
 * 打开才给助手也套一层药丸。
 */
data class ChatAppearance(
    val userBubbleFollowsSkin: Boolean = true,
    val userBubbleColor: Long? = null,
    val userBubbleAlpha: Float = 1f,
    val assistantBubbleEnabled: Boolean = false,
    val assistantBubbleFollowsSkin: Boolean = true,
    val assistantBubbleColor: Long? = null,
    val assistantBubbleAlpha: Float = 1f,
    val bubbleCornerDp: Int = 14
)

@Singleton
class ChatAppearanceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val USER_FOLLOWS = booleanPreferencesKey("bubble_user_follows_skin")
        val USER_COLOR = longPreferencesKey("bubble_user_color")
        val USER_ALPHA = floatPreferencesKey("bubble_user_alpha")
        val ASSISTANT_ENABLED = booleanPreferencesKey("bubble_assistant_enabled")
        val ASSISTANT_FOLLOWS = booleanPreferencesKey("bubble_assistant_follows_skin")
        val ASSISTANT_COLOR = longPreferencesKey("bubble_assistant_color")
        val ASSISTANT_ALPHA = floatPreferencesKey("bubble_assistant_alpha")
        val CORNER = intPreferencesKey("bubble_corner_dp")

        /** 颜色不可能是 0 或负值，用它表示「没选过颜色，跟随皮肤」。 */
        const val NO_COLOR = -1L
    }

    val settingsFlow: Flow<ChatAppearance> = context.chatAppearanceDataStore.data.map { prefs ->
        ChatAppearance(
            userBubbleFollowsSkin = prefs[USER_FOLLOWS] ?: true,
            userBubbleColor = prefs[USER_COLOR]?.takeIf { it != NO_COLOR },
            userBubbleAlpha = prefs[USER_ALPHA] ?: 1f,
            assistantBubbleEnabled = prefs[ASSISTANT_ENABLED] ?: false,
            assistantBubbleFollowsSkin = prefs[ASSISTANT_FOLLOWS] ?: true,
            assistantBubbleColor = prefs[ASSISTANT_COLOR]?.takeIf { it != NO_COLOR },
            assistantBubbleAlpha = prefs[ASSISTANT_ALPHA] ?: 1f,
            bubbleCornerDp = prefs[CORNER] ?: 14
        )
    }

    /** 一次性写入整份设置：气泡项之间有联动，逐字段 setter 容易写出中间态。 */
    suspend fun update(transform: (ChatAppearance) -> ChatAppearance) {
        val next = transform(settingsFlow.first())
        context.chatAppearanceDataStore.edit { prefs ->
            prefs[USER_FOLLOWS] = next.userBubbleFollowsSkin
            prefs[USER_COLOR] = next.userBubbleColor ?: NO_COLOR
            prefs[USER_ALPHA] = next.userBubbleAlpha
            prefs[ASSISTANT_ENABLED] = next.assistantBubbleEnabled
            prefs[ASSISTANT_FOLLOWS] = next.assistantBubbleFollowsSkin
            prefs[ASSISTANT_COLOR] = next.assistantBubbleColor ?: NO_COLOR
            prefs[ASSISTANT_ALPHA] = next.assistantBubbleAlpha
            prefs[CORNER] = next.bubbleCornerDp
        }
    }
}
