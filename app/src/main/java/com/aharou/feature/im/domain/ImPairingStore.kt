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

@Serializable
internal data class PairingFile(
    val approved: Map<String, ApprovedEntry> = emptyMap(),
    val pending: Map<String, PendingEntry> = emptyMap()
)

/**
 * 配对码与白名单存储。
 *
 * 落在 `filesDir/im/telegram/pairing.json`（App 私有目录），**绝不放工作区**：agent 的文件工具
 * 能写工作区，而「谁能和机器人说话」不该由 agent 决定。写入走临时文件 + rename，避免中断写坏。
 *
 * 只有单通道（Telegram），故白名单与待审批都扁平存 senderId → 记录，不再按 channel 分组。
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

    /** 已批准名单（按通过时间）。 */
    suspend fun approved(): List<PairedChat> = mutex.withLock {
        read().approved.map { (senderId, entry) ->
            PairedChat(
                senderId = senderId,
                senderName = entry.senderName,
                chatId = entry.chatId.ifBlank { senderId },
                approvedAt = entry.approvedAt
            )
        }.sortedByDescending { it.approvedAt }
    }

    suspend fun isApproved(senderId: String): Boolean = mutex.withLock {
        read().approved.containsKey(senderId)
    }

    /** 待审批列表（过期项已在读取时清理）。 */
    suspend fun pending(): List<PendingPairing> = mutex.withLock {
        read().pending.map { (code, entry) ->
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
     * 为某人发放配对码：同一人上一枚码仍有效时复用同一枚，避免刷屏。
     * 返回 6 位码（字符集去掉易混的 0/O/1/I）。
     */
    suspend fun request(senderId: String, senderName: String, chatId: String): String = mutex.withLock {
        val data = read()
        val now = System.currentTimeMillis()
        data.pending.entries.firstOrNull { it.value.senderId == senderId }?.let { return it.key }
        var code = newCode()
        while (data.pending.containsKey(code)) code = newCode()
        val pending = data.pending.toMutableMap()
        pending[code] = PendingEntry(
            senderId = senderId,
            senderName = senderName,
            chatId = chatId,
            createdAt = now,
            expiresAt = now + PAIRING_TTL_MS
        )
        write(data.copy(pending = pending))
        code
    }

    /** 通过配对码把人加进白名单；码不存在或已过期返回 null。 */
    suspend fun approve(code: String): PairedChat? = mutex.withLock {
        val data = read()
        val key = code.trim().uppercase()
        val info = data.pending[key] ?: return null
        val pending = data.pending.toMutableMap().apply { remove(key) }
        val approved = data.approved.toMutableMap()
        val entry = ApprovedEntry(
            senderName = info.senderName,
            chatId = info.chatId.ifBlank { info.senderId },
            approvedAt = System.currentTimeMillis()
        )
        approved[info.senderId] = entry
        write(data.copy(approved = approved, pending = pending))
        PairedChat(
            senderId = info.senderId,
            senderName = entry.senderName,
            chatId = entry.chatId,
            approvedAt = entry.approvedAt
        )
    }

    /** 拒绝一枚配对码；返回对应 senderId，未找到返回 null。 */
    suspend fun deny(code: String): String? = mutex.withLock {
        val data = read()
        val key = code.trim().uppercase()
        val info = data.pending[key] ?: return null
        val pending = data.pending.toMutableMap().apply { remove(key) }
        write(data.copy(pending = pending))
        info.senderId
    }

    /** 移除已批准的人；下次来消息会重新发码。 */
    suspend fun remove(senderId: String): Boolean = mutex.withLock {
        val data = read()
        if (!data.approved.containsKey(senderId)) return false
        val approved = data.approved.toMutableMap().apply { remove(senderId) }
        write(data.copy(approved = approved))
        true
    }

    /** 已批准者改名或换私聊时刷新记录。 */
    suspend fun touch(senderId: String, senderName: String, chatId: String) = mutex.withLock {
        val data = read()
        val entry = data.approved[senderId] ?: return
        val name = senderName.ifBlank { entry.senderName }
        val chat = chatId.ifBlank { entry.chatId }
        if (name == entry.senderName && chat == entry.chatId) return
        val approved = data.approved.toMutableMap().apply {
            this[senderId] = entry.copy(senderName = name, chatId = chat)
        }
        write(data.copy(approved = approved))
    }

    /** 读取并顺手清理过期待审批码。 */
    private fun read(): PairingFile {
        if (!file.exists()) return PairingFile()
        val loaded = runCatching {
            json.decodeFromString(PairingFile.serializer(), file.readText())
        }.onFailure { FileLogger.w(TAG, "读取配对文件失败", it) }.getOrNull() ?: PairingFile()

        val now = System.currentTimeMillis()
        val alive = loaded.pending.filterValues { it.expiresAt > now }
        return if (alive.size == loaded.pending.size) loaded else loaded.copy(pending = alive)
    }

    private suspend fun write(data: PairingFile) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "pairing.json.tmp")
            tmp.writeText(json.encodeToString(PairingFile.serializer(), data))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "写入配对文件失败", it) }
        Unit
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
