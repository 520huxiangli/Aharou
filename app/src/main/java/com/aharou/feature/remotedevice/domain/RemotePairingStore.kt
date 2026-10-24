package com.aharou.feature.remotedevice.domain

import android.content.Context
import android.util.Base64
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条待使用的配对码（一次性消费；不设过期）。 */
@Serializable
internal data class PendingCodeEntry(
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L
)

/** 一条已授权的远端设备记录；key 是 token 的 SHA-256，明文 token 只在签发那一刻返回给对端。 */
@Serializable
internal data class ApprovedDeviceEntry(
    val deviceName: String = "",
    val deviceId: String = "",
    val approvedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    /** token 前 4 位，仅用于设置页展示「哪一条授权是哪台机」，不足以还原 token。 */
    val tokenHint: String = ""
)

@Serializable
internal data class PairingFile(
    val pending: Map<String, PendingCodeEntry> = emptyMap(),
    val devices: Map<String, ApprovedDeviceEntry> = emptyMap()
)

/** 已授权设备（对外视图，不含 token 与 hash）。 */
data class RemotePairedDevice(
    val deviceId: String,
    val deviceName: String,
    val approvedAt: Long,
    val lastSeenAt: Long,
    val tokenHint: String
)

/** 配对（用码换 token）的结果。 */
sealed interface PairOutcome {
    data class Ok(val token: String, val device: RemotePairedDevice) : PairOutcome

    /** 码不存在或已过期。 */
    data object InvalidCode : PairOutcome

    /** 失败次数过多，暂时拒绝。 */
    data class Throttled(val retryAfterMs: Long) : PairOutcome
}

/**
 * 配对码与长期 token 的存储。
 *
 * 落点 `filesDir/remotedevice/pairing.json`（App 私有目录），**绝不放工作区**：agent 的文件工具能写工作区，
 * 而「谁能在本机执行动作」不该由 agent 决定。
 *
 * 两条安全约定：
 *  - 只存 token 的 SHA-256，不存明文——文件即使被读走也无法直接拿去调用本机服务；
 *  - 配对码一次性消费、不设过期，连续试错 5 次锁 60 秒，挡暴力枚举（6 位码空间有限，必须节流）。
 */
