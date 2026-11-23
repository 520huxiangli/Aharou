package com.aharou.feature.plugin.workflow

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 触发条件的时间计算：interval / specific_time / cron。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`core/workflow/WorkflowScheduler.kt` 的
 * `calculateNextCronTime` / `calculateDailyTime` / `calculateCronInterval`。
 * 差异：Operit 只认每天的 0 点、每 N 小时、每 N 分钟三种写法并用「间隔近似」代替真 cron；
 * 这里换成真正的五字段 cron 求值（支持通配符、单值、区间、步长与列表），
 * 因为插件模板里的 cron 表达是作者手写的，近似成固定间隔会让「每天 8 点」之类跑偏。
 */
object WorkflowTriggers {

    const val TYPE_INTERVAL = "interval"
    const val TYPE_SPECIFIC_TIME = "specific_time"
    const val TYPE_CRON = "cron"

    const val KEY_SCHEDULE_TYPE = "schedule_type"
    const val KEY_INTERVAL_MS = "interval_ms"
    const val KEY_INTERVAL_MINUTES = "interval_minutes"
    const val KEY_SPECIFIC_TIME = "specific_time"
    const val KEY_CRON_EXPRESSION = "cron_expression"
    const val KEY_REPEAT = "repeat"
    const val KEY_ENABLED = "enabled"

    /** 没有配置时保留的兜底间隔，避免模板漏填时变成「每分钟一次」。 */
    private const val DEFAULT_INTERVAL_MINUTES = 60L

    fun scheduleTrigger(workflow: Workflow): TriggerNode? =
        workflow.nodes.filterIsInstance<TriggerNode>().firstOrNull { it.triggerType == "schedule" }

    fun scheduleType(trigger: TriggerNode): String? =
        trigger.triggerConfig[KEY_SCHEDULE_TYPE]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isEnabled(trigger: TriggerNode): Boolean =
        parseBoolean(trigger.triggerConfig[KEY_ENABLED]) ?: true

    fun repeats(trigger: TriggerNode): Boolean =
        parseBoolean(trigger.triggerConfig[KEY_REPEAT]) ?: true

