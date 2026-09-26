package com.aharou.feature.settings.data.repository

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.KeyRotationStrategy
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Key 切换的结果，供调用方改写凭据并提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newKey: String,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int
)

/**
 * 多 Key 轮换器：决定某个 provider 在某条会话上该用哪个 API Key，并在 Key 不可用时切到下一个。
 *
 * 状态全部是运行时内存态、不落库：进程重启后回到第一个 Key、冷却记录清空——多 Key 的意义是
 * 遇到鉴权/额度/限流问题时能立刻绕开，没有必须跨进程保持的语义。
 *
 * **会话粘性**是这里的核心约束：服务端 prompt 缓存按 API Key 隔离，同一会话逐请求换 Key 会让
 * 每轮都落到没有缓存的 Key 上，缓存命中率归零、成本和首字延迟一起恶化。因此只在会话首次取
 * Key、或当前 Key 被判定不可用时才换，其余情况一直粘住同一个 Key。
 */
@Singleton
class ProviderKeyRotator @Inject constructor() {

    /** 由最近一次 [activeKey] 记录的 provider Key 配置，供失败上报时读取候选与冷却时长。 */
    private data class KeySetup(
        val keys: List<String>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + Key → 冷却截止时间戳（毫秒）。 */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → 轮询策略下一个新会话的起始下标。 */
    private val roundRobinCursor = mutableMapOf<String, Int>()

    /** providerId → 最近一次选中的 Key，供余额查询等旁路请求取「当前活动 Key」。 */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → 该会话粘住的 Key。会话数量无上界，用访问序 LRU 兜住内存，
     * 被淘汰的老会话下次请求重新分配 Key 即可，没有正确性问题。
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * 取 [config] 在 [sessionId] 上当前该用的 Key；没有任何可用 Key、或所有 Key 都在冷却时返回 null。
     * 同时把该 provider 的 Key 配置快照下来，供后续 [reportFailure] 使用。
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[bindKey] }
            if (bound != null && bound in keys && !isCoolingDown(config.id, bound)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet()) ?: return null
            if (bindKey != null) sessionKeys[bindKey] = chosen
            lastSelected[config.id] = chosen
            return chosen
        }
    }

    /**
     * 只读地取该 provider 当前活动的 Key：不建会话绑定、不推进轮询游标。
     * 供余额查询这类旁路请求使用——它们要反映「聊天正在用哪个 Key」，而不该干扰轮换节奏。
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && last in keys && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it) } ?: keys.first()
        }
    }

    /**
     * 上报一次可归因于 [key] 的失败：**立即**切到下一个可用 Key、把 [key] 打入冷却并重绑会话，
     * 返回切换结果；没有可切换的候选（候选已试完、或全部在冷却）时返回 null——此时**不冷却** [key]，
     * 避免把最后一个可用 Key 也锁死、让用户在一段时间内完全无法发起请求。
     *
     * [triedKeys] 为本次请求已试过的 Key（含 [key] 自身），用于避免同一次请求内重复试同一个 Key。
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        key: String,
        triedKeys: Set<String> = emptySet()
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (key !in setup.keys) return null

            // 先确认有可切换的候选，没有就别冷却——把最后一个可用 Key 冷却只会让用户在一段时间内完全不可用
            val next = pick(providerId, setup.keys, setup.strategy, exclude = triedKeys + key) ?: return null
            if (setup.cooldownMillis > 0) {
                cooldownUntil[stateKey(providerId, key)] = System.currentTimeMillis() + setup.cooldownMillis
            }
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            val index = setup.keys.indexOf(next) + 1
            FileLogger.i(TAG, "Key 切换 provider=$providerId ${key.masked()} → ${next.masked()} (第 $index/${setup.keys.size} 个)")
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /**
     * 按策略挑一个可选 Key：排除 [exclude]（本次已试过）与处于冷却中的 Key；没有可选时返回 null。
     * 不做「全冷却就退回最早到期者再试一遍」的回退——那等于拿已知不可用的 Key 硬打。
     */
    private fun pick(
        providerId: String,
        keys: List<String>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>
    ): String? {
        val ordered = when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> keys
            KeyRotationStrategy.ROUND_ROBIN -> {
                val start = (roundRobinCursor[providerId] ?: 0) % keys.size
                roundRobinCursor[providerId] = (start + 1) % keys.size
                keys.subList(start, keys.size) + keys.subList(0, start)
            }
        }
        return ordered.firstOrNull { it !in exclude && !isCoolingDown(providerId, it) }
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