@Singleton
class RemotePairingStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val random = SecureRandom()

    private val file: File
        get() = File(File(context.filesDir, RemoteDeviceSettings.DIR_NAME), "pairing.json").apply {
            parentFile?.mkdirs()
        }

    // 试错节流（内存态：进程重启即清零，配对码本身也只在内存期有效范围内有意义）。
    private var failedAttempts = 0
    private var lockedUntil = 0L

    /** 生成或复用当前的一次性配对码（6 位，去掉易混字符）。码不过期，配对成功后自动换新。 */
    suspend fun issueCode(): String = mutex.withLock {
        val data = read()
        val now = System.currentTimeMillis()
        data.pending.entries.firstOrNull()?.let { return it.key }
        var code = newCode()
        while (data.pending.containsKey(code)) code = newCode()
        val pending = data.pending.toMutableMap()
        // 只保留最新一枚码：多枚并存会让用户不知道该念哪枚。
        pending.clear()
        pending[code] = PendingCodeEntry(createdAt = now, expiresAt = NO_EXPIRY)
        write(data.copy(pending = pending))
        code
    }

    /** 当前待用的配对码；到期时刻固定为 0，表示不过期，界面据此不显示倒计时。 */
    suspend fun liveCode(): Pair<String, Long>? = mutex.withLock {
        read().pending.entries.firstOrNull()?.let { it.key to 0L }
    }

    /** 用配对码换长期 token；成功后码立即作废。 */
    suspend fun pair(code: String, deviceName: String, deviceId: String): PairOutcome = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PairOutcome.Throttled(lockedUntil - now)

        val data = read()
        val key = code.trim().uppercase()
        val entry = data.pending[key]
        if (entry == null) {
            failedAttempts++
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                lockedUntil = now + LOCK_MS
                failedAttempts = 0
            }
            return PairOutcome.InvalidCode
        }
        failedAttempts = 0

        val token = newToken()
        val hint = token.take(4)
        val record = ApprovedDeviceEntry(
            deviceName = deviceName.trim().take(64),
            deviceId = deviceId.trim().take(64),
            approvedAt = now,
            lastSeenAt = now,
            tokenHint = hint
        )
        val devices = data.devices.toMutableMap().apply { put(hash(token), record) }
        write(data.copy(pending = emptyMap(), devices = devices))
        FileLogger.i(TAG, "远端设备配对成功：${record.deviceName.ifBlank { record.deviceId }}")
        PairOutcome.Ok(
            token = token,
            device = RemotePairedDevice(record.deviceId, record.deviceName, now, now, hint)
        )
    }

    /** 校验 token；命中则顺带刷新 lastSeenAt（写盘节流到每分钟一次，避免每个请求都落盘）。 */
    suspend fun authenticate(token: String?): RemotePairedDevice? {
        if (token.isNullOrBlank()) return null
        val entry = mutex.withLock {
            val data = read()
            val key = hash(token)
            val record = data.devices[key] ?: return@withLock null
            val now = System.currentTimeMillis()
            if (now - record.lastSeenAt <= LAST_SEEN_WRITE_INTERVAL_MS) return@withLock record
            val devices = data.devices.toMutableMap().apply { put(key, record.copy(lastSeenAt = now)) }
            write(data.copy(devices = devices))
            record.copy(lastSeenAt = now)
        } ?: return null
        return RemotePairedDevice(entry.deviceId, entry.deviceName, entry.approvedAt, entry.lastSeenAt, entry.tokenHint)
    }

    suspend fun devices(): List<RemotePairedDevice> = mutex.withLock {
        read().devices.values
            .map { RemotePairedDevice(it.deviceId, it.deviceName, it.approvedAt, it.lastSeenAt, it.tokenHint) }
            .sortedByDescending { it.approvedAt }
    }

    /** 撤销某台设备的授权（按 deviceId）；下次调用会拿到 401，需重新配对。 */
    suspend fun revoke(deviceId: String): Boolean = mutex.withLock {
        val data = read()
        val hit = data.devices.entries.filter { it.value.deviceId == deviceId }
        if (hit.isEmpty()) return false
        val devices = data.devices.toMutableMap().apply { hit.forEach { remove(it.key) } }
        write(data.copy(devices = devices))
        FileLogger.i(TAG, "已撤销远端设备授权：$deviceId")
        true
    }

    /** 清空全部授权与待用配对码（关闭开关时用）。 */
    suspend fun revokeAll() = mutex.withLock {
        write(PairingFile())
    }

    /** 读取并顺手清理「旧版遗留的已过期码」；新版码（[NO_EXPIRY]）永远保留。 */
    private fun read(): PairingFile {
        if (!file.exists()) return PairingFile()
        val loaded = runCatching {
            json.decodeFromString(PairingFile.serializer(), file.readText())
        }.onFailure { FileLogger.w(TAG, "读取配对文件失败", it) }.getOrNull() ?: PairingFile()
        val now = System.currentTimeMillis()
        val alive = loaded.pending.filterValues { it.expiresAt == NO_EXPIRY || it.expiresAt > now }
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

    /** 32 字符 URL-safe token（24 字节随机，去掉填充）。 */
    private fun newToken(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "RemotePairingStore"

        /**
         * 配对码不设过期：协助是长期关系，每次都要重新生成会把功能用废。
         * 枚举风险由试错节流兜底（[MAX_FAILED_ATTEMPTS] 次锁 [LOCK_MS]），配对成功即作废换新。
         */
        private const val NO_EXPIRY = Long.MAX_VALUE
        private const val CODE_LENGTH = 6

        /** 去掉易混的 0/O/1/I：码要念出来或照屏幕敲。 */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCK_MS = 60 * 1000L
        private const val LAST_SEEN_WRITE_INTERVAL_MS = 60 * 1000L
    }
}