    fun intervalMillis(trigger: TriggerNode): Long {
        val config = trigger.triggerConfig
        config[KEY_INTERVAL_MS]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        config[KEY_INTERVAL_MINUTES]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 60_000L }
        return DEFAULT_INTERVAL_MINUTES * 60_000L
    }

    /** 下一次到期时刻；无法解析（缺 cron 表达式、时间格式不对）返回 null。 */
    fun nextRunAt(trigger: TriggerNode, after: Long): Long? = when (scheduleType(trigger)) {
        TYPE_INTERVAL -> after + intervalMillis(trigger)
        TYPE_SPECIFIC_TIME -> nextSpecificTime(trigger, after)
        TYPE_CRON -> {
            val expression = trigger.triggerConfig[KEY_CRON_EXPRESSION]?.trim()
            if (expression.isNullOrEmpty()) null else CronExpression.next(expression, after)
        }
        else -> null
    }

    /** 给宿主 API / 排障用的可读描述。 */
    fun describe(trigger: TriggerNode): String = when (scheduleType(trigger)) {
        TYPE_INTERVAL -> "每 ${intervalMillis(trigger) / 60_000L} 分钟"
        TYPE_SPECIFIC_TIME -> "指定时间 ${trigger.triggerConfig[KEY_SPECIFIC_TIME].orEmpty()}"
        TYPE_CRON -> "cron ${trigger.triggerConfig[KEY_CRON_EXPRESSION].orEmpty()}"
        else -> "未配置调度"
    }

    private fun nextSpecificTime(trigger: TriggerNode, after: Long): Long? {
        val raw = trigger.triggerConfig[KEY_SPECIFIC_TIME]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val target = parseDateTime(raw) ?: return null
        if (target > after) return target
        // 只跑一次的目标时刻已过：不再排（否则下一轮会拿一个过去的时刻去跑）。
        if (!repeats(trigger)) return null
        // 重复的「指定时间」按天顺延到最近的一个未来时刻；直接算天数差，
        // 不用逐日累加——目标时刻可能是几年前，逐日循环要跑上千次。
        val dayMillis = 24 * 60 * 60 * 1000L
        val daysToAdd = (after - target) / dayMillis + 1
        return target + daysToAdd * dayMillis
    }

    private fun parseDateTime(value: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
        )
        for (format in formats) {
            val parsed = runCatching {
                SimpleDateFormat(format, Locale.getDefault()).apply { isLenient = false }.parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun parseBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}

/**
 * 五字段 cron（分 时 日 月 周）求值，只做「下一次是什么时候」。
 *
 * 不引第三方 cron 库：本仓不新增依赖，而工作流需要的只是「下一个触发点」，
 * 不需要守护进程式的逐分钟调度。
 */
internal object CronExpression {

    private const val MAX_DAY_LOOKAHEAD = 366

    fun next(expression: String, after: Long): Long? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size < 5) return null
        val minutes = parseField(fields[0], 0, 59)?.sorted() ?: return null
        val hours = parseField(fields[1], 0, 23)?.sorted() ?: return null
        val daysOfMonth = parseField(fields[2], 1, 31) ?: return null
        val months = parseField(fields[3], 1, 12)?.toSet() ?: return null
        val daysOfWeek = parseField(fields[4], 0, 7)?.map(::toCalendarDayOfWeek)?.toSet() ?: return null

        val domRestricted = fields[2].trim() !in setOf("*", "?")
        val dowRestricted = fields[4].trim() !in setOf("*", "?")

        val base = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (dayOffset in 0..MAX_DAY_LOOKAHEAD) {
            val day = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
            if (day.get(Calendar.MONTH) + 1 !in months) continue
            val domMatch = day.get(Calendar.DAY_OF_MONTH) in daysOfMonth
            val dowMatch = day.get(Calendar.DAY_OF_WEEK) in daysOfWeek
            val dayMatches = when {
                domRestricted && dowRestricted -> domMatch || dowMatch
                domRestricted -> domMatch
                dowRestricted -> dowMatch
                else -> true
            }
            if (!dayMatches) continue
            for (hour in hours) {
                for (minute in minutes) {
                    val candidate = (day.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (candidate.timeInMillis > after) return candidate.timeInMillis
                }
            }
        }
        return null
    }

    /** cron 的 0/7 都表示周日，其余加一即 Calendar 的 1..7。 */
    private fun toCalendarDayOfWeek(value: Int): Int = when (value) {
        0, 7 -> Calendar.SUNDAY
        else -> value + 1
    }

    /** 解析单个字段；无法解析返回 null（调用方当成「表达式非法」）。 */
    private fun parseField(field: String, min: Int, max: Int): List<Int>? {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "*" || trimmed == "?") return (min..max).toList()
        val values = sortedSetOf<Int>()
        for (part in trimmed.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) return null
            val stepSplit = token.split('/')
            if (stepSplit.size > 2) return null
            val step = if (stepSplit.size == 2) {
                stepSplit[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            } else {
                1
            }
            val rangePart = stepSplit[0]
            val range = when {
                rangePart == "*" -> min..max
                rangePart.contains('-') -> {
                    val bounds = rangePart.split('-')
                    if (bounds.size != 2) return null
                    val start = bounds[0].toIntOrNull() ?: return null
                    val end = bounds[1].toIntOrNull() ?: return null
                    if (start > end) return null
                    start..end
                }
                else -> {
                    val single = rangePart.toIntOrNull() ?: return null
                    single..single
                }
            }
            var value = range.first
            while (value <= range.last) {
                if (value in min..max) values.add(value)
                value += step
            }
        }
        return if (values.isEmpty()) null else values.toList()
    }
}
