package com.aharou.core.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 让 PRoot 自己收场：优先 SIGQUIT，而不是直接 SIGKILL。
 *
 * PRoot 是 tracer，容器里的进程都是它的 tracee。SIGKILL 打在 tracer 上时，内核会把这些 tracee 摘掉
 * trace 继续跑，proot 的 atexit 清理（`--kill-on-exit` 就挂在它上面）一律不执行；双 fork 出去、被
 * reparent 到 pid 1 的守护进程更是从 /proc 的父子链上再也找不回来。SIGQUIT 是 proot 约定的「先杀掉
 * 整个被 trace 集合、再退出自己」，所以终止入口优先走它，确认不了才回落 SIGTERM/SIGKILL。
 *
 * 发信号前必须先确认两件事，任一不成立都直接返回 false 让调用方走强杀，**绝不盲发**：
 *  1. proot 已经装好 SIGQUIT handler。proot 先 fork 出第一个 tracee、之后才装 handler，这段启动窗口里
 *     发 SIGQUIT 只会杀掉 tracer、把 guest 留成孤儿（SIGQUIT 的默认动作是终止进程）。
 *  2. SIGQUIT 没有还堵在信号掩码里。Android 的进程由 App 进程 fork 出来，会继承那边的信号掩码；
 *     被屏蔽的信号送不到 handler，只会挂成 pending，白等一轮宽限。实测本机上由 App 起的 proot：
 *     `SigCgt` 含 SIGQUIT（proot 自己的 handler 已装），但 `SigBlk` 也含 SIGQUIT（ART 那边带下来的），
 *     所以这条时必须把掩码清掉，否则整条 SIGQUIT 路径永远走不通，见 [wrapForSignalMaskClearing]。
 */
object ProotShutdown {

    private const val TAG = "ProotShutdown"

    /** proot 可执行文件在 nativeLibraryDir 里的实际文件名（见 ContainerInstaller.prootBinary）。 */
    private const val PROOT_EXECUTABLE = "libproot.so"

    /** `/proc/<pid>/status` 的 SigCgt/SigBlk 是位掩码：第 N 个信号对应第 N-1 位，SIGQUIT 是 3 号信号。 */
    private const val SIGQUIT_BIT = 0x4L

    private const val POLL_INTERVAL_MS = 10L

    /** 等 proot 装好 SIGQUIT handler 的上限。就绪正常只要几十毫秒，这个上限只为兜住卡在启动的情形。 */
    const val READY_TIMEOUT_MS = 1500L

    /**
     * 发完 SIGQUIT 后等 proot 收完 tracee 并退出的宽限。
     *
     * 取 3 秒的理由：proot 收到 SIGQUIT 后是「逐个 kill + reap」再退出，容器里十个上下的进程通常
     * 数百毫秒内结束；宽限太长会把超时强杀的响应拖慢，太短则会把还在收尾的 proot 又强杀一遍、
     * 前功尽弃。真正的卡死由后面的 SIGKILL 兜底，这里只负责给正常收场留出时间。
     */
    const val EXIT_GRACE_MS = 3000L

    /** [pid] 当前是不是那个 proot tracer。读不到 exe 一律判否。 */
    fun isProotProcess(pid: Long): Boolean = executableName(pid) == PROOT_EXECUTABLE

