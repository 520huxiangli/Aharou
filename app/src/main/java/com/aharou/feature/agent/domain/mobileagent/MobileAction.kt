package com.aharou.feature.agent.domain.mobileagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/**
 * 一步的下场：要么落到设备上执行、要么是循环的终点或暂停点。
 *
 * 模型交来的坐标是 **0..1000 归一化坐标**（见 [MobileCoordinates]），只在真正下发动作前换算成
 * 屏幕像素；换算结果记在 [PlannedAction.Device.args] 里，原始归一化参数留在 [MobileStep] 上供 trace。
 */
sealed interface PlannedAction {
    /** 直接下发给设备（VdTool 影子屏参数）。 */
    data class Device(val args: Map<String, JsonElement>, val label: String) : PlannedAction

    /** 什么都不做，只是等一会儿（循环内部 delay，不占设备）。 */
    data class Wait(val seconds: Double) : PlannedAction

    /** 按名称打开 App；由循环把名称解析成包名后再下发 launch。 */
    data class Open(val app: String) : PlannedAction

    /** 终局：answer / terminate / ask_user。status ∈ done / failed / ask。 */
    data class Finish(val status: String, val text: String) : PlannedAction

    /** 这一步不可用（缺参数、动作不认识），让循环把它记一笔并让模型改变做法。 */
    data class Invalid(val reason: String) : PlannedAction
}

/**
 * 0..1000 归一化坐标空间：0 = 屏幕左/上边缘，1000 = 右/下边缘，(0,0) 左上角。
 *
 * 只有屏幕像素尺寸知道时才换算得动，否则整步按无效处理（给模型一句能改的说明）。
 * 越界**直接拒绝**而不是夹到边界：模型把像素当归一化值填进来时必须被拦下，不能默默点偏。
 */
object MobileCoordinates {
    const val SPACE = 1000

    /** 归一化值 → 像素；越界或尺寸未知返回 null。 */
    fun toPixels(value: Double, dimension: Int): Int? {
        if (dimension <= 0) return null
        if (value < 0.0 || value > SPACE.toDouble()) return null
        return (value / SPACE * dimension).roundToInt().coerceIn(0, dimension - 1)
    }
}

/**
 * `mobile_act` 参数 → [PlannedAction] 的映射，以及空转判定（两个动作算不算「同一个」）。
 */
object MobileAction {

    /** 两处点按落在这个像素距离内即算同一次（约一根手指宽，与 nanoMuse 的 24/999 对齐）。 */
    const val SAME_TAP_PX = 24f

    /** 系统键：模型说的 key → vscreen 的 keycode。 */
    private val KEYCODES = mapOf("back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH")

    /**
     * 把一步换算成要下发的动作。坐标参数是 0..1000 归一化值，按 [screenWidth] / [screenHeight]
     * 换算成像素后才交给手；尺寸未知或坐标越界则整步无效（不夹到边界，免得默默点偏）。
     */
    fun toPlan(step: MobileStep, screenWidth: Int, screenHeight: Int): PlannedAction {
        val args = step.arguments
        fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun num(key: String): Double? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
        val label = (args["label"] as? JsonPrimitive)?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() }
            ?: step.action

        /** 单个归一化坐标 → 像素；出错时带着能直接翻给模型的说明。 */
        fun coord(key: String, dimension: Int): Pair<Int?, String?> {
            val raw = num(key) ?: return null to "$key 需要一个 0..1000 的数字（归一化坐标）"
            if (dimension <= 0) return null to "拿不到屏幕像素尺寸，无法把 $key 换算成像素坐标"
            val value = MobileCoordinates.toPixels(raw, dimension)
                ?: return null to "$key=$raw 超出 0..1000：坐标要用归一化值（像素 ÷ 屏幕宽/高 × 1000），不是像素"
            return value to null
        }

        /** 一次算多个坐标；任一个出错就整步无效，避免半对的点按。 */
        fun coords(vararg keys: Pair<String, Int>): Pair<Map<String, Int>?, String?> {
            val out = LinkedHashMap<String, Int>(keys.size)
            for ((key, dimension) in keys) {
                val (value, error) = coord(key, dimension)
                if (value == null) return null to error
                out[key] = value
            }
            return out to null
        }

