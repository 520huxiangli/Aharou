package com.aharou.feature.im.domain

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.imDeliveryTargetDataStore by preferencesDataStore(
    name = "im_delivery_targets",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 一条「主动投递目标」：把平台路由（渠道 + chatId）固定在一个不会变的本地别名下。
 *
 * 为什么不直接让模型/定时任务记 chatId：chatId 是平台发的（Telegram 是数字 id、飞书是 `oc_` 开头的串、
 * NapCat 还要带账号前缀），换账号或换群就会变，而定时任务的提示词是长期存在的——
 * 里面写死的 chatId 一变，消息就静默发到错的人那里。别名由本地生成，换路由只改记录。
 */
@Serializable
data class ImDeliveryTarget(
    /** 本地别名，`tgt-` + 16 位十六进制；创建后不可改，只能改 [displayName]。 */
    val alias: String,
    val channelId: String,
    val chatId: String,
    /** 展示名，可改；空串表示没起名字（展示时回退 chatId）。 */
    val displayName: String = "",
    /** 创建时间（毫秒），仅用于稳定排序。 */
    val createdAt: Long = 0L,
)

/**
 * 「别名 → 平台路由」的落盘登记表，供后台任务/定时任务主动投递用。
 *
 * 与 [ImOwnerStore] 分开存放：所有者绑定回答的是「谁能指挥这个机器人」，
 * 投递目标回答的是「消息往哪儿发」，两者的写入方与生命周期都不一样（前者由入站消息写，
 * 后者由 agent 或用户在配置里登记）。
 *
 * 整张表以一条 JSON 存在 DataStore 里而不是一个别名一条键：条目数量级很小（个位数到几十条），
 * 一次读全表才能同时完成「按别名查」「按 channel+chatId 去重」两件事，也省掉键名拼接规则。
 */
@Singleton
class ImDeliveryTargetStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** 全量目标（设置界面可观察；按创建时间升序，别名顺序稳定）。 */
    fun targetsFlow(): Flow<List<ImDeliveryTarget>> =
        context.imDeliveryTargetDataStore.data.map { decode(it[KEY]) }

    suspend fun targets(): List<ImDeliveryTarget> = targetsFlow().first()

    /** 按别名查；不区分大小写——模型偶尔会把十六进制写成大写。 */
    suspend fun find(alias: String): ImDeliveryTarget? {
        val key = alias.trim()
        if (key.isEmpty()) return null
        return targets().firstOrNull { it.alias.equals(key, ignoreCase = true) }
    }

    /** 按 `<channelId>|<chatId>` 查已有别名；用于「同一聊天重复登记返回原别名」。 */
    suspend fun findByRoute(channelId: String, chatId: String): ImDeliveryTarget? {
        val channel = channelId.trim()
        val chat = chatId.trim()
        if (channel.isEmpty() || chat.isEmpty()) return null
        return targets().firstOrNull { it.channelId == channel && it.chatId == chat }
    }

    /**
     * 登记一条目标；同一 channel+chatId 已存在时原样返回已有记录，不新建。
     *
     * 查重与写入必须在同一次 `edit` 里：并发的两次登记同时到达时，DataStore 的编辑是串行的，
     * 后到的那次读到已有记录就放弃，不会给同一个聊天生成两个别名。
     */
    suspend fun register(channelId: String, chatId: String, displayName: String = ""): ImDeliveryTarget {
        val channel = channelId.trim()
        val chat = chatId.trim()
        require(channel.isNotEmpty() && chat.isNotEmpty()) { "channelId 与 chatId 不能为空" }

        var target: ImDeliveryTarget? = null
        var createdNow = false
        context.imDeliveryTargetDataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val existing = current.firstOrNull { it.channelId == channel && it.chatId == chat }
            if (existing != null) {
                target = existing
                return@edit
            }
            val taken = current.mapTo(HashSet()) { it.alias }
            var alias = newAlias()
            while (alias in taken) alias = newAlias()
            val created = ImDeliveryTarget(
                alias = alias,
                channelId = channel,
                chatId = chat,
                displayName = displayName.trim(),
                createdAt = System.currentTimeMillis(),
            )
            prefs[KEY] = encode(current + created)
            target = created
            createdNow = true
        }

        val resolved = checkNotNull(target)
        if (createdNow) FileLogger.i(TAG, "登记投递目标 ${resolved.alias} → $channel:$chat")
        return resolved
    }

    /** 改展示名；别名不动。返回是否命中。 */
    suspend fun rename(alias: String, displayName: String): Boolean {
        val key = alias.trim()
        if (key.isEmpty()) return false
        var renamed = false
        context.imDeliveryTargetDataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            if (current.none { it.alias.equals(key, ignoreCase = true) }) return@edit
            prefs[KEY] = encode(
                current.map {
                    if (it.alias.equals(key, ignoreCase = true)) it.copy(displayName = displayName.trim()) else it
                }
            )
            renamed = true
        }
        return renamed
    }

    private fun decode(raw: String?): List<ImDeliveryTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            // 存储损坏按空表处理：宁可让 agent 重新登记，也别让整条投递链路卡在解析异常上
            FileLogger.w(TAG, "投递目标解析失败，按空表处理：${it.message}")
            emptyList()
        }
    }

    private fun encode(list: List<ImDeliveryTarget>): String = json.encodeToString(serializer, list)

    private companion object {
        const val TAG = "ImDeliveryTargetStore"

        val KEY = stringPreferencesKey("delivery_targets")
        val serializer = ListSerializer(ImDeliveryTarget.serializer())
        val json = Json { ignoreUnknownKeys = true }

        /** 与 [com.aharou.feature.remotedevice.domain.RemoteDeviceSettings] 的安装标识同一种形态，便于肉眼识别。 */
        fun newAlias(): String = "tgt-" + UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
