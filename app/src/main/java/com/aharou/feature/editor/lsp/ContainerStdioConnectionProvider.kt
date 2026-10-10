package com.aharou.feature.editor.lsp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.TrackedStdioProcess
import com.aharou.feature.agent.domain.mcp.McpStdioChannel
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 把语言服务器跑在 Aharou 容器里，再把它的 stdin/stdout 当作 LSP 连接交给 editor-lsp。
 *
 * 进程侧复用 MCP 那套 stdio 通道（[LinuxContainerEngine.startTrackedStdioProcess] 起进程 +
 * [McpStdioChannel] 的并发名额与进程树回收，并把进程身份登记进账本，App 被杀后也能辨认回收），这样
 * 语言服务器看到的路径与编辑器一致（工作区在容器内是 `/root/workspace`），不必再写一套 PRoot 调用。
 *
 * 名额是**跨 server 的全局计数**，与 MCP 共用同一个上限；关闭或启动失败都必须归还，否则名额泄漏。
 */
class ContainerStdioConnectionProvider(
    private val serverKey: String,
    private val engine: LinuxContainerEngine,
    private val program: String,
    private val programArgs: List<String> = emptyList(),
    private val projectPath: String? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val runtimeProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE
) : StreamConnectionProvider {

    private companion object {
        const val TAG = "LspConnection"
    }

    @Volatile
    private var tracked: TrackedStdioProcess? = null

    /** 当前语言服务器进程（未启动为 null）；输入/输出流都从它取。 */
    private val process: Process?
        get() = tracked?.process

    @Volatile
    private var closed = false

    /** 名额是否由本实例占用（保证只归还一次：关闭与启动失败两条路径都会走到归还有点）。 */
    private val slotHeld = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun start() {
        if (process != null || closed) return
        try {
            McpStdioChannel.acquireSlot(serverKey)
            slotHeld.set(true)
        } catch (e: Exception) {
            // 名额已满：让 editor-lsp 把这当成一次连接失败，而不是崩出去。
            throw IOException("[$serverKey] ${e.message}", e)
        }
        FileLogger.i(TAG, "[$serverKey] 容器内启动语言服务器: $program ${programArgs.joinToString(" ")}")
        tracked = try {
            engine.startTrackedStdioProcess(
                program = program,
                programArgs = programArgs,
                projectPath = projectPath,
                tag = serverKey,
                extraEnv = extraEnv,
                profile = runtimeProfile
            )
        } catch (e: Exception) {
            releaseSlot()
            throw IOException("[$serverKey] 启动语言服务器失败: ${e.message}", e)
        }
    }

    override val inputStream: InputStream
        get() = process?.inputStream ?: throw IOException("[$serverKey] 语言服务器尚未启动")

    override val outputStream: OutputStream
        get() = process?.outputStream ?: throw IOException("[$serverKey] 语言服务器尚未启动")

    override val isClosed: Boolean
        get() = closed

    override fun close() {
        if (closed) return
        closed = true
        val t = tracked
        tracked = null
        if (t != null) {
            runCatching { t.process.outputStream.close() }
            runCatching { t.process.inputStream.close() }
            runCatching { McpStdioChannel.killProcessTree(serverKey, t.process) }
            // 进程树收掉后清账本记录，与启动时的 record 成对，别泄漏账本。
            runCatching { t.clearLedger() }
        }
        releaseSlot()
    }

    private fun releaseSlot() {
        if (slotHeld.compareAndSet(true, false)) {
            runCatching { McpStdioChannel.releaseSlot() }
        }
    }
}
