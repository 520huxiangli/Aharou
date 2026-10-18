package com.aharou.feature.im.domain.pairing

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.model.PendingPairing
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 存入 pairing.json 的一条已批准记录。 */
@Serializable
internal data class ApprovedEntry(
    val senderName: String = "",
    val chatId: String = "",
    val approvedAt: Long = 0L
)

/** 存入 pairing.json 的一条待审批记录。 */
@Serializable
internal data class PendingEntry(
    val senderId: String,
    val senderName: String = "",
    val chatId: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L
)

/** 单个渠道的白名单与待审批码。 */
@Serializable
internal data class ChannelPairing(
    val approved: Map<String, ApprovedEntry> = emptyMap(),
    val pending: Map<String, PendingEntry> = emptyMap()
)

@Serializable
internal data class PairingFile(
    val channels: Map<String, ChannelPairing> = emptyMap(),
    /** 旧版扁平表：只用于读取时迁移，迁移后写回空表。 */
    val approved: Map<String, ApprovedEntry> = emptyMap(),
    val pending: Map<String, PendingEntry> = emptyMap()
)

/**
 * 配对码与白名单存储，按渠道隔离。
 *
 * 落在 `filesDir/im/telegram/pairing.json`（App 私有目录），**绝不放工作区**：agent 的文件工具
 * 能写工作区，而「谁能和机器人说话」不该由 agent 决定。路径里的 `telegram` 是历史命名，
 * 改掉等于让老用户的配对记录凭空消失，故保持不变。
 *
 * 每个渠道各有一份白名单与待审批表（同一文件里的 `channels` 分组）：否则四个设置页会看到同一批
 * 记录，QQ 页甚至能批准一条微信的待配对码。
 *
 * 落盘防丢：写前先把当前主文件复制成 `pairing.json.bak`（只在主文件还能解析时复制，避免拿坏文件
 * 盖掉好备份），主文件走临时文件 + rename；读失败先回退备份。旧实现读失败静默返回空表，
 * 随后任何一次写入都会把白名单覆盖成空。
 */