    /**
     * 轮询等 [pid] 具备接收 SIGQUIT 的条件，就绪返回 true。
     *
     * 返回 false 的三种情形含义不同，但调用方的动作一致——回落强杀：
     *  - `stillOwned()` 变 false：进程已经不是我们要终止的那一代了（被复用或已退出）；
     *  - `/proc/<pid>/exe` 读不到：条目消失说明进程已经没了，是「已终止」而不是「失败」，由调用方复核；
     *  - 超时或 SIGQUIT 被掩码挡住：这条路走不通，别再等。
     */
    fun awaitProotSignalHandler(
        pid: Long,
        timeoutMs: Long = READY_TIMEOUT_MS,
        stillOwned: () -> Boolean = { true },
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            if (!stillOwned()) return false
            val exeName = executableName(pid) ?: return false
            if (exeName == PROOT_EXECUTABLE) {
                val masks = signalMasks(pid) ?: return false
                // SigCgt 里有 SIGQUIT 才说明 handler 装好了；在此之前它是默认动作（终止），发过去就是孤儿
                if (masks.caught and SIGQUIT_BIT != 0L) {
                    if (masks.blocked and SIGQUIT_BIT != 0L) {
                        FileLogger.w(TAG, "pid=$pid 的 SIGQUIT 被信号掩码挡住（多半是启动时没经 shell 中转清掩码），改走强杀")
                        return false
                    }
                    return true
                }
            }
            if (System.nanoTime() - deadline >= 0) {
                FileLogger.w(TAG, "等 pid=$pid 装好 SIGQUIT handler 超时(${timeoutMs}ms)，改走强杀")
                return false
            }
            sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * 请 [pid] 上的 proot 自己退出：就绪后发 SIGQUIT，让它杀掉并 reap 整个被 trace 集合。
     *
     * 返回 true 只代表「信号已经交出去」或「进程已经没了」，**不代表 tracee 已清干净**，调用方仍要
     * 复核根进程是否消失。就绪确认失败返回 false，不做任何兜底动作——强杀由调用方决定。
     */
    fun requestProotShutdown(pid: Long, stillOwned: () -> Boolean = { true }): Boolean {
        if (!awaitProotSignalHandler(pid, READY_TIMEOUT_MS, stillOwned)) return false
        // 轮询期间进程可能刚好退出（或被复用），发信号前再核一次，别把 SIGQUIT 打到别人身上
        if (!stillOwned()) return false
        return try {
            Os.kill(pid.toInt(), OsConstants.SIGQUIT)
            FileLogger.i(TAG, "已向 proot pid=$pid 发 SIGQUIT，交给它自己收掉 tracee")
            true
        } catch (e: ErrnoException) {
            // 进程已经没了：目标本来就达成了，按成功处理
            if (e.errno == OsConstants.ESRCH) {
                FileLogger.i(TAG, "proot pid=$pid 已不存在，SIGQUIT 无需发送")
                true
            } else {
                throw e
            }
        }
    }

    /**
     * 把「直接 exec proot」改成经 `/system/bin/sh -c 'exec "$@"'` 中转。
     *
     * 直接起进程会继承 App 进程的信号掩码，被 ART 屏蔽的 SIGQUIT 会跟着带进 proot（实测本机 proot 的
     * `SigBlk` 里确实含着 SIGQUIT），于是 [requestProotShutdown] 永远等不到可用的 handler。Android 的
     * mksh 在初始化时会清掉继承来的掩码，所以包一层中转再 exec。
     *
     * exec 保持同一个 PID、同一组已打开的描述符，重定向与管道都不变；argv 走位置参数传递，
     * 全程不经过 shell 求值，用户命令里的任何字符都不会被执行或改写。
     */
    fun wrapForSignalMaskClearing(argv: List<String>, shell: String = "/system/bin/sh"): List<String> =
        listOf(shell, "-c", "exec \"\$@\"", WRAPPER_ARGV0) + argv

    /** 中转 shell 的 $0；只占位，不参与任何逻辑。 */
    private const val WRAPPER_ARGV0 = "aharou-proot"

    /**
     * `/proc/<pid>/exe` 指向的文件名。
     *
     * 二进制被替换（应用更新）后内核会在目标末尾加 " (deleted)"，带这个后缀的仍是同一个可执行文件，
     * 认不出来会白白放弃 SIGQUIT 这条路径。
     */
    private fun executableName(pid: Long): String? {
        val target = try {
            Os.readlink("/proc/$pid/exe")
        } catch (_: ErrnoException) {
            return null
        }
        return target.substringAfterLast('/').removeSuffix(" (deleted)").ifBlank { null }
    }

    private class SignalMasks(val caught: Long, val blocked: Long)

    /** 一次读出 SigCgt（已安装 handler）与 SigBlk（被屏蔽）两个掩码；读不到返回 null。 */
    private fun signalMasks(pid: Long): SignalMasks? {
        val lines = runCatching { File("/proc/$pid/status").readLines() }.getOrNull() ?: return null
        val caught = lines.firstOrNull { it.startsWith("SigCgt:") }?.let { parseMask(it) } ?: return null
        val blocked = lines.firstOrNull { it.startsWith("SigBlk:") }?.let { parseMask(it) } ?: return null
        return SignalMasks(caught, blocked)
    }

    /** 掩码是十六进制；用无符号解析，最高位为 1 的实时信号位也不至于解析失败。 */
    private fun parseMask(line: String): Long? =
        runCatching { java.lang.Long.parseUnsignedLong(line.substringAfter(':').trim(), 16) }.getOrNull()

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
