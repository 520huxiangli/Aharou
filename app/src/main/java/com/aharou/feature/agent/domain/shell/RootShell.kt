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

        /**
         * 探测超时：未授权时 `su` 会一直等用户点授权框，不能无限挂住调用方。
         *
         * 给得偏宽是有原因的：首次探测时系统会弹授权框等人点，8 秒对「看到弹窗再抬手」太紧；
         * 一旦在这里判成 false，结果会被缓存住，之后就一直退回 Shizuku 通道——表现成
         * 「root 明明白给了，就是不走 root」。
         */
        const val PROBE_TIMEOUT_MS = 20_000L

        /**
         * 重探超时：走到重探说明上次判过不可用。此时要么用户刚授权（`su` 会立刻返回），
         * 要么确实没有 root，两种情况都不必再等整个首探时长。
         */
        const val REPROBE_TIMEOUT_MS = 3_000L

        /** 判定不可用后的重探间隔：短到用户不必记着手动点「重新检测」，长到不会拖慢每次工具调用。 */
        const val REPROBE_INTERVAL_MS = 60_000L

        const val MAX_TIMEOUT_MS = 1_800_000L
    }

    private val _available = MutableStateFlow<Boolean?>(null)

    /** null = 尚未探测；true / false = 最近一次探测结论。 */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val probeMutex = Mutex()

    /** 上次探测时刻，重探节流用。 */
    @Volatile
    private var lastProbeAt = 0L

    /**
     * 是否该（重新）探测。
     *
     * 判过 true 就不再探；判过 false 允许重探、但隔 [REPROBE_INTERVAL_MS] 才一次——
     * 用户刚在 root 管理器里授权完，不该逼他记得回来手动点「重新检测」；
     * 但也不能每次调用都探，root 真不可用时每次都等到超时会把工具调用整体拖慢。
     */
    fun needsReprobe(): Boolean = when (_available.value) {
        null -> true
        true -> false
        else -> System.currentTimeMillis() - lastProbeAt > REPROBE_INTERVAL_MS
    }

    /**
     * 探测 root 是否可用（`su -c id` 输出含 uid=0）。无 su 或未授权一律判不可用，不抛异常。
     *
     * 三种失败各自留痕（超时 / 输出里没有 uid=0 / 启动异常）：探测只在少数时机跑，
     * 静默判 false 会让「为什么不用 root」变成没法查的问题。
     */
    suspend fun refresh(): Boolean = probeMutex.withLock {
        // 首探给足时间（首次会弹授权框等人点）；重探说明用户可能刚授权，不必再等那么久。
        val timeout = if (_available.value == null) PROBE_TIMEOUT_MS else REPROBE_TIMEOUT_MS
        lastProbeAt = System.currentTimeMillis()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                // 与 run() 一致地关掉 stdin：有些 su 实现会等 stdin EOF，不关就得白等到超时。
                process.outputStream.close()
                val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    FileLogger.w(
                        TAG,
                        "root 探测超时（${timeout}ms）——若刚弹出授权框，点了允许再到设置里重新检测"
                    )
                    return@runCatching false
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val granted = output.contains("uid=0")
                if (!granted) {
                    FileLogger.w(TAG, "root 探测没拿到 uid=0，输出：${output.trim().take(200)}")
                }
                granted
            }.getOrElse {
                FileLogger.w(TAG, "root 探测失败：${it.javaClass.simpleName} ${it.message}")
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