@Singleton
class ImPairingStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    private val file: File
        get() = File(File(context.filesDir, "im/telegram"), "pairing.json").apply {
            parentFile?.mkdirs()
        }

    private val backupFile: File
        get() = File(file.parentFile, "pairing.json.bak")

    /** 某渠道的已批准名单（按通过时间倒序）。 */
    suspend fun approved(channel: String): List<PairedChat> = mutex.withLock {
        read().forChannel(channel).approved.map { (senderId, entry) ->
            PairedChat(
                senderId = senderId,
                senderName = entry.senderName,
                chatId = entry.chatId.ifBlank { senderId },
                approvedAt = entry.approvedAt
            )
        }.sortedByDescending { it.approvedAt }
    }

    suspend fun isApproved(channel: String, senderId: String): Boolean = mutex.withLock {
        read().forChannel(channel).approved.containsKey(senderId)
    }

    /** 某渠道的待审批列表（过期项已在读取时清理）。 */
    suspend fun pending(channel: String): List<PendingPairing> = mutex.withLock {
        read().forChannel(channel).pending.map { (code, entry) ->
            PendingPairing(
                code = code,
                senderId = entry.senderId,
                senderName = entry.senderName,
                chatId = entry.chatId.ifBlank { entry.senderId },
                createdAt = entry.createdAt,
                expiresAt = entry.expiresAt
            )
        }.sortedBy { it.createdAt }
    }

    /**
     * 为某人发放配对码：同渠道同一个人上一枚码仍有效时复用同一枚，避免刷屏。
     * 码在所有渠道间唯一，用户报码时不必说自己是哪个渠道。
     * 返回 6 位码（字符集去掉易混的 0/O/1/I）。
     */
    suspend fun request(
        channel: String,
        senderId: String,
        senderName: String,
        chatId: String
    ): String = mutex.withLock {
        val data = read()
        val current = data.forChannel(channel)
        // read() 已清掉过期项，这里能命中的一定还在有效期内
        current.pending.entries.firstOrNull { it.value.senderId == senderId }?.let { return it.key }
        var code = newCode()
        while (data.hasCode(code)) code = newCode()
        val now = System.currentTimeMillis()
        val updated = current.copy(
            pending = current.pending + (code to PendingEntry(
                senderId = senderId,
                senderName = senderName,
                chatId = chatId,
                createdAt = now,
                expiresAt = now + PAIRING_TTL_MS
            ))
        )
        write(data.withChannel(channel, updated))
        code
    }

    /** 在指定渠道通过配对码把人加进白名单；码不在该渠道或已过期返回 null。 */
    suspend fun approve(channel: String, code: String): PairedChat? = mutex.withLock {
        val data = read()
        val current = data.forChannel(channel)
        val key = code.trim().uppercase()
        val info = current.pending[key] ?: return null
        val entry = ApprovedEntry(
            senderName = info.senderName,
            chatId = info.chatId.ifBlank { info.senderId },
            approvedAt = System.currentTimeMillis()
        )
        val updated = current.copy(
            approved = current.approved + (info.senderId to entry),
            pending = current.pending - key
        )
        write(data.withChannel(channel, updated))
        PairedChat(
            senderId = info.senderId,
            senderName = entry.senderName,
            chatId = entry.chatId,
            approvedAt = entry.approvedAt
        )
    }

    /** 在指定渠道拒绝一枚配对码；返回对应 senderId，未找到返回 null。 */
    suspend fun deny(channel: String, code: String): String? = mutex.withLock {
        val data = read()
        val current = data.forChannel(channel)
        val key = code.trim().uppercase()
        val info = current.pending[key] ?: return null
        write(data.withChannel(channel, current.copy(pending = current.pending - key)))
        info.senderId
    }

    /** 移除某渠道已批准的人；下次来消息会重新发码。 */
    suspend fun remove(channel: String, senderId: String): Boolean = mutex.withLock {
        val data = read()
        val current = data.forChannel(channel)
        if (!current.approved.containsKey(senderId)) return false
        write(data.withChannel(channel, current.copy(approved = current.approved - senderId)))
        true
    }

    /** 已批准者改名或换私聊时刷新记录。 */
    suspend fun touch(channel: String, senderId: String, senderName: String, chatId: String) =
        mutex.withLock {
            val data = read()
            val current = data.forChannel(channel)
            val entry = current.approved[senderId] ?: return
            val name = senderName.ifBlank { entry.senderName }
            val chat = chatId.ifBlank { entry.chatId }
            if (name == entry.senderName && chat == entry.chatId) return
            val updated = current.copy(
                approved = current.approved + (senderId to entry.copy(senderName = name, chatId = chat))
            )
            write(data.withChannel(channel, updated))
        }

    /** 一次读盘的结果；[recovered] 表示主文件坏了、数据来自备份，需要立刻回写修复主文件。 */
    private class ReadResult(val data: PairingFile, val recovered: Boolean = false)

    /**
     * 读盘：解析 → 迁移旧结构 → 清过期码，三者任一产生变化时立刻写回。
     * 迁移不落盘的话，下一次别的方法写入会带着旧字段一起写回，等于每次读都要重迁。
     */
    private suspend fun read(): PairingFile = withContext(Dispatchers.IO) {
        val loaded = loadFromDisk()
        val migrated = migrateLegacy(loaded.data)
        val pruned = pruneExpired(migrated, System.currentTimeMillis())
        if (loaded.recovered || pruned !== loaded.data) writeSync(pruned)
        pruned
    }

    private fun loadFromDisk(): ReadResult {
        if (!file.exists()) return ReadResult(PairingFile())
        decode(file)?.let { return ReadResult(it) }
        if (!backupFile.exists()) {
            FileLogger.w(TAG, "主配对文件无法解析且没有备份，按空表处理")
            return ReadResult(PairingFile())
        }
        val restored = decode(backupFile)
        if (restored == null) {
            FileLogger.w(TAG, "主配对文件与配对备份都无法解析，按空表处理")
            return ReadResult(PairingFile())
        }
        FileLogger.w(
            TAG,
            "主配对文件无法解析，已回退备份（${restored.channels.size} 个渠道）"
        )
        return ReadResult(restored, recovered = true)
    }

    private fun decode(source: File): PairingFile? = runCatching {
        json.decodeFromString(PairingFile.serializer(), source.readText())
    }.onFailure { FileLogger.w(TAG, "解析 ${source.name} 失败", it) }.getOrNull()

    /**
     * 旧版顶层扁平表按 chatId 形状归渠道：`c2c:`/`group:` 是 QQ，`@im.wechat` 后缀是微信，
     * `oc_`/`ou_`/`on_` 开头是飞书，其余纯数字/负数归 Telegram。
     * 不用 senderId 兜底之外的猜测——归错渠道比留在旧结构里更糟，只能靠形状这种确定证据。
     */
    private fun migrateLegacy(data: PairingFile): PairingFile {
        if (data.approved.isEmpty() && data.pending.isEmpty()) return data
        val channels = data.channels.toMutableMap()
        data.approved.forEach { (senderId, entry) ->
            val channel = channelOfLegacy(entry.chatId, senderId)
            val existing = channels[channel] ?: ChannelPairing()
            if (!existing.approved.containsKey(senderId)) {
                channels[channel] = existing.copy(approved = existing.approved + (senderId to entry))
            }
        }
        data.pending.forEach { (code, entry) ->
            val channel = channelOfLegacy(entry.chatId, entry.senderId)
            val existing = channels[channel] ?: ChannelPairing()
            if (!existing.pending.containsKey(code)) {
                channels[channel] = existing.copy(pending = existing.pending + (code to entry))
            }
        }
        FileLogger.i(
            TAG,
            "迁移旧版扁平配对表：${data.approved.size} 条已批准 / ${data.pending.size} 条待审批已按渠道归位"
        )
        return data.copy(channels = channels, approved = emptyMap(), pending = emptyMap())
    }

    private fun channelOfLegacy(chatId: String, senderId: String): String {
        listOf(chatId, senderId).filter { it.isNotBlank() }.forEach { id ->
            channelOfShape(id)?.let { return it }
        }
        return "telegram"
    }

    private fun channelOfShape(id: String): String? = when {
        id.startsWith("c2c:") || id.startsWith("group:") -> "qq"
        id.endsWith("@im.wechat") -> "weixin"
        id.startsWith("oc_") || id.startsWith("ou_") || id.startsWith("on_") -> "feishu"
        else -> null
    }

    /** 无变化时返回原对象，避免 read() 每次都判定「需要写回」。 */
    private fun pruneExpired(data: PairingFile, now: Long): PairingFile {
        val hasExpired = data.channels.values.any { channel ->
            channel.pending.values.any { it.expiresAt <= now }
        }
        if (!hasExpired) return data
        return data.copy(
            channels = data.channels.mapValues { (_, channel) ->
                channel.copy(pending = channel.pending.filterValues { it.expiresAt > now })
            }
        )
    }

    private fun PairingFile.forChannel(channel: String): ChannelPairing =
        channels[channel] ?: ChannelPairing()

    private fun PairingFile.withChannel(channel: String, data: ChannelPairing): PairingFile =
        copy(channels = channels + (channel to data))

    /** 码要跨渠道唯一，否则用户在错误的设置页输入也能命中别人的记录。 */
    private fun PairingFile.hasCode(code: String): Boolean =
        pending.containsKey(code) || channels.values.any { it.pending.containsKey(code) }

    /** 有变化才落盘（见 [read]），写入本身走 [write]。 */
    private suspend fun write(data: PairingFile) = withContext(Dispatchers.IO) { writeSync(data) }

    private fun writeSync(data: PairingFile) {
        runCatching {
            backupCurrent()
            val tmp = File(file.parentFile, "pairing.json.tmp")
            tmp.writeText(json.encodeToString(PairingFile.serializer(), data))
            if (!tmp.renameTo(file)) {
                // rename 在个别文件系统上会失败，退化成覆盖写，别把新内容留在 tmp
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "写入配对文件失败", it) }
    }

    /** 主文件解析不了时不动备份，否则会把唯一一份好数据盖掉。 */
    private fun backupCurrent() {
        if (!file.exists()) return
        val readable = runCatching {
            json.decodeFromString(PairingFile.serializer(), file.readText())
        }.isSuccess
        if (!readable) {
            FileLogger.w(TAG, "当前主配对文件无法解析，保留原备份不覆盖")
            return
        }
        runCatching { file.copyTo(backupFile, overwrite = true) }
            .onFailure { FileLogger.w(TAG, "备份配对文件失败", it) }
    }

    private fun newCode(): String =
        buildString { repeat(CODE_LENGTH) { append(CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.length)]) } }

    companion object {
        private const val TAG = "ImPairingStore"
        /** 码有效 10 分钟。 */
        private const val PAIRING_TTL_MS = 10 * 60 * 1000L
        private const val CODE_LENGTH = 6
        /** 去掉易混的 0/O/1/I：码可能被念出来或照屏幕输入。 */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
