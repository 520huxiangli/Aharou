package com.aharou.feature.pet

import android.content.Context

/**
 * 小染对主人的态度。
 *
 * 好感度落盘，只做三件事：决定用哪一档台词、摸头时给什么反应、以及久别重逢要不要说重话。
 * 变化规则都往「不惩罚用户」的方向调：被戳烦了才掉分、长期不理只缓慢变淡且有下限，
 * 关掉 App 一周回来不会变成陌生狐。
 */
data class PetMood(
    /** 0~100。 */
    val affection: Int,
    /** 上次被互动的时刻（毫秒）：拖动、点她、唤菜单都算。 */
    val lastTouchAt: Long,
    /** 上次「见面」的时刻（毫秒）：她现身或 App 打开时刷新，用来算久别。 */
    val lastSeenAt: Long,
    /** 闹别扭的截止时刻（毫秒），期间嘴硬。 */
    val sulkUntil: Long,
) {

    enum class Level { COLD, NORMAL, CLOSE, CLINGY }

    val level: Level
        get() = when {
            affection >= CLINGY_AT -> Level.CLINGY
            affection >= CLOSE_AT -> Level.CLOSE
            affection >= NORMAL_AT -> Level.NORMAL
            else -> Level.COLD
        }

    /** 是不是正在闹别扭（被戳烦之后的一小段时间）。 */
    fun isSulking(now: Long = System.currentTimeMillis()): Boolean = now < sulkUntil

    /**
     * 0~1：多久没被搭理。隔得越久越想被注意，一天封顶。
     *
     * 只拿互动时刻算，不加新字段——落盘格式不动，旧版本读出来的状态照样能算。
     */
    fun attentionNeed(now: Long = System.currentTimeMillis()): Double {
        val hours = ((now - lastTouchAt).coerceAtLeast(0L)) / HOUR_MS.toDouble()
        return (hours / 24.0).coerceIn(0.0, 1.0)
    }

    /**
     * 0~1：当前时段的精力。深夜最低，白天满格。
     *
     * 作息是人的，不是好感度的——所以按小时算，不按被摸次数算，否则戳多了她会「累」得莫名其妙。
     */
    fun energyAt(hour: Int): Double = when (hour) {
        in 0..5 -> 0.25
        in 6..7 -> 0.6
        in 8..22 -> 1.0
        else -> 0.5
    }

    /**
     * 当前该用哪一档口气：好感度打底，冷落时长与时段修正。
     *
     * 比单看好感度多两件事：很久没人理时说「普通」而不是冷话，深夜不黏人。
     * 闹别扭与冷淡档不受影响——那是明确的负向状态，不该被冷落时长抹掉。
     */
    fun toneFor(now: Long = System.currentTimeMillis(), hour: Int = hourOf(now)): Level {
        if (isSulking(now) || level == Level.COLD) return Level.COLD
        val energy = energyAt(hour)
        if (energy < 0.4) return Level.NORMAL
        if (attentionNeed(now) >= NEEDY_AFTER) return Level.NORMAL
        return level
    }

    /** 隔了几个自然日没见；当天见过算 0。 */
    fun daysSinceSeen(now: Long = System.currentTimeMillis()): Int =
        ((now - lastSeenAt) / DAY_MS).toInt().coerceAtLeast(0)

    fun petted(now: Long): PetMood =
        copy(affection = (affection + PET_BONUS).coerceAtMost(MAX), lastTouchAt = now)

    /** 被戳烦：掉分 + 一小段别扭。 */
    fun pokedTooMuch(now: Long): PetMood = copy(
        affection = (affection - POKE_PENALTY).coerceAtLeast(MIN),
        lastTouchAt = now,
        sulkUntil = now + SULK_MS,
    )

    fun touched(now: Long): PetMood = copy(lastTouchAt = now)

    /**
     * 见面结算：补上离线期间的缓慢变淡，并把「上次见面」推到今天。
     * 返回结算后的状态与隔了几天（调用方按天数决定要不要说重逢台词）。
     */
    fun appeared(now: Long): Pair<PetMood, Int> {
        val missed = daysSinceSeen(now)
        val decayed = if (missed > GRACE_DAYS) {
            affection - (missed - GRACE_DAYS) * DAILY_DECAY
        } else {
            affection
        }
        return copy(
            affection = decayed.coerceIn(MIN, MAX),
            lastSeenAt = now,
        ) to missed
    }

    companion object {
        const val MIN = 20
        const val MAX = 100
        const val NORMAL_AT = 20
        const val CLOSE_AT = 60
        const val CLINGY_AT = 85

        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000

        /** 冷落超过这个比例就不再装作若无其事，退回「平常」那一档。 */
        private const val NEEDY_AFTER = 0.5
        private const val PET_BONUS = 2
        private const val POKE_PENALTY = 3
        private const val DAILY_DECAY = 1
        private const val GRACE_DAYS = 3
        private const val SULK_MS = 3L * 60 * 1000

        /** 初始好感：略高于「平常」的下限，第一次摸头就能看出反应。 */
        private const val INITIAL = 40

        fun initial(now: Long = System.currentTimeMillis()): PetMood =
            PetMood(INITIAL, now, now, 0L)

        private fun hourOf(now: Long): Int {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = now
            return c.get(java.util.Calendar.HOUR_OF_DAY)
        }
    }
}

/**
 * 好感度的落盘读写。读写都走同一份 prefs，服务没在跑时设置页也能看。
 */
object PetMoodStore {

    private const val KEY_AFFECTION = "mood_affection"
    private const val KEY_LAST_TOUCH = "mood_last_touch"
    private const val KEY_LAST_SEEN = "mood_last_seen"
    private const val KEY_SULK_UNTIL = "mood_sulk_until"
    private const val KEY_SAID_DAY = "said_day"
    private const val KEY_TODAY_SAID = "today_said"

    // ── 今日已说台词（跨进程用 SharedPreferences，自然日重置）────────────────

    /** 读取今天已说过的台词集合；日期不符则视为空集。 */
    fun readTodaySaid(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        if (prefs.getString(KEY_SAID_DAY, null) != today) return emptySet()
        val raw = prefs.getString(KEY_TODAY_SAID, null) ?: return emptySet()
        return raw.split("|").filter { it.isNotBlank() }.toSet()
    }

    /** 把一句台词加入今日已说集合，并刷新自然日 key。 */
    fun markSaid(context: Context, line: String) {
        if (line.isBlank()) return
        val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val existing = readTodaySaid(context)
        val updated = (existing + line).joinToString("|")
        prefs.edit()
            .putString(KEY_SAID_DAY, today)
            .putString(KEY_TODAY_SAID, updated)
            .apply()
    }

    private fun todayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    fun read(context: Context): PetMood {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_AFFECTION)) return PetMood.initial(now)
        return PetMood(
            affection = prefs.getInt(KEY_AFFECTION, PetMood.initial(now).affection),
            lastTouchAt = prefs.getLong(KEY_LAST_TOUCH, now),
            lastSeenAt = prefs.getLong(KEY_LAST_SEEN, now),
            sulkUntil = prefs.getLong(KEY_SULK_UNTIL, 0L),
        )
    }

    fun write(context: Context, mood: PetMood) {
        context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AFFECTION, mood.affection)
            .putLong(KEY_LAST_TOUCH, mood.lastTouchAt)
            .putLong(KEY_LAST_SEEN, mood.lastSeenAt)
            .putLong(KEY_SULK_UNTIL, mood.sulkUntil)
            .apply()
    }
}
