package com.aharou.feature.agent.domain.tool.vdisplay

/**
 * `tap_target`：用一段描述让模型在截图上找元素，再把归一化点换算成屏幕像素。
 *
 * 与 `tap` 的 `target` 参数不同：那条路靠控件树读文字（读不到树就废），这里靠看图——
 * 图标、颜色、没有文字的按钮都能找，代价是每次调用要多走一次带图请求。
 */
internal object VdTapTarget {

    /** 系统提示：只说角色，具体任务与输出格式都在用户消息里，方便模型照着模板逐字执行。 */
    const val SYSTEM_PROMPT = "你在屏幕截图上定位界面元素，只按要求的标签回答，不要输出多余解释。"

    /** 归一化坐标的上界（相对图片宽高）。 */
    const val NORM_MAX = 1000

    /**
     * 定位提示词。要点：先让模型自己写出「它看到了什么」（<seen>），再判断是不是目标——
     * 逼它先观察再看描述，能挡掉不少「作用相近就点」的误点（把「关闭」当成「删除」）。
     */
    fun prompt(target: String, note: String? = null): String {
        val extra = note?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n补充说明：$it" }.orEmpty()
        return "这是一张屏幕截图。要找的界面元素：「$target」。$extra\n" +
            "先用一句话写出截图上最符合的那个元素实际显示的文字或图标（<seen>…</seen>），再判断它是不是要找的元素：" +
            "描述里提到的文字、颜色或图标在它身上都能对上，才算是。" +
            "是，就输出 <point>x y</point>（中心点，x、y 是相对图片宽高的 0-$NORM_MAX 归一化坐标）；" +
            "不是或截图里没有，就输出 <none/>。不要把只是作用相近的东西当成它（比如不要把「关闭」当成「删除」）。"
    }

    /** 归一化点。 */
    data class Point(val x: Int, val y: Int)

    /**
     * 解析结果：[point] 与 [none] 至多一个非空；两者都空时 [error] 说明失败原因。
     * [seen] 是模型写的「它看到了什么」，随结果回给调用方，让模型知道刚才点的到底是什么。
     */
    data class Answer(val seen: String?, val point: Point?, val none: Boolean, val error: String?) {
        val ok: Boolean get() = point != null || none
    }

    private val SEEN_RE = Regex("<seen>(.*?)</seen>", RegexOption.DOT_MATCHES_ALL)
    private val POINT_RE = Regex("<point>\\s*(-?\\d+)\\s*[,，\\s]\\s*(-?\\d+)\\s*</point>")
    private val NONE_RE = Regex("<none\\s*/?>")

    /**
     * 解析模型回答：有 `<point>` 就只看点（越界算失败，不退化成「没找到」——那会把一次
     * 格式错误悄悄变成一次不点击）；没有点再看 `<none/>`；都没有就是失败。
     */
    fun parse(raw: String): Answer {
        val seen = SEEN_RE.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val pointMatch = POINT_RE.find(raw)
        if (pointMatch != null) {
            val x = pointMatch.groupValues[1].toIntOrNull()
            val y = pointMatch.groupValues[2].toIntOrNull()
            if (x == null || y == null || x !in 0..NORM_MAX || y !in 0..NORM_MAX) {
                return Answer(
                    seen = seen,
                    point = null,
                    none = false,
                    error = "模型给的坐标不在 0..$NORM_MAX 范围内（原文：${pointMatch.value.take(60)}）",
                )
            }
            return Answer(seen, Point(x, y), none = false, error = null)
        }
        if (NONE_RE.containsMatchIn(raw)) return Answer(seen, null, none = true, error = null)
        return Answer(seen, null, none = false, error = "模型回答里既没有 <point> 也没有 <none/>")
    }

    /** 归一化点换算成屏幕像素（与 tap 同一坐标系）；图片宽高必须有效。 */
    fun toPixels(point: Point, width: Int, height: Int): Pair<Int, Int> {
        val px = (point.x.toLong() * width / NORM_MAX).toInt().coerceIn(0, width - 1)
        val py = (point.y.toLong() * height / NORM_MAX).toInt().coerceIn(0, height - 1)
        return px to py
    }
}
