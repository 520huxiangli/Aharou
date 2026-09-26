package com.aharou.feature.agent.domain.shizuku

import android.os.Bundle
import com.aharou.feature.agent.domain.container.BoundedOutput
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：由 Shizuku 在 shell（uid 2000）进程中实例化，代 App 执行 shell 命令。
 *
 * 本进程不是合法的 Android 应用进程，只能用 Java/系统级 API，不能用 Context 相关能力
 * （getContentResolver / registerReceiver 等），故实现只用 [ProcessBuilder]。
 *
 * 类名与构造器会被 Shizuku 反射加载，不可被 R8 混淆（见 proguard-rules.pro）。
 */
class ShizukuShellService : IShizukuShellService.Stub() {

    /** Shizuku 保留的销毁入口：unbindUserService(remove=true) 时触发，结束本进程。 */
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            // 输出读取与等待结束必须并行：管道写满会阻塞子进程，先 waitFor 再读会死锁。
            // 同时限幅：Binder 事务缓冲上限 1MB，超大输出直接回传会抛 TransactionTooLargeException。
            val output = BoundedOutput()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.append(line)
                            output.append("\n")
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            result.putString(KEY_OUTPUT, output.build())
            result.putInt(KEY_EXIT_CODE, if (finished) process.exitValue() else EXIT_TIMEOUT)
        } catch (e: Exception) {
            result.putString(KEY_OUTPUT, e.message ?: "执行失败")
            result.putInt(KEY_EXIT_CODE, EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
        return result
    }

    companion object {
        const val KEY_OUTPUT = "output"
        const val KEY_EXIT_CODE = "exitCode"

        /** 命令超时（进程被强杀）时的退出码。 */
        const val EXIT_TIMEOUT = -1000

        /** 启动/读取异常时的退出码。 */
        const val EXIT_FAILURE = -1

        private const val READER_JOIN_TIMEOUT_MS = 2_000L
    }
}
