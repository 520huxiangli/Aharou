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
        private const val PET_BONUS = 2
        private const val POKE_PENALTY = 3
        private const val DAILY_DECAY = 1
        private const val GRACE_DAYS = 3
        private const val SULK_MS = 3L * 60 * 1000

        /** 初始好感：略高于「平常」的下限，第一次摸头就能看出反应。 */
        private const val INITIAL = 40

        fun initial(now: Long = System.currentTimeMillis()): PetMood =
            PetMood(INITIAL, now, now, 0L)
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