        return when (step.kind) {
            "click" -> {
                val (xy, error) = coords("x" to screenWidth, "y" to screenHeight)
                if (xy == null) PlannedAction.Invalid(error ?: "click 需要 x / y")
                else PlannedAction.Device(
                    mapOf(
                        "action" to JsonPrimitive("tap"),
                        "x" to JsonPrimitive(xy.getValue("x")), "y" to JsonPrimitive(xy.getValue("y")),
                        "label" to JsonPrimitive(label),
                    ),
                    label,
                )
            }

            "long_press" -> {
                val (xy, error) = coords("x" to screenWidth, "y" to screenHeight)
                if (xy == null) {
                    PlannedAction.Invalid(error ?: "long_press 需要 x / y")
                } else {
                    // 影子屏没有独立的长按接口，用同点滑动 + 时长模拟（runner 会按下并保持 duration）。
                    val x = xy.getValue("x")
                    val y = xy.getValue("y")
                    PlannedAction.Device(
                        buildMap {
                            put("action", JsonPrimitive("swipe"))
                            put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
                            put("x2", JsonPrimitive(x)); put("y2", JsonPrimitive(y))
                            put("duration", JsonPrimitive(seconds(num("time"), 1.0, 5.0).times(1000).toInt()))
                            put("label", JsonPrimitive(label))
                        },
                        label,
                    )
                }
            }

            "swipe" -> {
                val (xy, error) = coords(
                    "x" to screenWidth, "y" to screenHeight,
                    "x2" to screenWidth, "y2" to screenHeight,
                )
                if (xy == null) {
                    PlannedAction.Invalid(error ?: "swipe 需要 x / y / x2 / y2")
                } else {
                    PlannedAction.Device(
                        buildMap {
                            put("action", JsonPrimitive("swipe"))
                            put("x", JsonPrimitive(xy.getValue("x"))); put("y", JsonPrimitive(xy.getValue("y")))
                            put("x2", JsonPrimitive(xy.getValue("x2"))); put("y2", JsonPrimitive(xy.getValue("y2")))
                            put("duration", JsonPrimitive(300))
                            put("label", JsonPrimitive(label))
                        },
                        label,
                    )
                }
            }

            "type" -> {
                val text = str("text")
                if (text.isEmpty()) PlannedAction.Invalid("type 需要 text")
                else PlannedAction.Device(
                    mapOf("action" to JsonPrimitive("type"), "text" to JsonPrimitive(text), "label" to JsonPrimitive(label)),
                    label,
                )
            }

            "key" -> {
                val code = KEYCODES[str("key").lowercase()]
                if (code == null) PlannedAction.Invalid("key 只支持 back / home / recents")
                else PlannedAction.Device(
                    mapOf("action" to JsonPrimitive("key"), "keycode" to JsonPrimitive(code), "label" to JsonPrimitive(label)),
                    label,
                )
            }

            "open" -> {
                val app = str("text").ifEmpty { str("app") }
                if (app.isEmpty()) PlannedAction.Invalid("open 需要 App 名称（text）") else PlannedAction.Open(app)
            }

            "wait" -> PlannedAction.Wait(seconds(num("time"), 2.0, 10.0))

            "answer" -> PlannedAction.Finish("done", str("text").ifEmpty { step.thought })
            "terminate" -> {
                val ok = str("status").ifEmpty { "success" }.equals("success", ignoreCase = true)
                PlannedAction.Finish(if (ok) "done" else "failed", str("text").ifEmpty { step.thought })
            }
            "ask_user" -> PlannedAction.Finish("ask", str("text").ifEmpty { step.thought })

            else -> PlannedAction.Invalid("不认识的 action：${step.kind.ifEmpty { "（空）" }}")
        }
    }

    /**
     * 两个设备动作算不算「同一个」：动作种类 + 携带内容，坐标给 [SAME_TAP_PX] 容差，忽略 label。
     * 模型点一个点不动的东西时会每次换一句 Action 句、坐标差几像素，这里仍应认成同一次点按。
     * 传进来的应是**下发参数**（已换算成像素），所以容差按像素算。
     */
    fun sameAction(a: Map<String, JsonElement>, b: Map<String, JsonElement>): Boolean {
        fun s(m: Map<String, JsonElement>, k: String) = (m[k] as? JsonPrimitive)?.contentOrNull
        if (s(a, "action") != s(b, "action")) return false
        for (key in listOf("text", "keycode", "component")) {
            if (s(a, key) != s(b, key)) return false
        }
        for (key in listOf("x", "y", "x2", "y2")) {
            val va = s(a, key)?.toDoubleOrNull()
            val vb = s(b, key)?.toDoubleOrNull()
            if (va != null && vb != null) {
                if (kotlin.math.abs(va - vb) > SAME_TAP_PX) return false
            } else if (s(a, key) != s(b, key)) {
                return false
            }
        }
        return true
    }

    private fun seconds(value: Double?, default: Double, cap: Double): Double =
        (value ?: default).coerceIn(0.2, cap)
}
