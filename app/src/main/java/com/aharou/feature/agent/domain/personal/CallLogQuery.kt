package com.aharou.feature.agent.domain.personal

import android.content.Context
import android.provider.CallLog
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通话记录只读查询（content://call_log/calls）。
 *
 * 只读、不拨号、不清记录。`number` 在部分 ROM 上会是 null（系统对某些号码做了脱敏），
 * 这种情况保留 `cachedName` 并标 `numberHidden`，不让整行消失。
 */
@Singleton
class CallLogQuery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val access: PersonalContextAccess,
    private val accessText: PersonalAccessText,
) {

    private companion object {
        const val TAG = "CallLogQuery"

        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
    }

    suspend fun query(page: PersonalPage, window: PersonalTimeWindow, text: String?): ToolResult =
        withContext(Dispatchers.IO) {
            val state = access.snapshot(PersonalCapability.CALL_LOG)
            if (!state.usable) return@withContext accessText.error(state)

            val selectionArgs = mutableListOf(window.startMs.toString(), window.endMs.toString())
            var selection = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
            val keyword = text?.trim()?.takeIf { it.isNotEmpty() }
            if (keyword != null) {
                selection += " AND (${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\'" +
                    " OR ${CallLog.Calls.CACHED_NAME} LIKE ? ESCAPE '\\')"
                val pattern = likePattern(keyword)
                selectionArgs += pattern
                selectionArgs += pattern
            }

            runCatching {
                val rows = mutableListOf<JsonObject>()
                var skipped = 0
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    PROJECTION,
                    selection,
                    selectionArgs.toTypedArray(),
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && rows.size < page.probeLimit) {
                        if (skipped < page.offset) {
                            skipped++
                            continue
                        }
                        rows += buildJsonObject {
                            put("id", cursor.longOrNull(CallLog.Calls._ID) ?: -1L)
                            val number = cursor.stringOrNull(CallLog.Calls.NUMBER)
                            put("number", number.orEmpty())
                            if (number.isNullOrEmpty()) put("numberHidden", true)
                            cursor.stringOrNull(CallLog.Calls.CACHED_NAME)
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { put("contactName", it) }
                            put("type", callType(cursor.intOrNull(CallLog.Calls.TYPE)))
                            put("date", formatEpoch(cursor.longOrNull(CallLog.Calls.DATE) ?: 0L))
                            put("durationSeconds", cursor.longOrNull(CallLog.Calls.DURATION) ?: 0L)
                        }
                    }
                }
                rows
            }.fold(
                onSuccess = { rows ->
                    PersonalResults.page(
                        capability = PersonalCapability.CALL_LOG,
                        source = "content://call_log/calls",
                        rows = rows,
                        page = page,
                        window = window,
                        note = if (rows.isEmpty()) {
                            "这段时间窗里没有通话记录。若通话 App 里确有记录，可能是该 ROM 不允许读取通话库。"
                        } else {
                            null
                        },
                    )
                },
                onFailure = { e ->
                    FileLogger.e(TAG, "查询通话记录失败", e)
                    if (e is SecurityException) {
                        // 权限刚被收走（或 ROM 额外拦截）：按「当前不可用」报，而不是把 granted 的状态贴上去。
                        accessText.error(
                            state.copy(
                                status = PersonalAccessStatus.DENIED,
                                reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
                            ),
                        )
                    } else {
                        ToolResult.Error("查询通话记录失败：${e.message}", "CALL_LOG_QUERY_FAILED")
                    }
                },
            )
        }

    private fun callType(raw: Int?): String = when (raw) {
        1 -> "incoming"
        2 -> "outgoing"
        3 -> "missed"
        4 -> "voicemail"
        5 -> "rejected"
        6 -> "blocked"
        7 -> "answered_externally"
        else -> "unknown"
    }
}
