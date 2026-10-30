package com.aharou.core.util

import android.content.Context
import android.util.Log

/**
 * 把不依赖平台的 [FileLogger] / [AILogger] 接到 Android 的存储目录与 logcat。
 *
 * 放在 :app 而不是 :core-agent 里，是因为这里要碰 Context 与 android.util.Log；
 * 内核侧只对外暴露 [FileLogger.platformLogSink] 与
 * [AILogger.infoSink] / [AILogger.errorSink] / [AILogger.isEnabled] 几个端口。
 */
fun initAndroidLogging(context: Context) {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val debuggable =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    FileLogger.platformLogSink = { level, tag, message, throwable ->
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.NONE -> Unit
        }
    }
    FileLogger.init(base, debuggable)

    AILogger.infoSink = { FileLogger.i("AILogger", it) }
    AILogger.errorSink = { message, error -> FileLogger.e("AILogger", message, error) }
    AILogger.isEnabled = { !FileLogger.isSilenced() }
    AILogger.init(base)
}
