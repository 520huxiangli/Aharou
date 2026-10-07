package com.aharou.core.util

import android.system.OsConstants
import java.io.File

/**
 * 进程身份与进程树回收。
 *
 * Android 上按 PID 终止进程有两个坑：PID 会被系统复用（可能杀到无关进程），以及只杀直接子进程
 * 会把容器内的子孙留成孤儿——PRoot 忽略 SIGTERM，而 `--kill-on-exit` 靠 atexit 生效、SIGKILL
 * 又绕过 atexit，于是出现「进程已消失但容器里的任务还在跑」。
 *
 * 这里用 `/proc/<pid>/stat` 的启动时钟（第 22 字段）给进程定代次：同一次开机内 PID 被复用后
 * 启动时钟必然不同。只有代次核验通过才发信号，核验不了就一个信号都不发（宁可漏杀也不杀错）。
 */
object ProcessIdentity {

    private const val TAG = "ProcessIdentity"

    /** 发 SIGTERM 后等待进程自行退出的时长；到期仍存活的改用 SIGKILL。PRoot 忽略 SIGTERM，宽限不必长。 */
    private const val TERM_GRACE_MS = 120L

    /** 发 SIGKILL 后等待进程消失的时长，之后复核根进程。 */
    private const val KILL_GRACE_MS = 150L

    /** 一个进程的身份：宿主 pid + 该代次的启动时钟（读不到时为 null）。 */
    data class Handle(val pid: Int, val startTimeTicks: Long?)

    /** 代次核验结果。 */
    enum class State { MATCHED_ACTIVE, MATCHED_ZOMBIE, MISSING, REUSED, UNREADABLE }

    /** 记录 [process] 当前代次，供之后终止时核验；三条取 pid 的路都失败时才返回 null。 */
    fun capture(process: Process, fallbackPid: Int? = null): Handle? {
        val pid = pidOf(process)?.toInt()?.takeIf { it > 0 }
            ?: fallbackPid?.takeIf { it > 0 }
            ?: run {
                FileLogger.w(TAG, "拿不到命令进程 pid（直调/反射/子进程差集均失败），本次无法按身份终止")
                return null
            }
        val ticks = readStartTimeTicks(pid)
        if (ticks == null) FileLogger.v(TAG, "进程 $pid 的启动时钟读不到，终止时只能按 pid 存在性核验")
        return Handle(pid, ticks)
    }

    /**
     * 当前进程的直接子进程 pid 集合。
     *
     * 优先用内核的 `/proc/self/task/<tid>/children`；部分内核或 SELinux 策略下该文件读不到，
     * 退回扫描 `/proc` 按 PPid 反查（贵但可靠）。两条都空才是真的没有子进程。
     */
    fun childPids(): Set<Int> {
        val viaChildren = runCatching {
            File("/proc/self/task").list().orEmpty().asSequence()
                .mapNotNull { tid -> runCatching { File("/proc/self/task/$tid/children").readText() }.getOrNull() }
                .flatMap { it.trim().split(' ').asSequence() }
                .mapNotNull { it.toIntOrNull() }
                .toSet()
        }.getOrDefault(emptySet())
        if (viaChildren.isNotEmpty()) return viaChildren

        val self = android.os.Process.myPid()
        val found = File("/proc").list().orEmpty().asSequence()
            .mapNotNull { it.toIntOrNull() }
            .filter { readStat(it)?.getOrNull(PPID_INDEX)?.toIntOrNull() == self }
            .toSet()
        if (found.isEmpty()) FileLogger.w(TAG, "子进程枚举为空（children 接口与 /proc 反查都无结果）")
        return found
    }

