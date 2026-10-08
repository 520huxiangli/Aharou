package com.aharou.feature.agent.domain.container

import com.aharou.core.util.BoundedLineReader
import com.aharou.core.util.FileLogger
import com.aharou.core.util.LINE_TRUNCATED_NOTE
import com.aharou.feature.agent.domain.container.CommandEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * [CommandEngine] 的远程 SSH 实现：用 sshj exec channel 在远程服务器上执行命令。
 *
 * 共享一个 [RemoteSshConnection]（持有 sshj [SSHClient]）执行命令；文件读写由 [RemoteSftpFileAccess]
 * 走另一条独立的 SFTP transport（见 [RemoteSshConnection.sftp]），两者隔离互不影响。
 *
 * 与 [LinuxContainerEngine] 的语义对应：
 * - [ensureInstalled]：建立/维持 SSH 连接（对应本地解压 rootfs）；
 * - [isContainerInstalled]：SSH 连接是否存活（对应本地 rootfs 是否就绪）；
 * - [isProvisioned]：恒 true（远程工具由用户自行保证，对应本地 apk 装包完成）；
 * - [defaultShell]：/bin/bash（远程服务器通常有 bash）。
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = channelFlow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程流式) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            if (session.isOpen) {
                timedOut.set(true)
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && session.isOpen) {
                FileLogger.i(TAG, "命令被取消，关闭 session: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        try {
            // stdout 与 stderr 并发逐行 emit：本地引擎 redirectErrorStream(true) 是合并语义，
            // 远程 exec 通道两路分离，不读 stderr 会丢掉 `command not found`、编译错误、--progress 等。
            pumpMergedOutput(session) { send(CommandEvent.Line(it)) }
            // sshj 的 exitStatus 在流 EOF 后未必就绪，close 后才保证有值（与同步路径一致）
            runCatching { session.close() }
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                send(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                send(CommandEvent.Exit(null))
            } else {
                when {
                    exitCode == null -> FileLogger.w(TAG, "命令未收到退出码: ${sanitizeCommandForLog(command)}")
                    exitCode != 0 -> FileLogger.w(TAG, "命令退出码=$exitCode: ${sanitizeCommandForLog(command)}")
                    else -> FileLogger.v(TAG, "命令完成(退出码 0): ${sanitizeCommandForLog(command)}")
                }
                send(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchdog.cancel()
            if (timedOut.get()) {
                send(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                send(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): ${sanitizeCommandForLog(command)}", e)
                send(CommandEvent.Line("[命令执行异常：${e.message}]"))
                send(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!isContainerInstalled()) return null
        return runCatching { execCaptured(command, projectPath, timeoutMs) }
            .getOrElse {
                FileLogger.w(TAG, "远程命令执行失败(连接可能已断): ${sanitizeCommandForLog(command)}", it)
                null
            }
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs, unbounded = true)
    }

    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程同步) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        var exitCode: Int? = null
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (session.isOpen) {
                        FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                        runCatching { session.close() }
                    }
                }
                try {
                    // 并发读 stdout/stderr 并按行合并（共用 pumpMergedOutput，与流式路径一致）
                    pumpMergedOutput(session) { line -> output.append("$line\n") }
                } finally {
                    watchdog.cancel()
                }
                // sshj 的 exitStatus 在流 EOF 后未必就绪，close 后才保证有值
                runCatching { session.close() }
                exitCode = session.exitStatus
            }
        } finally {
            runCatching { session.close() }
        }
        FileLogger.v(TAG, "命令完成(远程, 退出码 $exitCode，输出 ${output.totalChars} 字符): ${sanitizeCommandForLog(command)}")
        CommandResult(output.build(), exitCode, output.truncated)
    }

    /**
     * 并发读取 [session] 的 stdout 与 stderr，逐行回调 [onLine]（已处理超长行截断）。
     * 本地引擎用 redirectErrorStream(true) 合并两路输出；远程 exec 通道二者分离，
     * 不并发读会把 `command not found`、编译错误、`git clone --progress` 等 stderr 输出静默丢弃。
     * 回调可能从两个协程并发触发，调用方自行保证线程安全（[BoundedOutput.append] 已同步）。
     */
    private suspend fun pumpMergedOutput(
        session: Session.Command,
        onLine: suspend (String) -> Unit
    ) = coroutineScope {
        suspend fun pump(stream: InputStream) {
            val reader = BoundedLineReader(InputStreamReader(stream))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                }
            } finally {
                runCatching { reader.close() }
            }
        }
        val stdout = launch { pump(session.inputStream) }
        val stderr = launch { pump(session.errorStream) }
        stdout.join()
        stderr.join()
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        if (connection.isConnected()) {
            _initProgress.value = ContainerInitState.Ready
            return@withLock
        }
        _initProgress.value = ContainerInitState.InstallingPackages(line = "正在连接 SSH 服务器…")
        try {
            connection.connect()
            _initProgress.value = ContainerInitState.Ready
        } catch (e: Exception) {
            FileLogger.e(TAG, "SSH 连接失败", e)
            val friendly = friendlySshError(e)
            _initProgress.value = ContainerInitState.Failed(friendly)
            throw RuntimeException(friendly, e)
        }
    }

    /** 拼接 cd 到 projectPath 再执行 command 的完整命令；projectPath 为 null 则直接执行。
     *  优先 cd 到 ~/workspace（符号链接），让 AI 执行 pwd 时看到 ~/workspace 而非真实路径。
     *  ~/workspace 不存在（符号链接未建成）时 fallback 到 projectPath。
     *  注入 GIT_CONFIG_GLOBAL 指向 App 管理的 ~/.aharou/gitconfig：
     *  仅当用户开启「自动注入」时该文件存在（含 include 用户全局配置 + includeIf 限定工作区根），
     *  文件不存在时 git 静默跳过——不影响用户在服务器上手动 git。 */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        val prefix = "export GIT_CONFIG_GLOBAL=\"\$HOME/.aharou/gitconfig\"; "
        if (projectPath == null) return prefix + command
        // cd 失败就不要往下跑了：路径写错时在 $HOME 里执行命令，比直接报错危险得多。
        // 路径用单引号包并转义内部单引号，避免路径含特殊字符时被 shell 拆成别的参数。
        return prefix + "cd ~/workspace 2>/dev/null || cd ${shellQuote(projectPath)} 2>/dev/null || exit 1; $command"
    }

    /** 单引号包裹并转义内部的单引号（`'` → `'\''`），保证作为整体传给 shell。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
