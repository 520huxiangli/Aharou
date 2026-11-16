package com.aharou.feature.im.domain.maibot

import android.content.Context
import com.aharou.core.util.FileLogger
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        val ANSI = Regex("\u001B\\[[;\\d]*m")
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
            handle?.let { tracked ->
                runCatching { tracked.process.destroy() }
                tracked.clearLedger()
            }
            handle = null
            _state.value = State(phase = "stopped")
            appendLog("MaiBot stopped")
        }
    }

    private fun appendLog(line: String) {
        val clean = line.replace(ANSI, "").trimEnd()
        if (clean.isBlank()) return
        FileLogger.d(TAG, clean)
        _logs.value = (_logs.value + clean).takeLast(MAX_LOGS)
    }
}