    /**
     * `java.lang.Process` 取 pid。
     *
     * android.jar 里虽有 `pid()` 声明，但 Kotlin 编译期解析不到（AGP 的 API 面），设备实现也不保证有，
     * 所以只走反射：先试方法，再试各版本实现里的字段名。都不行就交给调用方的子进程差集兜底。
     */
    fun pidOf(process: Process): Long? {
        runCatching { (process.javaClass.getMethod("pid").invoke(process) as? Number)?.toLong() }
            .getOrNull()?.takeIf { it > 0 }?.let { return it }
        for (name in PID_FIELDS) {
            runCatching {
                process.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(process) as? Number
            }.getOrNull()?.toLong()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    /** 核验 [pid] 是否仍是 [expectedStartTicks] 那一代。 */
    fun state(pid: Int, expectedStartTicks: Long?): State {
        val fields = readStat(pid)
        if (fields == null) {
            // 读不到不等于不存在：可能是权限/竞态。只有确认目录也不在时才判 MISSING。
            return if (File("/proc/$pid").exists()) State.UNREADABLE else State.MISSING
        }
        val startTicks = fields.getOrNull(START_TIME_INDEX)?.toLongOrNull() ?: return State.UNREADABLE
        if (expectedStartTicks != null && startTicks != expectedStartTicks) return State.REUSED
        return if (fields.firstOrNull()?.firstOrNull() == 'Z') State.MATCHED_ZOMBIE else State.MATCHED_ACTIVE
    }

    /**
     * 终止以 [handle] 为根的一整棵进程树，返回是否**确认**根进程已消失。
     *
     * 根已消失或已成僵尸 → true；身份读不出来或启动时钟对不上（PID 已被复用）→ 不发任何信号并
     * 返回 false。其余情况自子向父发 SIGTERM，宽限后重扫一次（覆盖期间新 fork 的）对仍属原代次的
     * 发 SIGKILL，最后复核根已终止才算确认。调用方不得把 false 当作「已停止」。
     */
    fun terminateTree(handle: Handle): Boolean {
        val initial = state(handle.pid, handle.startTimeTicks)
        FileLogger.i(TAG, "终止进程树 pid=${handle.pid} ticks=${handle.startTimeTicks} 初始=$initial")
        when (initial) {
            // 僵尸表示进程已死、只等父进程回收，不必再动手
            State.MISSING, State.MATCHED_ZOMBIE -> return true
            State.REUSED -> {
                FileLogger.w(TAG, "进程 ${handle.pid} 启动时钟对不上（PID 已被复用），不发信号")
                return false
            }
            // 身份读不出来时不再直接放弃：拿不到代次至多发错一个信号，比整棵树都杀不掉要好
            State.UNREADABLE, State.MATCHED_ACTIVE -> Unit
        }
        // 每个 pid 首次观察到的代次；两轮信号都按这份快照核验，期间被复用的 pid 会被跳过。
        val generations = HashMap<Int, Long?>()
        generations[handle.pid] = handle.startTimeTicks
        signalTree(handle.pid, OsConstants.SIGTERM, generations)
        sleep(TERM_GRACE_MS)
        signalTree(handle.pid, OsConstants.SIGKILL, generations)
        sleep(KILL_GRACE_MS)
        // 被 SIGKILL 后进程先变僵尸（/proc 条目仍在、等父进程回收），故僵尸也算已终止——
        // 否则会把「已经杀死、只是还没回收」误报成「没杀掉」。
        val finalState = state(handle.pid, handle.startTimeTicks)
        FileLogger.i(TAG, "终止进程树结束 pid=${handle.pid} 末态=$finalState")
        return finalState == State.MISSING || finalState == State.MATCHED_ZOMBIE
    }

    /** 对整棵树发信号：先子孙后根，逐个核验代次，新出现的 pid 记录当下代次。 */
    private fun signalTree(rootPid: Int, signal: Int, generations: MutableMap<Int, Long?>) {
        descendantsFirst(rootPid).forEach { pid ->
            val ticks = generations.getOrPut(pid) { readStartTimeTicks(pid) }
            if (isSameGeneration(pid, ticks)) sendSignal(pid, signal)
        }
        if (isSameGeneration(rootPid, generations[rootPid])) sendSignal(rootPid, signal)
    }

    /**
     * 代次核验：核验不了就放行——拿不到代次至多发错一个信号，比整棵树都杀不掉要好。
     * 进入这里之前 [state] 已经排除了「已消失」与「启动时钟对不上」两种情形。
     */
    private fun isSameGeneration(pid: Int, ticks: Long?): Boolean {
        val fields = readStat(pid) ?: return true
        if (ticks == null) return true
        return fields.getOrNull(START_TIME_INDEX)?.toLongOrNull() == ticks
    }

    /** 自 [rootPid] 按 /proc 父链收集后代，深的在前——先杀子孙再杀父，避免父先死让子树被 reparent 走。 */
    private fun descendantsFirst(rootPid: Int): List<Int> {
        val childrenByParent = HashMap<Int, MutableList<Int>>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val ppid = readStat(pid)?.getOrNull(PPID_INDEX)?.toIntOrNull() ?: return@forEach
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val ordered = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        val seen = HashSet<Int>()
        seen.add(rootPid)
        stack.addLast(rootPid)
        while (stack.isNotEmpty()) {
            childrenByParent[stack.removeLast()]?.forEach { child ->
                if (seen.add(child)) {
                    ordered.add(child)
                    stack.addLast(child)
                }
            }
        }
        ordered.reverse()
        return ordered
    }

    /** 本次开机的标识；跨重启后同一个 PID 不再代表同一个进程。 */
    fun readBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().ifBlank { null }
    }.getOrNull()

    private fun readStartTimeTicks(pid: Int): Long? = readStat(pid)?.getOrNull(START_TIME_INDEX)?.toLongOrNull()

    /**
     * 解析 `/proc/<pid>/stat` 的字段表。comm（第 2 字段）可能含空格与 ')'，所以一律从**最后一个**
     * ')' 之后切分——切出来的第一段是 state（第 3 字段），故字段 N 落在下标 N-3。
     */
    private fun readStat(pid: Int): List<String>? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val close = text.lastIndexOf(')')
        if (close < 0) null else text.substring(close + 2).split(' ')
    }.getOrNull()

    /** 发信号：先走 `Os.kill`，失败再退到 `Process.sendSignal`——两条路都试过才算失败。 */
    private fun sendSignal(pid: Int, signal: Int) {
        if (runCatching { android.system.Os.kill(pid, signal) }.isSuccess) return
        runCatching { android.os.Process.sendSignal(pid, signal) }
            .onFailure { FileLogger.w(TAG, "向进程 $pid 发送信号 $signal 失败: ${it.message}") }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private const val PPID_INDEX = 1
    private const val START_TIME_INDEX = 19

    /** 各版本 `Process` 实现里存放 pid 的字段名。 */
    private val PID_FIELDS = listOf("pid", "mPid")
}
