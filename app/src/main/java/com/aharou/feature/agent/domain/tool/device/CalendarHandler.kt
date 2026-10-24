package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 系统日历的读取与建事件（走 CalendarContract，与用户自己的日历用的是同一份数据）。 */
@Singleton
class CalendarHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "CalendarHandler"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100

        /**
         * 时间格式器。`SimpleDateFormat` 不可跨线程共享，而本类是单例、下面两个方法都跑在
         * `Dispatchers.IO` 上——共享一个实例会输出错乱的时间串。`DateTimeFormatter` 不可变且线程安全。
         */
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        const val PERMISSION_HINT =
            "没有日历权限。到系统设置给 Aharou 开「日历」（读事件要读权限、建事件要写权限），" +
                "或用设置里的「应用权限」一键跳转。"
    }

    private fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun list(startMs: Long, endMs: Long, limitRaw: Int?): ToolResult = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_CALENDAR)) {
            return@withContext ToolResult.Error(PERMISSION_HINT, "CALENDAR_PERMISSION_MISSING")
        }
        val limit = (limitRaw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        runCatching {
            val items = mutableListOf<List<Any>>()
            context.contentResolver.query(
                uri, projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < limit) {
                    items += listOf(
                        cursor.getString(0).orEmpty(),
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getInt(3) == 1,
                        cursor.getString(4).orEmpty(),
                        cursor.getString(5).orEmpty(),
                    )
                }
            }
            items
        }.fold(
            onSuccess = { items ->
                ToolResult.Success(buildJsonObject {
                    put("count", items.size)
                    put("events", buildJsonArray {
                        items.forEach { row ->
                            add(buildJsonObject {
                                put("title", row[0].toString())
                                put("begin", formatTime(row[1] as Long))
                                put("end", formatTime(row[2] as Long))
                                put("all_day", row[3] as Boolean)
                                if (row[4].toString().isNotEmpty()) put("location", row[4].toString())
                                if (row[5].toString().isNotEmpty()) put("calendar", row[5].toString())
                            })
                        }
                    })
                })
            },
            onFailure = { e ->
                FileLogger.e(TAG, "读取日历失败", e)
                ToolResult.Error("读取日历失败：${e.message}", "CALENDAR_READ_FAILED")
            },
        )
    }

    suspend fun add(
        title: String,
        beginMs: Long,
        endMs: Long,
        location: String?,
        description: String?,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.WRITE_CALENDAR)) {
            return@withContext ToolResult.Error(PERMISSION_HINT, "CALENDAR_PERMISSION_MISSING")
        }
        if (endMs <= beginMs) {
            return@withContext ToolResult.Error("结束时间必须晚于开始时间", "INVALID_TIME_RANGE")
        }
        val calendarId = writableCalendarId()
            ?: return@withContext ToolResult.Error(
                "设备上没有可写入的日历账户：先在系统日历 App 里建一个日历（或登录一个账户）再试。",
                "NO_WRITABLE_CALENDAR",
            )
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.fold(
            onSuccess = { uri ->
                if (uri == null) {
                    ToolResult.Error("日历拒绝了这次写入", "CALENDAR_INSERT_REJECTED")
                } else {
                    ToolResult.Success(buildJsonObject {
                        put("message", "已加入日历")
                        put("title", title)
                        put("begin", formatTime(beginMs))
                        put("end", formatTime(endMs))
                        put("event_id", ContentUris.parseId(uri))
                    })
                }
            },
            onFailure = { e ->
                FileLogger.e(TAG, "写日历失败", e)
                ToolResult.Error("写日历失败：${e.message}", "CALENDAR_WRITE_FAILED")
            },
        )
    }

    /** 挑一个当前账户可贡献事件的日历：优先 primary，其次任意可写日历。 */
    private fun writableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val minAccess = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        val candidates = mutableListOf<Pair<Long, Boolean>>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val levelIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                    if (levelIdx >= 0 && cursor.getInt(levelIdx) < minAccess) continue
                    val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                    candidates += cursor.getLong(0) to (primaryIdx >= 0 && cursor.getInt(primaryIdx) == 1)
                }
            }
        }
        return candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }
}