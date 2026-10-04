package com.aharou.feature.agent.domain.mcp

import android.system.OsConstants
import com.aharou.core.util.FileLogger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * STDIO MCP server 的进程侧公共设施：全局并发名额与进程树回收。
 *
 * 名额是跨 server 的全局计数（同一时刻真正跑着的 stdio 进程数），由各 [StdioTransport]
 * 在启动时占用、在关闭或进程退出时归还。进程树回收放在这里，是因为「杀干净」只跟
 * 「进程怎么被拉起、会怎么再 fork」有关，与传输逻辑无关。
 */
object McpStdioChannel {
    private const val TAG = "McpStdioChannel"

    /**
     * 同时运行的 STDIO server 进程数上限。
     * 每个 stdio server 都是容器内一个常驻 node/python 进程（数十 MB 内存），移动端不能任由配置堆叠：
     * 到上限就给明确错误，而不是静默起一堆进程把设备拖垮。取 8——日常配置 1~3 个 server，8 已足够宽松，
     * 又能挡住配置文件被复制粘贴堆出十几个进程的情况。
     */
    const val MAX_CONCURRENT_STDIO_SERVERS = 8

    /** 发 SIGTERM 后等进程自行退出的时长；到期仍存活的（含期间新 fork 的）改用 SIGKILL。 */
    private const val KILL_GRACE_MS = 300L

    /** 跨实例统计「已启动且未关闭」的 stdio server 数量。 */
    private val runningServers = AtomicInteger(0)

    /** 当前运行中的 stdio server 数量（诊断用）。 */
    fun runningCount(): Int = runningServers.get()

    /**
     * 占用一个运行名额；已满则抛 [McpException]（不排队、不静默堆积）。
     * 调用方拿到名额后必须保证在关闭 / 进程退出时调用 [releaseSlot] 归还。
     */
    fun acquireSlot(serverName: String) {
        while (true) {
            val current = runningServers.get()
            if (current >= MAX_CONCURRENT_STDIO_SERVERS) {
                throw McpException(
                    message = "[$serverName] 同时运行的 STDIO server 已达上限 $MAX_CONCURRENT_STDIO_SERVERS 个，" +
                        "无法启动新进程；请先停用不用的 MCP server 再重试"
                )
            }
            if (runningServers.compareAndSet(current, current + 1)) return
        }
    }

    /** 归还一个运行名额（每个名额只应归还一次）。 */
    fun releaseSlot() {
        runningServers.decrementAndGet()
    }

    /**
     * 结束整个进程树。stdio server 由容器内 `sh -c 'exec "$0" "$@"'` 拉起，server 自身还会再 fork
     * （npx → node、ssh MCP 的重试分支等）；只 destroy() 直接子进程会把孙进程留成孤儿——线上见过
     * ssh MCP 反复重试残留一串进程。这里按 /proc 的父子关系把整棵树收掉：先对所有后代发 SIGTERM，
     * 等 [KILL_GRACE_MS] 后重新扫描（覆盖退出期间新 fork 的）仍存活的发 SIGKILL，最后强杀根进程。
     */
    /**
     * 取进程 pid。Android SDK 的 `java.lang.Process` 没暴露 `pid()`（编译期解析不到），
     * 只能反射；拿不到就返回 -1，调用方退化为只杀直接子进程。
     */
    private fun pidOf(process: Process): Long =
        runCatching { (Process::class.java.getMethod("pid").invoke(process) as? Number)?.toLong() ?: -1L }
            .getOrDefault(-1L)

    fun killProcessTree(serverName: String, root: Process) {
        val rootPid = pidOf(root)
        if (rootPid <= 0L) {
            // 拿不到 pid 就没法扫树，退化为只杀直接子进程。
            runCatching { root.destroyForcibly() }
            return
        }
        collectDescendantPids(rootPid).forEach { sendSignal(serverName, it, OsConstants.SIGTERM) }
        runCatching { root.destroy() }
        try {
            Thread.sleep(KILL_GRACE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        collectDescendantPids(rootPid).forEach { sendSignal(serverName, it, OsConstants.SIGKILL) }
        runCatching { root.destroyForcibly() }
    }

    /** 按 /proc/<pid>/stat 的 PPid 字段自 [rootPid] 起收集全部后代 pid（同 uid 的进程在 /proc 可见）。 */
    private fun collectDescendantPids(rootPid: Long): List<Long> {
        val childrenByParent = HashMap<Long, MutableList<Long>>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toLongOrNull() ?: return@forEach
            val ppid = readParentPid(pid) ?: return@forEach
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val result = ArrayList<Long>()
        val stack = ArrayDeque<Long>()
        val seen = HashSet<Long>()
        stack.addLast(rootPid)
        seen.add(rootPid)
        while (stack.isNotEmpty()) {
            childrenByParent[stack.removeLast()]?.forEach { child ->
                if (seen.add(child)) {
                    result.add(child)
                    stack.addLast(child)
                }
            }
        }
        return result
    }

    /** 读 /proc/<pid>/stat 的 PPid（第 4 个字段）。comm 可能含空格与 ')'，故从最后一个 ')' 之后解析。 */
    private fun readParentPid(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val close = stat.lastIndexOf(')')
        if (close < 0) null else stat.substring(close + 2).split(' ').getOrNull(1)?.toLongOrNull()
    }.getOrNull()

    private fun sendSignal(serverName: String, pid: Long, signal: Int) {
        runCatching { android.os.Process.sendSignal(pid.toInt(), signal) }
            .onFailure { FileLogger.v(TAG, "[$serverName] 结束进程 $pid 失败: ${it.message}") }
    }
}
