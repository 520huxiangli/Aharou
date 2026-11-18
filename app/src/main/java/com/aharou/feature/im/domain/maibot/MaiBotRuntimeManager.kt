package com.aharou.feature.im.domain.maibot

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.TrackedStdioProcess
import com.aharou.feature.im.data.napcat.NapCatPackageInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MaiBot 常驻进程。
 *
 * MaiBot 是机器人环境里的独立 Python 进程，与 NapCat 用同一套 rootfs 和 profile，
 * 通过 NapCat 的 OneBot 正向 WebSocket 收消息；它不依赖 App 的任何会话表。
 */
@Singleton
class MaiBotRuntimeManager @Inject constructor(
    private val engine: LinuxContainerEngine,
    private val installer: NapCatPackageInstaller,
    @param:ApplicationContext private val context: Context
) {
    data class State(val phase: String = "stopped", val error: String = "", val pid: Int? = null)

    private companion object {
        const val TAG = "MaiBotRuntime"
        const val ROOT = "/opt/aharou-napcat/maibot/MaiBot-main"
        const val EULA = "8e6e7d647f7f82d6ea98456b73908656"
        const val PRIVACY = "91e5db7659c560bc3545e63859b6ebc0"
        const val MAX_LOGS = 300
        const val CHECK_TIMEOUT_MS = 20_000L
        val ANSI = Regex("\u001B\\[[;\\d]*m")

        /**
         * 启动前的环境自检。
         *
         * 虚拟环境与解释器版本对不上时（系统 Python 换过版本，.venv 里的包目录还停留在旧版本），
         * python 能跑起来但看不到任何依赖，表现是启动后立刻在 import 阶段崩掉、退出码 1，
         * 日志里只有一行 ModuleNotFoundError，看不出根因。这里先把这种情况拦住。
         */
        val CHECK_SCRIPT = """
            cd $ROOT || { echo MAIBOT_MISSING; exit 2; }
            [ -x .venv/bin/python ] || { echo MAIBOT_MISSING; exit 3; }
            .venv/bin/python -c "import os,sys;p='.venv/lib/python{0}.{1}/site-packages'.format(*sys.version_info[:2]);sys.exit(0 if os.path.isdir(p) else 1)" || { echo MAIBOT_PYTHON_MISMATCH; exit 4; }
            .venv/bin/python -c "import structlog" 2>/dev/null || { echo MAIBOT_DEPS_MISSING; exit 5; }
            echo MAIBOT_READY
        """.trimIndent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private var handle: TrackedStdioProcess? = null
    private var watch: Job? = null

    fun isRunning(): Boolean = handle?.process?.isAlive == true

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle?.process?.isAlive == true) return@withLock
            check(installer.isEnvironmentReady()) { "机器人环境未就绪，先安装机器人环境" }
            _state.value = State(phase = "starting")
            diagnose()?.let { problem ->
                _state.value = State(phase = "error", error = problem)
                appendLog(problem)
                return@withLock
            }
            val tracked = engine.startTrackedStdioProcess(
                "/bin/bash",
                listOf("-c", "cd $ROOT && exec .venv/bin/python bot.py"),
                null,
                "maibot:runtime",
                extraEnv = mapOf("EULA_AGREE" to EULA, "PRIVACY_AGREE" to PRIVACY),
                profile = installer.profile(),
                inheritUserConfig = false,
            )
            handle = tracked
            _state.value = State(phase = "running", pid = tracked.pid)
            appendLog("MaiBot started pid=${tracked.pid}")
            listOf(tracked.process.inputStream, tracked.process.errorStream).forEach { stream ->
                scope.launch {
                    runCatching { stream.bufferedReader().useLines { lines -> lines.forEach(::appendLog) } }
                }
            }
            watch?.cancel()
            watch = scope.launch {
                val exit = tracked.process.waitFor()
                tracked.clearLedger()
                mutex.withLock {
                    if (handle === tracked) {
                        handle = null
                        _state.value = State(
                            phase = if (exit == 0) "stopped" else "error",
                            error = if (exit == 0) "" else "MaiBot 退出（退出码 $exit）",
                        )
                        appendLog("MaiBot exited code=$exit")
                    }
                }
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            watch?.cancel()
            watch = null
            val tracked = handle
            handle = null
            if (tracked == null) {
                _state.value = State(phase = "stopped")
                return@withLock
            }
            // 只 destroy 直接子进程不够：PRoot 忽略 SIGTERM，容器里的 bot.py 与插件 runner 会活下来，
            // 继续占着 host/run.lock，下次启动直接报「无法取得运行租约」而退出。
            val identity = ProcessIdentity.capture(tracked.process, tracked.pid)
            val terminated = identity != null && ProcessIdentity.terminateTree(identity)
            if (!terminated) runCatching { tracked.process.destroyForcibly() }
            tracked.clearLedger()
            _state.value = if (terminated) {
                State(phase = "stopped")
            } else {
                State(phase = "error", error = "MaiBot 未能完全退出，可能有残留进程占用运行锁，请稍后重试")
            }
            appendLog(if (terminated) "MaiBot stopped" else "MaiBot 未能完全退出")
        }
    }

    /** 返回启动前自检发现的问题；环境看起来正常、或无法判定时返回 null 交给正常启动流程。 */
    private suspend fun diagnose(): String? = withContext(Dispatchers.IO) {
        val process = runCatching {
            engine.startStdioProcess(
                "/bin/bash",
                listOf("-c", CHECK_SCRIPT),
                null,
                profile = installer.profile(),
                inheritUserConfig = false,
            )
        }.getOrNull() ?: return@withContext null
        val output = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            runInterruptible { process.inputStream.bufferedReader().use { it.readText() } }
        }
        if (output == null) {
            process.destroyForcibly()
            return@withContext null
        }
        when (output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()) {
            "MAIBOT_PYTHON_MISMATCH" ->
                "MaiBot 的 Python 环境与当前解释器版本不一致（虚拟环境已失效），请在机器人设置里重新安装"
            "MAIBOT_DEPS_MISSING" -> "MaiBot 依赖不完整，请在机器人设置里重新安装"
            "MAIBOT_MISSING" -> "MaiBot 运行文件缺失，请先在机器人设置里安装"
            else -> null
        }
    }

    private fun appendLog(line: String) {
        val clean = line.replace(ANSI, "").trimEnd()
        if (clean.isBlank()) return
        FileLogger.d(TAG, clean)
        _logs.value = (_logs.value + clean).takeLast(MAX_LOGS)
    }
}
