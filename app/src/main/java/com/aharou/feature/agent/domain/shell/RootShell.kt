package com.aharou.feature.agent.domain.shell

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 一次宿主命令的执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class ShellCommandResult(val output: String, val exitCode: Int)

/** 命令超时未结束，进程已被强制终止。 */
const val SHELL_EXIT_TIMEOUT = -124

/** 进程未能启动（如设备上根本没有 su）。 */
const val SHELL_EXIT_START_FAILED = -125

/**
 * root 后端：以 `su -c` 执行宿主命令（uid 0）。
 *
 * 不依赖 Shizuku 服务——设备已 root 并授予本应用权限即可用，也少掉「Shizuku 重启后要重新绑定」
 * 这类不稳定因素。代价是权限面扩大到全权，因此调用方的高危确认策略必须照旧生效。
 */
@Singleton
class RootShell @Inject constructor() {
    private companion object {
        const val TAG = "RootShell"

        /** 探测超时：未授权时 `su` 会一直等用户点授权框，不能无限挂住调用方。 */
        const val PROBE_TIMEOUT_MS = 8_000L

        const val MAX_TIMEOUT_MS = 1_800_000L
    }

    private val _available = MutableStateFlow<Boolean?>(null)

    /** null = 尚未探测；true / false = 最近一次探测结论。 */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val probeMutex = Mutex()

    /** 探测 root 是否可用（`su -c id` 输出含 uid=0）。无 su 或未授权一律判不可用，不抛异常。 */
    suspend fun refresh(): Boolean = probeMutex.withLock {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching false
                }
                process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
            }.getOrElse {
                FileLogger.i(TAG, "root 探测失败：${it.javaClass.simpleName} ${it.message}")
                false
            }
        }
        _available.value = ok
        ok
    }

    /** 执行命令。超时与启动失败不抛异常，用负 [ShellCommandResult.exitCode] 表达，与 Shizuku 通道口径一致。 */
    suspend fun run(command: String, timeoutMs: Long): ShellCommandResult = coroutineScope {
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            FileLogger.w(TAG, "启动 su 失败：${it.message}")
            return@coroutineScope ShellCommandResult(it.message.orEmpty(), SHELL_EXIT_START_FAILED)
        }
        process.outputStream.close()
        // 输出可能很大，必须与 waitFor 并发读，否则管道缓冲写满会把子进程卡死。
        val pending = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = withContext(Dispatchers.IO) { process.waitFor(timeout, TimeUnit.MILLISECONDS) }
        if (!finished) {
            process.destroyForcibly()
            pending.cancel()
            return@coroutineScope ShellCommandResult("命令执行超时（${timeout}ms），已终止", SHELL_EXIT_TIMEOUT)
        }
        val output = runCatching { pending.await() }.getOrDefault("")
        ShellCommandResult(output, runCatching { process.exitValue() }.getOrDefault(SHELL_EXIT_START_FAILED))
    }
}
