package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 sshj 的 [Session.Command] 包装成 [Process]，让远程也能跑 stdio 型 MCP server。
 *
 * 为什么需要它：`StdioTransport` 只认 [Process] 的三条流（stdin 写请求、stdout 读响应、stderr 收日志）。
 * 远程模式下这些流来自 SSH channel，而 sshj 没有提供 Process 实现，所以在这里做一层适配——
 * 上层的 JSON-RPC 路由、超时、重连逻辑一行都不用改。
 *
 * 语义对齐：
 * - [outputStream] 就是**写进 server stdin** 的通道（[Process] 的命名容易反直觉）；
 * - [getInputStream]/[getErrorStream] 分别是 server 的 stdout/stderr；
 * - [waitFor] 等远端进程结束；[destroy] 关闭通道由远端进程收 EOF 退出。
 *
 * 一个已知限制：SSH 的 exit-status 要等通道关闭才拿得到，所以 [exitValue] 在进程自然结束前不可用。
 */
class SshCommandProcess(
    private val session: Session,
    private val command: Session.Command,
    private val label: String
) : Process() {

    private companion object {
        const val TAG = "SshCommandProcess"
    }

    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var cachedExit: Int? = null

    override fun getOutputStream(): OutputStream = command.outputStream

    override fun getInputStream(): InputStream = command.inputStream

    override fun getErrorStream(): InputStream = command.errorStream

    override fun waitFor(): Int {
        cachedExit?.let { return it }
        // 远端进程结束后通道会关闭；join 阻塞到那一刻，再取 exit-status。
        runCatching { command.join() }
        val status = runCatching { command.exitStatus }.getOrDefault(-1)
        cachedExit = status
        return status
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        cachedExit?.let { return true }
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (System.currentTimeMillis() < deadline) {
            if (!command.isOpen) return true
            Thread.sleep(50)
        }
        return false
    }

    override fun exitValue(): Int =
        cachedExit ?: throw IllegalThreadStateException("远端进程尚未退出（SSH 的 exit-status 要等通道关闭才可得）")

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        // 先关 stdin 让远端进程收到 EOF 自己退，再关通道；顺序反了可能拿不到 exit-status。
        runCatching { command.outputStream.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        FileLogger.d(TAG, "已终止远程 stdio 进程：$label")
    }

    override fun isAlive(): Boolean = !destroyed.get() && command.isOpen
}
