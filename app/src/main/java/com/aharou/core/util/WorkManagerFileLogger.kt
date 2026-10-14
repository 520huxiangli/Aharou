package com.aharou.core.util

import android.annotation.SuppressLint
import android.util.Log
import androidx.work.Logger

/**
 * 把 WorkManager 的内部日志（[Logger]）桥接到 [FileLogger]。
 *
 * 默认的 `Logger.LogcatLogger` 只在 logcat 输出，而本项目装的是 release 版，于是「Worker 从没进过
 * JobScheduler、入队又被静默吞掉」这类现场一片空白。换成这里后，WorkManager 日志与 App 其他日志
 * 写进同一份按天文件，`grep WM-` 即可筛出。
 *
 * 两点非显而易见：WorkManager 调用方已用 `Logger.tagWithPrefix` 给 tag 加了 `WM-` 前缀，这里只在
 * 缺失时补，免得出现 `WM-WM-xxx`；日志器自身抛异常会把 WorkManager 的调度流程一起带崩，故所有
 * 分支都不向上抛、消息先截断再落盘。
 */
@SuppressLint("RestrictedApi") // Logger 带 @RestrictTo(LIBRARY_GROUP)，但 setLogger 是 WorkManager 约定的自定义日志入口
internal class WorkManagerFileLogger : Logger(Log.DEBUG) {

    companion object {
        private const val TAG_PREFIX = "WM-"
        private const val MAX_MESSAGE_LENGTH = 2000

        /**
         * 安装为全局日志器。必须在进程启动早期、WorkManager 初始化之前调用：[Logger.setLogger]
         * 只在「当前还没有 logger」时生效，晚一步就会被默认的 LogcatLogger 占位。
         */
        fun install() {
            runCatching { Logger.setLogger(WorkManagerFileLogger()) }
        }
    }

    override fun verbose(tag: String, message: String) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(message)) }
    }

    override fun verbose(tag: String, message: String, throwable: Throwable) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(withThrowable(message, throwable))) }
    }

    override fun debug(tag: String, message: String) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(message)) }
    }

    override fun debug(tag: String, message: String, throwable: Throwable) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(withThrowable(message, throwable))) }
    }

    override fun info(tag: String, message: String) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(message)) }
    }

    override fun info(tag: String, message: String, throwable: Throwable) {
        safeLog { FileLogger.i(prefixedTag(tag), clip(withThrowable(message, throwable))) }
    }

    override fun warning(tag: String, message: String) {
        safeLog { FileLogger.w(prefixedTag(tag), clip(message)) }
    }

    override fun warning(tag: String, message: String, throwable: Throwable) {
        safeLog { FileLogger.w(prefixedTag(tag), clip(message), throwable) }
    }

    override fun error(tag: String, message: String) {
        safeLog { FileLogger.e(prefixedTag(tag), clip(message)) }
    }

    override fun error(tag: String, message: String, throwable: Throwable) {
        safeLog { FileLogger.e(prefixedTag(tag), clip(message), throwable) }
    }

    private fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // 绝不能冒泡给 WorkManager
        }
    }

    private fun prefixedTag(tag: String): String =
        if (tag.startsWith(TAG_PREFIX)) tag else TAG_PREFIX + tag

    private fun clip(message: String): String =
        if (message.length <= MAX_MESSAGE_LENGTH) {
            message
        } else {
            message.substring(0, MAX_MESSAGE_LENGTH) + "...(已截断)"
        }

    private fun withThrowable(message: String, throwable: Throwable): String =
        message + "\n" + throwable.stackTraceToString()
}
