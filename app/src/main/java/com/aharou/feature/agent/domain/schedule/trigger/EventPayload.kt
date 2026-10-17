package com.aharou.feature.agent.domain.schedule.trigger

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一次事件触发的现场：由事件总线填好，调度层把它渲染进指令。
 *
 * 字段口径统一是「拿到就有、没有就空」——开机触发没有通知标题，电量事件没有 WiFi 名。
 */
data class EventPayload(
    /** 来源应用包名（通知触发时有值）。 */
    val app: String = "",
    /** 通知标题。 */
    val title: String = "",
    /** 通知正文。 */
    val text: String = "",
    /** 电量百分比；读不到时为 null。 */
    val batteryPercent: Int? = null,
    /** 当前 WiFi 名；移动网络等取不到时为空串。 */
    val ssid: String = "",
    /** 事件发生时刻（epoch 毫秒）。 */
    val timeMillis: Long = 0L,
)

/** 占位符拿不到实际值时替换成它，免得指令里留一段空白让人误会。 */
private const val UNKNOWN_VALUE = "未知"

private const val P_APP = "{{event.app}}"
private const val P_TITLE = "{{event.title}}"
private const val P_TEXT = "{{event.text}}"
private const val P_BATTERY = "{{event.battery}}"
private const val P_SSID = "{{event.ssid}}"
private const val P_TIME = "{{event.time}}"

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 把 [prompt] 里的 `{{event.xxx}}` 占位符换成 [payload] 的实际值。
 *
 * 用字面量逐个替换而不是正则：占位符就固定这几个，而替换进来的文本（通知正文等）
 * 可能含 `$`、`\` 这类正则元字符，走正则得处处转义。
 */
fun renderPrompt(prompt: String, payload: EventPayload): String {
    if (prompt.isEmpty()) return prompt
    return prompt
        .replace(P_APP, payload.app.ifEmpty { UNKNOWN_VALUE })
        .replace(P_TITLE, payload.title.ifEmpty { UNKNOWN_VALUE })
        .replace(P_TEXT, payload.text.ifEmpty { UNKNOWN_VALUE })
        .replace(P_BATTERY, payload.batteryPercent?.let { "${it}%" } ?: UNKNOWN_VALUE)
        .replace(P_SSID, payload.ssid.ifEmpty { UNKNOWN_VALUE })
        .replace(P_TIME, formatTime(payload.timeMillis))
}

private fun formatTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return UNKNOWN_VALUE
    return Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
}