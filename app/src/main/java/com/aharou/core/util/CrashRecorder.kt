package com.aharou.core.util

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留档：把未捕获异常的摘要写成结构化 JSON，放在 `filesDir/aharou/crash/` 下。
 *
 * 选这个目录是因为它同时被挂进容器（容器内 `/root/.aharou/crash`），
 * 于是 App 崩了以后 agent 能直接读到现场，不用再让用户手抄堆栈；
 * 崩溃页的「复制报告」也仍然可用。只保留最近 [MAX_RECORDS] 份，避免无限增长。
 */
object CrashRecorder {

    private const val DIR_NAME = "aharou"
    private const val SUB_DIR = "crash"
    private const val MAX_RECORDS = 20
    private const val MAX_STACK_CHARS = 20_000

    /**
     * 记录一次崩溃。必须在进程被 kill 之前同步调用；任何失败都静默吞掉
     *（崩溃路径上再抛异常会盖掉原始崩溃，得不偿失）。
     */
    fun record(
        context: Context,
        threadName: String,
        throwable: Throwable,
        screen: String?,
        workspaceMode: String?
    ) {
        runCatching {
            val dir = File(File(context.filesDir, DIR_NAME), SUB_DIR).apply { mkdirs() }
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stack = sw.toString().let {
                if (it.length > MAX_STACK_CHARS) it.take(MAX_STACK_CHARS) + "\n...[truncated]" else it
            }
            val now = Date()
            val json = JSONObject().apply {
                put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now))
                put("thread", threadName)
                put("exception", throwable.javaClass.name)
                put("message", throwable.message.orEmpty())
                put("screen", screen.orEmpty())
                put("workspaceMode", workspaceMode.orEmpty())
                put("version", versionName(context))
                put("stack", stack)
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
            File(dir, "crash-$stamp.json").writeText(json.toString())
            prune(dir)
        }
    }

    /** 崩溃留档目录（供导出/诊断读取）。 */
    fun crashDir(context: Context): File = File(File(context.filesDir, DIR_NAME), SUB_DIR)

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 文件名带时间戳，按名字倒序保留最新 [MAX_RECORDS] 份。 */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_RECORDS).forEach { runCatching { it.delete() } }
    }
}
