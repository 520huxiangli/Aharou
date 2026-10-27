package com.aharou.feature.agent.domain.personal

import android.content.Context
import android.provider.Telephony
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
 * 短信库只读查询（content://sms）。
 *
 * 边界是刻意的：只读、不发送、不改已读状态、不删。正文默认截到 [MAX_BODY_CHARS] 字，
 * 避免一页 50 条长短信把上下文顶爆；截断时显式标 `bodyTruncated`。
 *
 * 权限被拒、被安装来源限制、ROM 没有 sms provider 三种情况都通过
 * [PersonalContextAccess.snapshot] 区分，返回可读指引而不是抛异常。
 */
@Singleton
class SmsQuery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val access: PersonalContextAccess,
    private val accessText: PersonalAccessText,
) {

    private companion object {
        const val TAG = "SmsQuery"
        const val MAX_BODY_CHARS = 500

        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
    }

    suspend fun query(page: PersonalPage, window: PersonalTimeWindow, text: String?): ToolResult =
        withContext(Dispatchers.IO) {
            val state = access.snapshot(PersonalCapability.SMS)
            if (!state.usable) return@withContext accessText.error(state)

            val selectionArgs = mutableListOf(window.startMs.toString(), window.endMs.toString())
            var selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
            val keyword = text?.trim()?.takeIf { it.isNotEmpty() }
            if (keyword != null) {
                selection += " AND (${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'" +
                    " OR ${Telephony.Sms.BODY} LIKE ? ESCAPE '\\')"
                val pattern = likePattern(keyword)
                selectionArgs += pattern
                selectionArgs += pattern
            }

            runCatching {
                val rows = mutableListOf<JsonObject>()
                var skipped = 0
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    PROJECTION,
                    selection,
                    selectionArgs.toTypedArray(),
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && rows.size < page.probeLimit) {
                        if (skipped < page.offset) {
                            skipped++
                            continue
                        }
                        rows += buildJsonObject {
                            put("id", cursor.longOrNull(Telephony.Sms._ID) ?: -1L)
                            put("address", cursor.stringOrNull(Telephony.Sms.ADDRESS).orEmpty())
                            put("date", formatEpoch(cursor.longOrNull(Telephony.Sms.DATE) ?: 0L))
                            put("type", smsType(cursor.intOrNull(Telephony.Sms.TYPE)))
                            put("read", (cursor.intOrNull(Telephony.Sms.READ) ?: 0) == 1)
                            val body = cursor.stringOrNull(Telephony.Sms.BODY).orEmpty()
                            put("body", body.take(MAX_BODY_CHARS))
                            if (body.length > MAX_BODY_CHARS) put("bodyTruncated", true)
                        }
                    }
                }
                rows
            }.fold(
                onSuccess = { rows ->
                    PersonalResults.page(
                        capability = PersonalCapability.SMS,
                        source = "content://sms",
                        rows = rows,
                        page = page,
                        window = window,
                        note = if (rows.isEmpty()) {
                            "这段时间窗里没有查到短信。若短信 App 里确有记录，可能是该 ROM 不允许读取短信库。"
                        } else {
                            null
                        },
                    )
                },
                onFailure = { e ->
                    FileLogger.e(TAG, "查询短信失败", e)
                    if (e is SecurityException) {
                        // 权限刚被收走（或 ROM 额外拦截）：按「当前不可用」报，而不是把 granted 的状态贴上去。
                        accessText.error(
                            state.copy(
                                status = PersonalAccessStatus.DENIED,
                                reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
                            ),
                        )
                    } else {
                        ToolResult.Error("查询短信失败：${e.message}", "SMS_QUERY_FAILED")
                    }
                },
            )
        }

    private fun smsType(raw: Int?): String = when (raw) {
        1 -> "received"
        2 -> "sent"
        3 -> "draft"
        4 -> "outbox"
        5 -> "failed"
        6 -> "queued"
        else -> "unknown"
    }
}
