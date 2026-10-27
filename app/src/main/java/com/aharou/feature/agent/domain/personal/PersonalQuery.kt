package com.aharou.feature.agent.domain.personal

import android.database.Cursor
import com.aharou.feature.agent.domain.tool.ToolResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 个人数据查询的统一翻页口径：limit 1..200（默认 50）、offset 只做正向跳过、
 * 结果必带 hasMore / nextOffset。
 *
 * 实现上「多取一行」来判断 hasMore，不发 COUNT 查询——短信/通话记录的真实条数可能上万，
 * 数一遍比读一页还贵，而模型真正需要的只是「还有没有下一页」。
 */
data class PersonalPage(val limit: Int, val offset: Int) {

    /** 向 provider 要的行数：比 limit 多一行，多出来的那一行只用来判断 hasMore。 */
    val probeLimit: Int get() = limit + 1

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        /** 上限卡住 offset：翻页是正向跳过，深到万级只会白烧 IO，用户真需要就缩小时间窗。 */
        const val MAX_OFFSET = 10_000

        fun of(limit: Int?, offset: Int?): PersonalPage = PersonalPage(
            limit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
            offset = (offset ?: 0).coerceIn(0, MAX_OFFSET),
        )
    }
}

/**
 * 查询时间窗。默认只查最近 7 天，最长 30 天——个人数据类型里 7 天足够回答「刚才/这周」类问题，
 * 默认口径越窄，模型顺手全量导出的风险越小。
 */
data class PersonalTimeWindow(val days: Int, val startMs: Long, val endMs: Long) {

    companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 30
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(days: Int?, now: Long = System.currentTimeMillis()): PersonalTimeWindow {
            val windowDays = (days ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
            return PersonalTimeWindow(windowDays, now - windowDays * DAY_MS, now)
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("days", days)
        put("start", formatEpoch(startMs))
        put("end", formatEpoch(endMs))
    }
}

/** 统一的结果信封：[PersonalCapability] + 分页字段 + 时间窗，让模型不必猜每个数据源各自的格式。 */
internal object PersonalResults {

    fun page(
        capability: PersonalCapability,
        source: String,
        rows: List<JsonObject>,
        page: PersonalPage,
        window: PersonalTimeWindow,
        note: String? = null,
    ): ToolResult {
        val hasMore = rows.size > page.limit
        val visible = if (hasMore) rows.subList(0, page.limit) else rows
        return ToolResult.Success(buildJsonObject {
            put("source", source)
            put("capability", capability.id)
            put("count", visible.size)
            put("limit", page.limit)
            put("offset", page.offset)
            put("hasMore", hasMore)
            put("nextOffset", if (hasMore) page.offset + page.limit else null)
            put("empty", visible.isEmpty())
            put("window", window.toJson())
            if (note != null) put("note", note)
            put("items", buildJsonArray { visible.forEach { add(it) } })
        })
    }
}

private val EPOCH_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

/** 时间统一成本地时区的可读串；`DateTimeFormatter` 不可变、线程安全（SimpleDateFormat 不是）。 */
internal fun formatEpoch(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(EPOCH_FORMATTER)

/** 列不存在或为 NULL 都返回 null：不同 ROM 的短信/通话库列集不一致，缺列不该让整次查询失败。 */
internal fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getString(index)
}

internal fun Cursor.longOrNull(column: String): Long? = stringOrNull(column)?.toLongOrNull()

internal fun Cursor.intOrNull(column: String): Int? = stringOrNull(column)?.toIntOrNull()

/** LIKE 的模式串：转义通配符，用户输的「50%」不会被当成「以 50 开头」。 */
internal fun likePattern(raw: String): String =
    "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
