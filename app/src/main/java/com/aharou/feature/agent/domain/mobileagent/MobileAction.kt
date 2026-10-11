package com.aharou.feature.agent.domain.mobileagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一步的下场：要么落到设备上执行、要么是循环的终点或暂停点。
 *
 * 坐标直接沿用模型从网格读到的输入坐标（VdTool 截图网格标注的那套，不做二次缩放）。
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
 * `mobile_act` 参数 → [PlannedAction] 的映射，以及空转判定（两个动作算不算「同一个」）。
 */
object MobileAction {

    /** 两处点按落在这个像素距离内即算同一次（约一根手指宽，与 nanoMuse 的 24/999 对齐）。 */
    const val SAME_TAP_PX = 24f

    /** 系统键：模型说的 key → vscreen 的 keycode。 */
    private val KEYCODES = mapOf("back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH")

    fun toPlan(step: MobileStep): PlannedAction {
        val args = step.arguments
        fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun num(key: String): Double? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
        fun int(key: String) = num(key)?.toInt()
        val label = (args["label"] as? JsonPrimitive)?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() }
            ?: step.action

        return when (step.kind) {
            "click" -> {
                val x = int("x"); val y = int("y")
                if (x == null || y == null) PlannedAction.Invalid("click 需要 x / y")
                else PlannedAction.Device(
                    mapOf("action" to JsonPrimitive("tap"), "x" to JsonPrimitive(x), "y" to JsonPrimitive(y), "label" to JsonPrimitive(label)),
                    label,
                )
            }

            "long_press" -> {
                val x = int("x"); val y = int("y")
                if (x == null || y == null) PlannedAction.Invalid("long_press 需要 x / y")
                // 影子屏没有独立的长按接口，用同点滑动 + 时长模拟（runner 会按下并保持 duration）。
                else PlannedAction.Device(
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

            "swipe" -> {
                val x = int("x"); val y = int("y"); val x2 = int("x2"); val y2 = int("y2")
                if (x == null || y == null || x2 == null || y2 == null) PlannedAction.Invalid("swipe 需要 x / y / x2 / y2")
                else PlannedAction.Device(
                    buildMap {
                        put("action", JsonPrimitive("swipe"))
                        put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
                        put("x2", JsonPrimitive(x2)); put("y2", JsonPrimitive(y2))
                        put("duration", JsonPrimitive(300))
                        put("label", JsonPrimitive(label))
                    },
                    label,
                )
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
