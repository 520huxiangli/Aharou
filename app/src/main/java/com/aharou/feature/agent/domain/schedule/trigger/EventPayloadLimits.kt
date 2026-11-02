package com.aharou.feature.agent.domain.schedule.trigger

import kotlin.math.ceil
import kotlin.math.max

/**
 * 事件载荷的硬上限。收敛统一发生在 [EventTriggerBus] 的投递收口（`dispatch`），
 * 不在这里改 [EventPayload] 本身——载荷定义是「拿到就有、没有就空」的纯数据，
 * 上限是投递侧的策略，两者分开，别的消费方（测试、渲染）不受影响。
 *
 * 为什么必须收敛：上游是系统通知回调，正文没有任何长度约束。合并型通知（聊天汇总、邮件摘要）
 * 实测能到几 KB，整段拼进提示词既吃 token，又会把真正决定「要不要干活」的那句话推得很远。
 *
 * 为什么截断而不是丢弃整条：一次事件里「谁发的、说了什么」哪怕只留开头也有信息量；
 * 丢掉整条等于让任务凭空不跑，比截断糟得多。截断处留标记，读提示词的一方看得出来少了内容。
 */
object EventPayloadLimits {

    /** 合法 Android 包名远短于此（`a.b.c` 形态，每段上限 63），纯防御。 */
    const val PACKAGE_MAX_CHARS = 128

    /** 常见通知标题不到 100 字符，留一倍余量。 */
    const val TITLE_MAX_CHARS = 200

    /** 800 字符约 400 汉字，够回答「谁发了什么」；再多对决策没有增量信息。 */
    const val TEXT_MAX_CHARS = 800

    /**
     * 整条载荷序列化后的 UTF-8 字节上限。
     *
     * 按字节而不是字符：汉字/表情各占 3~4 字节，真正进提示词的量是字节数。
     * 取 3072 是让上面三条字符上限先起作用（800 汉字正文约 2.4 KB + 200 汉字标题约 0.6 KB
     * 正好是这个量级），总上限只兜底满屏表情这类四字节字符把体积撑大的极端情况。
     */
    const val TOTAL_JSON_MAX_BYTES = 3_072

    /** 总上限触发时的字段下限：连这几十个字都不剩，截断后已看不出「谁发的」。 */
    const val MIN_FIELD_CHARS = 32

    /** 截断标记。写上「已截断」而不只是省略号，避免模型把残缺的正文当完整消息去理解。 */
    const val TRUNCATION_MARK = "…（已截断）"
}

/**
 * 把载荷收敛到 [EventPayloadLimits] 之内：先按字段上限截，再按整条 JSON 的字节上限
 * 反复截当前最长的字段，直到放得进或所有字段都到了下限。
 */
fun EventPayload.limited(): EventPayload {
    val appField = PayloadField(app, EventPayloadLimits.PACKAGE_MAX_CHARS)
    val titleField = PayloadField(title, EventPayloadLimits.TITLE_MAX_CHARS)
    val textField = PayloadField(text, EventPayloadLimits.TEXT_MAX_CHARS)
    val fields = listOf(appField, titleField, textField)

    while (true) {
        val size = jsonByteSize(
            appField.rendered(), titleField.rendered(), textField.rendered(),
            batteryPercent, ssid, timeMillis,
        )
        if (size <= EventPayloadLimits.TOTAL_JSON_MAX_BYTES) break
        val excess = size - EventPayloadLimits.TOTAL_JSON_MAX_BYTES
        // 先砍最长的：长度越接近上限，越可能是「没有增量信息」的那一段。
        val longest = fields
            .filter { it.canShrink() }
            .maxByOrNull { it.rendered().length }
            ?: break
        longest.shrink(excess)
    }

    return EventPayload(
        app = appField.rendered(),
        title = titleField.rendered(),
        text = textField.rendered(),
        batteryPercent = batteryPercent,
        ssid = ssid,
        timeMillis = timeMillis,
    )
}

/** 一个可截断的字段：正文与「是否被截过」分开存，标记只在渲染时加，免得它被反复算进长度。 */
private class PayloadField(initial: String, maxChars: Int) {

    var value: String = if (initial.length <= maxChars) {
        initial
    } else {
        takeSafely(initial, (maxChars - EventPayloadLimits.TRUNCATION_MARK.length).coerceAtLeast(1))
    }
        private set

    private var cut: Boolean = initial.length > maxChars

    fun rendered(): String = if (cut) value + EventPayloadLimits.TRUNCATION_MARK else value

    /**
     * 是否还能再截。判断的是 [value] 而不是 [rendered]：渲染后的长度含截断标记，
     * 拿它跟字符下限比，会把已经砍到底的字段误判成「还能砍」，循环永远不收敛（曾是死循环）。
     */
    fun canShrink(): Boolean = value.length > EventPayloadLimits.MIN_FIELD_CHARS

    /** 至少砍掉一个字，保证 [limited] 的循环会收敛；已到下限就不再动。 */
    fun shrink(excessBytes: Int) {
        if (value.length <= EventPayloadLimits.MIN_FIELD_CHARS) return
        // 按该字段的平均字节数折算成字符数：直接按字节砍字符，中文/表情会被过度截断。
        val bytesPerChar = max(1.0, value.toByteArray(Charsets.UTF_8).size.toDouble() / value.length)
        val cutChars = ceil(excessBytes / bytesPerChar).toInt().coerceAtLeast(1)
        val keep = (value.length - cutChars).coerceAtLeast(EventPayloadLimits.MIN_FIELD_CHARS)
        value = takeSafely(value, keep)
        cut = true
    }
}

/** 按字符截断，但不把代理对（表情符号）劈成两半——劈开后渲染出来是乱码方块。 */
private fun takeSafely(value: String, keep: Int): String {
    val count = keep.coerceIn(0, value.length)
    if (count == 0) return ""
    return if (Character.isHighSurrogate(value[count - 1])) {
        value.substring(0, count - 1)
    } else {
        value.substring(0, count)
    }
}

/**
 * 载荷序列化后的字节数。字段顺序与 [EventPayload] 的声明顺序一致，只用于量体积，
 * 不对外输出 JSON，所以只转义会破坏结构的反斜杠与引号（控制字符极少见，误差可忽略）。
 */
private fun jsonByteSize(
    app: String,
    title: String,
    text: String,
    batteryPercent: Int?,
    ssid: String,
    timeMillis: Long,
): Int {
    val json = buildString(64) {
        append("{\"app\":\"").append(escaped(app))
        append("\",\"title\":\"").append(escaped(title))
        append("\",\"text\":\"").append(escaped(text))
        append("\",\"batteryPercent\":").append(batteryPercent)
        append(",\"ssid\":\"").append(escaped(ssid))
        append("\",\"timeMillis\":").append(timeMillis)
        append('}')
    }
    return json.toByteArray(Charsets.UTF_8).size
}

private fun escaped(value: String): String {
    if (value.none { it == '\\' || it == '"' || it < ' ' }) return value
    val builder = StringBuilder(value.length + 8)
    value.forEach { c ->
        when {
            c == '\\' -> builder.append("\\\\")
            c == '"' -> builder.append("\\\"")
            c < ' ' -> builder.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> builder.append(c)
        }
    }
    return builder.toString()
}
