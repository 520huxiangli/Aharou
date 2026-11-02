package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import java.io.File
import java.security.MessageDigest

/** 一次投递的判定结果。[DUPLICATE] 与 [FULL] 都算拒收，但原因不同，要让用户分得清。 */
enum class DedupDecision {
    /** 可以投递。 */
    ACCEPTED,

    /** 同一条事件 24 小时内已经投递过。 */
    DUPLICATE,

    /** 凭据表满了，本次事件拒收。 */
    FULL,
}

/**
 * 事件去重凭据：管的是「同一条事件绝不重复执行」，与 `debounceMinutes` 不是一回事——
 * 防抖只是把窗口内连续到来的事件合并成一次，窗口一过同样的通知再来照样会跑。
 *
 * 凭据按「任务 + 触发类型 + 来源 + 内容」算，落盘在应用私有文件里：通知监听服务会被系统回收重建，
 * 只放内存的话，重连后系统重放的通知会当成新事件再跑一遍。
 *
 * 为什么满了拒收、不淘汰旧凭据：被淘汰的那条在有效期内会重新变成「新事件」再跑一遍，
 * 正是这套机制要防的事。自动化任务重复执行（可能发消息、下单）比漏执行更糟。
 */
class EventDedupStore(private val file: File) {

    /** 凭据 → 过期时刻（epoch 毫秒）。 */
    private val entries = HashMap<String, Long>()

    private var loaded = false

    fun accept(taskId: String, type: String, payload: EventPayload, now: Long): DedupDecision =
        synchronized(this) {
            ensureLoaded(now)
            purgeExpired(now)
            val key = credentialOf(taskId, type, payload)
            val expiresAt = entries[key]
            if (expiresAt != null && expiresAt > now) {
                FileLogger.i(TAG, "事件在 24 小时内已投递过，忽略重复：$type/${payload.app}")
                return@synchronized DedupDecision.DUPLICATE
            }
            if (entries.size >= MAX_ENTRIES) {
                FileLogger.w(TAG, "事件去重凭据表已满（$MAX_ENTRIES 条），本次事件拒收：$type/${payload.app}")
                return@synchronized DedupDecision.FULL
            }
            val expireAt = now + TTL_MILLIS
            entries[key] = expireAt
            append(key, expireAt)
            DedupDecision.ACCEPTED
        }

    /** 投递没落地（拨时间失败等）时撤回登记，否则这条事件 24 小时内再来就被自己的登记挡住了。 */
    fun forget(taskId: String, type: String, payload: EventPayload) {
        synchronized(this) {
            if (!loaded) return
            if (entries.remove(credentialOf(taskId, type, payload)) == null) return
            rewrite()
        }
    }

    private fun ensureLoaded(now: Long) {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.exists()) file.readText() else "" }
            .onFailure { FileLogger.w(TAG, "读取事件去重凭据失败", it) }
            .getOrElse { return }
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val expiresAt = line.substring(0, tab).toLongOrNull() ?: return@forEach
            // 过期的直接不入表：启动时清理过期凭据，有效期内的一条不动。
            if (expiresAt > now) entries[line.substring(tab + 1)] = expiresAt
        }
        // 文件里可能留着已过期的行，整理掉，免得越攒越长。
        if (entries.size != text.lineSequence().count { it.isNotBlank() }) rewrite()
    }

    private fun purgeExpired(now: Long) {
        val before = entries.size
        entries.entries.removeAll { it.value <= now }
        if (entries.size != before) rewrite()
    }

    private fun append(key: String, expiresAt: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$expiresAt\t$key\n")
        }.onFailure { FileLogger.w(TAG, "事件去重凭据落盘失败", it) }
    }

    private fun rewrite() {
        runCatching {
            file.parentFile?.mkdirs()
            val content = buildString {
                entries.forEach { (key, expiresAt) ->
                    this.append(expiresAt).append('\t').append(key).append('\n')
                }
            }
            file.writeText(content)
        }.onFailure { FileLogger.w(TAG, "事件去重凭据整理失败", it) }
    }

    /**
     * 稳定凭据：同来源 + 同类型 + 同内容 → 同一串。
     *
     * 不含 `timeMillis`——它每次都不同，含进去等于根本不去重；也不含通知的送达序号之类的运行期信息。
     * 带上任务 id 是因为两个任务同时被同一条通知触发是两个意图，后者不该被前者挡掉。
     */
    private fun credentialOf(taskId: String, type: String, payload: EventPayload): String {
        val canonical = buildString {
            this.append(type).append('\n')
            this.append(payload.app).append('\n')
            this.append(payload.title).append('\n')
            this.append(payload.text).append('\n')
            this.append(payload.batteryPercent).append('\n')
            this.append(payload.ssid)
        }
        return taskId + "|" + sha256Hex(canonical)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(32)
        // 取前 16 字节（128 位）足够区分事件，文件里也不至于太长。
        for (i in 0 until 16) {
            val byte = digest[i].toInt() and 0xFF
            builder.append(HEX[byte ushr 4]).append(HEX[byte and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private const val TAG = "EventDedup"

        /** 凭据有效期一天。系统重投通知、监听重连后重放都在分钟级，给足一天是「宁判重不重跑」的取舍。 */
        const val TTL_MILLIS = 24 * 60 * 60 * 1000L

        /** 凭据表上限。满了拒收新事件，绝不淘汰未过期的旧凭据（理由见类注释）。 */
        const val MAX_ENTRIES = 1_024

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * 哪些触发类型才套去重。只有通知的内容本身能唯一标识「一次事件」（包名 + 标题 + 正文）；
         * 开机与 App 启动没有内容，两次启动的凭据完全相同，24 小时只放行第一次等于把任务停了；
         * 电量 / WiFi 的数值是状态而不是事件身份（50%→40%→50% 是三次真事件），
         * 而且这两类本来就只在「变了」的时候才投递。
         */
        fun appliesTo(type: String): Boolean = type == ScheduledTaskEntity.TYPE_NOTIFICATION
    }
}
