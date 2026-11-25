package com.aharou.feature.agent.domain.personal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 受后台读取配额约束的设备敏感数据源。
 *
 * 只覆盖「后台自动读取」会碰到的那几类：位置、通知、短信。通话记录、联系人、相册不在此列，
 * 原因是它们没有被自动化任务周期性读取的典型用法，加进来只是多一层记账负担。
 */
enum class DeviceReadCategory(val id: String) {
    LOCATION("location"),
    NOTIFICATION("notification"),
    SMS("sms"),
}

/** 一次配额判定。放行时 [reason] 为 null；被拒时 [retryAfterSeconds] 给出还要等多久。 */
data class DeviceReadQuotaDecision(
    val allowed: Boolean,
    val category: DeviceReadCategory,
    val used: Int,
    val limit: Int,
    val retryAfterSeconds: Long,
    val reason: String? = null,
)

/** 某类数据源的配额快照，供设置页与配置通道展示。 */
data class DeviceReadQuotaStatus(
    val category: DeviceReadCategory,
    val used: Int,
    val limit: Int,
    val minIntervalSeconds: Int,
    val earliestExpirySeconds: Long,
)

/**
 * 后台自动读取的设备阅读配额。
 *
 * 为什么需要它：自动化任务在没人看着的时候跑，模型可以反复调 personal_context / device 去读
 * 短信、通知、位置。这类读取既耗电也涉及隐私，用户不在跟前时没有机会喊停。配额只拦「自动触发
 * 轮次」的读取（[com.aharou.feature.agent.domain.workflow.AgentTurnTrigger.SCHEDULED_TASK]），
 * 用户自己在对话里让它读走的是手动轮次，不受任何限制。
 *
 * 两道上限：滚动 24 小时次数上限与同类读取的最小间隔（防止一次任务里连续读几十次位置）。
 * 窗口用时间戳列表实现，读取时顺手丢弃超过 24 小时的记录，不另开定时清理。
 *
 * 持久化用 SharedPreferences 而不是数据库：这里记的是按小时滚动的短生命周期计数，
 * 不值得为它加表加迁移，App 数据被清掉导致配额归零也无碍——最坏情况是当天多读了两次。
 */
@Singleton
class DeviceReadQuota @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    companion object {
        /** 距上一次同类读取太近。 */
        const val REASON_TOO_FREQUENT = "too_frequent"

        /** 滚动 24 小时内的次数已用尽。 */
        const val REASON_DAILY_LIMIT = "daily_limit_reached"

        private const val PREFS = "aharou_device_read_quota"
        private const val WINDOW_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * 各数据源的默认额度。取值按「一个自动化任务每 15 分钟读一次、一天最多 96 次」估算，
         * 位置再收紧一点是因为定位最耗电。这些数不是从别处抄来的定值，用户觉得不够可以经
         * 配置通道调大，代码不替用户拍板。
         */
        private val DEFAULT_LIMITS = mapOf(
            DeviceReadCategory.LOCATION to 60,
            DeviceReadCategory.NOTIFICATION to 200,
            DeviceReadCategory.SMS to 200,
        )
        private val DEFAULT_MIN_INTERVAL_SECONDS = mapOf(
            DeviceReadCategory.LOCATION to 60,
            DeviceReadCategory.NOTIFICATION to 10,
            DeviceReadCategory.SMS to 10,
        )
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** 串行化读写：同一进程内多个会话可能并发读设备，不锁会让同一时刻的多笔请求一起穿过上限。 */
    private val lock = Any()

    // ---- 判定 ----

    /** 只判定不记账，供调用方先看结果再决定是否真的去读。 */
    fun check(category: DeviceReadCategory, now: Long = System.currentTimeMillis()): DeviceReadQuotaDecision =
        synchronized(lock) { decide(category, now) }

    /** 判定并记账。放行才写时间戳；被拒的那次不消耗额度。 */
    fun checkAndRecord(
        category: DeviceReadCategory,
        now: Long = System.currentTimeMillis(),
    ): DeviceReadQuotaDecision = synchronized(lock) {
        val decision = decide(category, now)
        if (decision.allowed) {
            val stamps = load(category, now).also { it.add(now) }
            save(category, stamps)
        }
        decision
    }

    private fun decide(category: DeviceReadCategory, now: Long): DeviceReadQuotaDecision {
        val limit = limitOf(category)
        val minInterval = minIntervalOf(category)
        val stamps = load(category, now)
        val last = stamps.lastOrNull()
        if (last != null && minInterval > 0 && now - last < minInterval * 1000L) {
            val wait = ((minInterval * 1000L - (now - last)) / 1000L).coerceAtLeast(1L)
            return DeviceReadQuotaDecision(false, category, stamps.size, limit, wait, REASON_TOO_FREQUENT)
        }
        if (limit > 0 && stamps.size >= limit) {
            val oldest = stamps.first()
            val wait = ((WINDOW_MILLIS - (now - oldest)) / 1000L).coerceAtLeast(1L)
            return DeviceReadQuotaDecision(false, category, stamps.size, limit, wait, REASON_DAILY_LIMIT)
        }
        return DeviceReadQuotaDecision(true, category, stamps.size, limit, 0L)
    }

    // ---- 状态 ----

    fun status(now: Long = System.currentTimeMillis()): List<DeviceReadQuotaStatus> =
        DeviceReadCategory.values().map { category ->
            val stamps = synchronized(lock) { load(category, now) }
            val earliest = stamps.firstOrNull()?.let { (WINDOW_MILLIS - (now - it)) / 1000L } ?: 0L
            DeviceReadQuotaStatus(
                category = category,
                used = stamps.size,
                limit = limitOf(category),
                minIntervalSeconds = minIntervalOf(category),
                earliestExpirySeconds = earliest.coerceAtLeast(0L),
            )
        }

    /** 清空某一类的全部读数，设置页提供「今天已用」归零用。 */
    fun reset(category: DeviceReadCategory) {
        synchronized(lock) { prefs.edit().remove(keyOf(category)).commit() }
    }

    // ---- 可调项 ----

    fun limitOf(category: DeviceReadCategory): Int = prefs.getInt(
        "limit.${category.id}",
        DEFAULT_LIMITS.getValue(category),
    )

    fun minIntervalOf(category: DeviceReadCategory): Int = prefs.getInt(
        "interval.${category.id}",
        DEFAULT_MIN_INTERVAL_SECONDS.getValue(category),
    )

    /** [limit] 传 0 表示不设次数上限，只保留最小间隔。 */
    fun setLimit(category: DeviceReadCategory, limit: Int) {
        prefs.edit().putInt("limit.${category.id}", limit.coerceAtLeast(0)).commit()
    }

    fun setMinInterval(category: DeviceReadCategory, seconds: Int) {
        prefs.edit().putInt("interval.${category.id}", seconds.coerceAtLeast(0)).commit()
    }

    // ---- 存储 ----

    private fun keyOf(category: DeviceReadCategory) = "stamps.${category.id}"

    private fun load(category: DeviceReadCategory, now: Long): MutableList<Long> {
        val raw = prefs.getString(keyOf(category), null) ?: return mutableListOf()
        val all = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }
        val kept = all.filter { now - it < WINDOW_MILLIS }.sorted().toMutableList()
        // 顺手把过期项落盘，避免只在内存里过滤、文件随时间无限增长。
        if (kept.size < all.size) save(category, kept)
        return kept
    }

    private fun save(category: DeviceReadCategory, stamps: List<Long>) {
        prefs.edit().putString(keyOf(category), stamps.joinToString(",")).commit()
    }
}
