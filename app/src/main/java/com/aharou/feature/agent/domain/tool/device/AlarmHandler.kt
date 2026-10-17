package com.aharou.feature.agent.domain.tool.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 系统闹钟与计时器。
 *
 * 走 [AlarmClock] 的标准 intent 交给设备自带的时钟应用去建，不申请任何权限，
 * 也不自己持有调度——这样建出来的闹钟与用户手动设的完全等价：杀进程照样响、
 * 省电模式下照样响（这一点是 WorkManager 那套后台唤醒做不到的）。
 */
@Singleton
class AlarmHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "AlarmHandler"
    }

    suspend fun setAlarm(hour: Int, minutes: Int, message: String?, skipUi: Boolean): ToolResult {
        if (hour !in 0..23 || minutes !in 0..59) {
            return ToolResult.Error("时间不合法：hour 需 0-23、minutes 需 0-59", "INVALID_TIME")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            message?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch("闹钟", intent, buildJsonObject {
            put("message", "已请系统时钟在 %02d:%02d 建闹钟".format(hour, minutes))
            put("hour", hour)
            put("minutes", minutes)
        })
    }

    suspend fun setTimer(seconds: Int, message: String?, skipUi: Boolean): ToolResult {
        if (seconds <= 0) return ToolResult.Error("seconds 必须大于 0", "INVALID_DURATION")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            message?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch("计时器", intent, buildJsonObject {
            put("message", "已请系统时钟建 $seconds 秒计时器")
            put("seconds", seconds)
        })
    }

    private suspend fun launch(what: String, intent: Intent, success: kotlinx.serialization.json.JsonObject): ToolResult {
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Error("这台设备上没有能建${what}的应用（系统时钟缺失）", "NO_CLOCK_APP")
        }
        return withContext(Dispatchers.Main) {
            runCatching { context.startActivity(intent) }.fold(
                onSuccess = { ToolResult.Success(success) },
                onFailure = { e ->
                    FileLogger.e(TAG, "建立${what}失败", e)
                    // Android 10+ 禁止后台应用拉起 Activity：定时任务在后台触发时会走到这里。
                    ToolResult.Error(
                        "${what}未能建立：${e.message}。系统禁止后台应用直接打开时钟界面——" +
                            "把 Aharou 切到前台再试，或让任务改用通知提醒。",
                        "BACKGROUND_ACTIVITY_BLOCKED",
                    )
                },
            )
        }
    }
}