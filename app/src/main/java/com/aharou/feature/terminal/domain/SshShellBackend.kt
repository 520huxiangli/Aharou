package com.aharou.feature.terminal.domain

import com.aharou.core.util.FileLogger
import com.termux.terminal.SessionBackend
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

private const val TAG = "SshShellBackend"

/**
 * [SessionBackend] backed by an sshj interactive shell channel ([Session.Shell]).
 *
 * Used by [RemoteTerminalSessionManager] so the remote SSH shell drives the Termux
 * [com.termux.terminal.TerminalEmulator] exactly like a local pty would: shell output →
 * emulator, user input → shell stdin, resize → PTY window size change.
 *
 * The [Session.Shell] must already be started (with a PTY allocated) before being wrapped
 * here. [waitForExit] blocks on [Session.Shell.join]; remote shells have no meaningful
 * exit status, so 0 is returned (the emulator appends its own "[Process completed]" notice
 * via [com.termux.terminal.TerminalSession] once the reader thread hits EOF).
 */
class SshShellBackend(
    private val shell: Session.Shell
) : SessionBackend {

    // changeWindowDimensions 走网络 I/O，resize 会被 TerminalView 在主线程触发，需切到后台线程
    private val resizeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SshShellResize") }
    @Volatile private var closed = false

    override fun getInputStream(): InputStream = shell.inputStream

    override fun getOutputStream(): OutputStream = shell.outputStream

    override fun resize(columns: Int, rows: Int) {
        if (closed || resizeExecutor.isShutdown) return
        resizeExecutor.execute {
            runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
                .onFailure { FileLogger.w(TAG, "PTY resize 失败 ${columns}x${rows}", it) }
        }
    }

    override fun waitForExit(): Int {
        return runCatching {
            shell.join()
            0
        }.getOrElse {
            FileLogger.w(TAG, "shell.join 异常", it)
            0
        }
    }

    @Synchronized
    override fun close() {
        // 与 SubprocessBackend.close 同理：关标签与 shell 退出各调一次，重复关闭会让底层 socket 被关
        // 二次，fdsan 判「关闭不属于自己的 fd」并 SIGABRT。只放行第一次。
        if (closed) return
        closed = true
        resizeExecutor.shutdownNow()
        // shell.close() 走网络 I/O，不能在主线程同步执行
        Thread({
            runCatching { shell.close() }
                .onFailure { FileLogger.w(TAG, "shell.close 异常", it) }
        }, "SshShellClose").start()
    }
}
