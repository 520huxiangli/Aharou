package com.aharou.feature.im.domain

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.imOwnerDataStore by preferencesDataStore(
    name = "im_owner_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 机器人接入的「所有者聊天」绑定。
 *
 * 为什么需要：机器人对谁开放等于把整台设备的 agent 能力（含工具调用）交给对方，
 * 光靠 `enabled` 开关和 token 保密不够——token 只有本机持有，但任何能给这个 bot 发消息的人
 * 都能触发一轮 agent。这里按渠道记一份已绑定的 chatId 集合，其余聊天一律不处理。
 *
 * 绑定策略是最简版：某个渠道还没有任何所有者时，第一条进来的消息把所在聊天绑成所有者
 * （即「谁先配好机器人谁就是主人」），之后只认这个集合。要换人只能先清空绑定。
 *
 * 存独立的 DataStore 而不是复用各渠道的配置仓：渠道仓在 `data/repository/` 下按平台拆，
 * 所有者集合是四个平台共用的同一套语义，放在这里才不会每加一个平台就重抄一遍。
 */
@Singleton
class ImOwnerStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** 某渠道已绑定的所有者聊天（设置界面可观察）。 */
    fun ownersFlow(channelId: String): Flow<Set<String>> =
        context.imOwnerDataStore.data.map { prefs -> prefs[keyFor(channelId)].orEmpty() }

    suspend fun owners(channelId: String): Set<String> = ownersFlow(channelId).first()

    suspend fun isOwner(channelId: String, chatId: String): Boolean =
        chatId.isNotBlank() && chatId in owners(channelId)

    /**
     * 仅当该渠道还没有所有者时把 [chatId] 绑上，返回是否由本次调用完成绑定。
     *
     * 判定与写入必须在同一次 `edit` 里：两个聊天同时到达时，DataStore 的编辑是串行的，
     * 后到的那次读到非空集合就放弃，不会把两个陌生人都绑成所有者。
     */
    suspend fun bindIfEmpty(channelId: String, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        var bound = false
        context.imOwnerDataStore.edit { prefs ->
            val key = keyFor(channelId)
            if (prefs[key].isNullOrEmpty()) {
                prefs[key] = setOf(chatId)
                bound = true
            }
        }
        if (bound) FileLogger.i(TAG, "[$channelId] 已绑定所有者 chat=$chatId")
        return bound
    }

    /** 解开该渠道的全部绑定：下一个进来的聊天会重新成为所有者。 */
    suspend fun clearOwners(channelId: String) {
        context.imOwnerDataStore.edit { prefs -> prefs.remove(keyFor(channelId)) }
        FileLogger.i(TAG, "[$channelId] 已清除所有者绑定")
    }

    private companion object {
        const val TAG = "ImOwnerStore"

        fun keyFor(channelId: String) = stringSetPreferencesKey("owner_chats_$channelId")
    }
}
