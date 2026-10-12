package com.aharou.feature.remotedevice.server

import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 传输方向。 */
enum class TransferDirection { DOWNLOAD, UPLOAD }

/** 一张一次性的文件传输票据：只够完成一次下载或上传。 */
data class FileTicket(
    val id: String,
    val path: String,
    val direction: TransferDirection,
    val expiresAt: Long
)

/**
 * 文件传输票据：由 `fetch_file` / `push_file` 动作签发，`/file/<id>` 与 `/upload/<id>` 消费。
 *
 * 为什么走票据而不是让对端带 Bearer token：文件走的是原生 HTTP 流，取它的常常是浏览器、
 * curl 或另一个进程，它们不方便加自定义头。票据是 24 字节随机串、一次性、15 分钟时效，
 * 且只对签发时那一个路径有效——泄露面比长期 token 小得多。
 */
@Singleton
class RemoteFileTicketStore @Inject constructor() {
    private val random = SecureRandom()
    private val tickets = mutableMapOf<String, FileTicket>()

    @Synchronized
    fun issue(path: String, direction: TransferDirection, ttlMs: Long = TICKET_TTL_MS): FileTicket {
        purgeExpired()
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val ticket = FileTicket(id, path, direction, System.currentTimeMillis() + ttlMs)
        tickets[id] = ticket
        return ticket
    }

    /** 取出并作废（一次性）；方向不符或已过期返回 null。 */
    @Synchronized
    fun consume(id: String, direction: TransferDirection): FileTicket? {
        purgeExpired()
        val ticket = tickets[id] ?: return null
        if (ticket.direction != direction) return null
        tickets.remove(id)
        return ticket
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        tickets.entries.removeAll { it.value.expiresAt <= now }
    }

    private companion object {
        const val TICKET_TTL_MS = 15 * 60 * 1000L
    }
}
