package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 打电话：拨号、接听、挂断、查当前通话状态。
 *
 * 分层很清楚，别混：
 * - **拨号**只是一条 Intent。有 CALL_PHONE 权限走 ACTION_CALL 直接呼出，没有就退成
 *   ACTION_DIAL 打开拨号盘把号码填好，由用户按拨号键。两种都不需要宿主命令通道。
 * - **接听 / 挂断**系统不给第三方应用的公开 API（MODIFY_PHONE_STATE 是系统权限），只能经
 *   root 或 Shizuku 通道发 `input keyevent`。通道不可用时如实报错，不假装成功。
 * - **状态**优先读 TelephonyManager，没权限时退回 `dumpsys telephony.registry` 解析。
 *
 * 通话内容与对方语音不在这里处理：本工具不做录音、不读通话音频——那既需要另一套权限，
 * 也超出「帮忙打电话」的范围。
 */
@Singleton
class PhoneCallTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val hostShell: HostShellManager,
) : AgentTool() {

    private companion object {
        const val TAG = "PhoneCallTool"

        const val ACT_STATE = "state"
        const val ACT_DIAL = "dial"
        const val ACT_ANSWER = "answer"
        const val ACT_HANGUP = "hangup"

        /** KEYCODE_CALL：接听。 */
        const val KEYCODE_ANSWER = 5

        /** KEYCODE_ENDCALL：挂断。 */
        const val KEYCODE_HANGUP = 6

        const val SHELL_TIMEOUT_MS = 15_000L
    }

    override val name = "phone_call"

    override val description =
        "打电话：拨号、接听、挂断、查通话状态。action 取值：" +
            "dial=拨出号码（number 必填，只接受用户明确要求的号码；有 CALL_PHONE 权限时直接呼出，" +
            "没有时打开拨号盘预填号码由用户自己按）；" +
            "answer=接听当前来电；hangup=挂断当前通话（这两个需要 root 或 Shizuku 通道，" +
            "普通权限做不到，通道不可用会明确报错）；" +
            "state=当前通话状态（idle 空闲 / ringing 响铃来电 / offhook 通话中）。" +
            "禁止拨紧急号码（110/119/120 等）与 USSD/MMI 码（*# 开头）。" +
            "挂断与接听都属于不可逆操作，做之前先确认用户的意图。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "操作：state / dial / answer / hangup", true,
            enum = listOf(ACT_STATE, ACT_DIAL, ACT_ANSWER, ACT_HANGUP),
        ),
        "number" to ToolParameter("number", ParameterType.STRING, "dial 专用：要拨的号码，可带 + 与国际区号", false),
    )

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        if (action(args) in setOf(ACT_DIAL, ACT_ANSWER, ACT_HANGUP)) {
            setOf(ToolCapability.MODIFY_DEVICE_STATE)
        } else {
            capabilities
        }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = action(args)
            ?: return ToolResult.Error("缺少 action（state / dial / answer / hangup）", "MISSING_ACTION")
        return when (action) {
            ACT_STATE -> state()
            ACT_DIAL -> dial(args["number"]?.jsonPrimitive?.contentOrNull.orEmpty())
            ACT_ANSWER -> keyEvent(KEYCODE_ANSWER, "接听")
            ACT_HANGUP -> keyEvent(KEYCODE_HANGUP, "挂断")
            else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
        }
    }

    /** 当前通话状态。先问 TelephonyManager，权限不足再退回 shell。 */
    private suspend fun state(): ToolResult {
        val viaManager = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return@runCatching null
            tm.callState
        }.getOrNull()
        if (viaManager != null) {
            return ToolResult.Success(buildJsonObject {
                put("state", stateName(viaManager))
                put("raw", viaManager)
                put("source", "telephony")
            })
        }
        if (hostShell.ensureMode() == HostShellMode.UNAVAILABLE) {
            return ToolResult.Error(
                "读不到通话状态：没有 READ_PHONE_STATE 权限，宿主命令通道也不可用。" +
                    "请用户在系统设置里给本应用开「电话」权限，或授权 Shizuku/root。",
                "CALL_STATE_UNAVAILABLE",
            )
        }
        val result = runCatching { hostShell.run(BLOCKING, SHELL_TIMEOUT_MS) }
        val raw = result.getOrNull()?.output.orEmpty()
        val code = Regex("mCallState=(\\d+)").findAll(raw).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        if (code == null) {
            return ToolResult.Error("解析通话状态失败：${raw.take(120)}", "CALL_STATE_UNPARSED")
        }
        return ToolResult.Success(buildJsonObject {
            put("state", stateName(code))
            put("raw", code)
            put("source", "shell")
        })
    }

    private fun stateName(code: Int): String = when (code) {
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "unknown"
    }

    private fun dial(rawNumber: String): ToolResult {
        val number = normalize(rawNumber)
        if (number.isEmpty()) {
            return ToolResult.Error("dial 需要有效号码（number）", "MISSING_NUMBER")
        }
        if (number.startsWith("*") || number.startsWith("#")) {
            return ToolResult.Error(
                "不拨 USSD/MMI 码（$number）：这类号码会直接触发运营商操作，不属于普通通话。",
                "USSD_REJECTED",
            )
        }
        if (number in EMERGENCY_NUMBERS || number.replace("+86", "").trimStart('0') in EMERGENCY_NUMBERS) {
            return ToolResult.Error(
                "不代拨紧急号码（$number）：紧急呼叫必须由用户本人操作，避免误拨与误报。",
                "EMERGENCY_REJECTED",
            )
        }
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val uri = Uri.fromParts("tel", number, null)
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(buildJsonObject {
                put("dialed", number)
                put("auto_call", canCall)
                put(
                    "note",
                    if (canCall) {
                        "已通过系统直接呼出，接下来由用户或对方决定是否接通；不要在没确认接通前就说「通话已建立」。"
                    } else {
                        "没有 CALL_PHONE 权限，已打开拨号盘并填好号码，需要用户自己按拨号键；" +
                            "要说清楚这一点，不要报「已拨出」。"
                    },
                )
            })
        }.getOrElse { e ->
            FileLogger.e(TAG, "拨号失败：$number", e)
            ToolResult.Error("打开拨号失败：${e.message}", "DIAL_FAILED")
        }
    }

    /** 接听与挂断都靠宿主通道发按键；通道不可用就如实说做不到。 */
    private suspend fun keyEvent(keyCode: Int, label: String): ToolResult {
        if (hostShell.ensureMode() == HostShellMode.UNAVAILABLE) {
            return ToolResult.Error(
                "${label}需要 root 或 Shizuku 通道：系统不给普通应用接听/挂断的公开接口，" +
                    "连宿主命令通道也没连上，这次没有做任何操作。请用户自己接听/挂断。",
                "HOST_SHELL_UNAVAILABLE",
            )
        }
        val command = "input keyevent $keyCode"
        val result = runCatching { hostShell.run(command, SHELL_TIMEOUT_MS) }.getOrElse { e ->
            FileLogger.e(TAG, "${label}命令执行失败", e)
            return ToolResult.Error("${label}失败：${e.message}", "KEY_EVENT_FAILED")
        }
        if (result.exitCode != 0) {
            return ToolResult.Error(
                "${label}命令返回退出码 ${result.exitCode}：${result.output.take(120)}",
                "KEY_EVENT_FAILED",
            )
        }
        return ToolResult.Success(buildJsonObject {
            put("performed", label)
            put("note", "已通过宿主通道发送 ${label} 按键。是否真的生效要看通话状态（phone_call state）；" +
                "不要只凭这条结果就说已经接通或已挂断。")
        })
    }

    private fun action(args: Map<String, JsonElement>): String? =
        args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** 只保留数字与开头的 +，其余字符（空格、横线、括号）会被拨号器忽略，这里统一抹掉。 */
    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }

    private val EMERGENCY_NUMBERS = setOf("110", "119", "120", "122", "112", "911", "999")

    /** 不把 dumpsys 全量拉回来：只取通话状态那几行，避免几百 KB 输出撑爆结果。 */
    private val BLOCKING = "dumpsys telephony.registry | grep -E 'mCallState|mCallIncomingNumber' | tail -5"
}
