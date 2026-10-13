package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkspaceFileLock"

/**
 * 工作区路径级互斥：把「同一文件的读-改-写」在多会话之间串行化。
 *
 * ### 为什么需要它
 *
 * `editFile` 是典型的 read-modify-write：读原文 → 内存里改 → 整篇写回。两个会话同时这么做时，
 * 后写的一方会覆盖前者的结果（lost update），且**双方都以为自己成功**。`StatefulAgentWorkflow`
 * 里原有的 `pathLocks` 只在**一次 tool batch 的 coroutineScope 内**生效，跨批次、跨会话、跨子代理
 * 全部失效——而子代理各自是独立 session，恰恰是最常见的并发来源。
 *
 * ### 锁键的选取
 *
 * 键必须用 [com.aharou.feature.workspace.domain.FileAccessProvider.toDisplayPath] 的结果
 * （`~/workspace/...` 这类**逻辑**路径），**不能**用物理路径：同一个文件在本地模式是宿主绝对路径、
 * PRoot 下是 `/data/...`、远端 SFTP 下是 `/root/workspace/...`，三者字符串不同，用物理路径会导致锁
 * 形同虚设。逻辑路径在三种后端下对同一文件稳定一致。
 *
 * ### 边界（必须知道）
 *
 * 这是**进程内**的锁，无法拦截：
 * - 容器内 shell 命令写文件（`sed -i`、`cat >`）——它们不经本进程；
 * - 远端服务器上其他进程的写入；
 * - 用户编辑器自己的保存。
 *
 * 这些场景由 [FileVersionRegistry] 的**写前版本校验**事后发现（它校验的是内容而非锁，因此对
 * 「锁管不到的写入」同样有效）。两者是互补关系，不是二选一。
 *
 * ### 实现要点
 *
 * - **多键排序获取**：一次要锁多个路径时按字符串序依次获取，避免两个会话以相反顺序请求同一组文件而死锁。
 * - **等待者计数回收**：没有等待者时把该路径的 [Mutex] 从表中移除，否则长会话会为每个碰过的路径
 *   永久留一条记录（这类实现最常见的泄漏点）。
 * - 不做超时/租约：进程内锁的持有者一旦取消或异常，`withLock` 的 `finally` 一定会释放，不存在
 *   「孤儿锁」；加租约反而会引入「锁还在但被别人抢走」的新竞态。
 */
@Singleton
class WorkspaceFileLock @Inject constructor() {

    /** 逻辑路径 → 该路径的锁。仅有持锁者或等待者时存在。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 逻辑路径 → 正在持有或等待该锁的协程数。归零即回收 [locks] 中的条目。 */
    private val waiters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 在指定逻辑路径的互斥保护下执行 [block]。
     *
     * @param keys 逻辑路径（[com.aharou.feature.workspace.domain.FileAccessProvider.toDisplayPath] 的结果）。
     *             null/空白会被忽略，因此调用方可以直接传可能为 null 的路径而不必先判空。
     *             空集合等价于不加锁。
     */
    suspend fun <T> withLock(keys: Collection<String?>, block: suspend () -> T): T {
        val ordered = keys.asSequence()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

        if (ordered.isEmpty()) return block()

        val acquired = ArrayList<Mutex>(ordered.size)
        try {
            for (key in ordered) {
                val mutex = acquireRef(key)
                mutex.lock()
                acquired.add(mutex)
            }
            logIfSuspicious()
            return block()
        } finally {
            // 逆序释放，与获取顺序对称。无论 block 正常返回、抛异常还是协程被取消，这里都会执行——
            // 这是本实现不需要租约的前提。
            for (i in acquired.indices.reversed()) {
                acquired[i].unlock()
                releaseRef(ordered[i])
            }
        }
    }

    /** 单键便捷重载。 */
    suspend fun <T> withLock(key: String?, block: suspend () -> T): T = withLock(listOf(key), block)

    /** 当前活跃的锁条目数，供测试与诊断使用。 */
    fun trackedKeyCount(): Int = locks.size

    private fun acquireRef(key: String): Mutex {
        waiters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        return locks.computeIfAbsent(key) { Mutex() }
    }

    private fun releaseRef(key: String) {
        val counter = waiters[key] ?: return
        if (counter.decrementAndGet() <= 0) {
            // 计数归零：无人持有也无人等待，移除条目避免无界增长。先清 waiters 再清 locks，且只在
            // 计数仍为 0 时移除——若此刻有新的 acquireRef 插入，它会重新建立条目，不会拿到被移除的旧 Mutex。
            waiters.remove(key, counter)
            if (counter.get() <= 0) locks.remove(key)
        }
    }

    /**
     * 条目数异常增长时留一条日志。正常情况下条目数应约等于"同时被操作的文件数"，若逼近
     * [SUSPICIOUS_KEY_COUNT] 说明要么确实在批量操作大量文件，要么回收逻辑出了问题——这条日志是
     * 排查后者的唯一线索，因此保留但用 `w` 级别避免刷屏。
     */
    private fun logIfSuspicious() {
        val size = locks.size
        if (size >= SUSPICIOUS_KEY_COUNT) {
            FileLogger.w(TAG, "路径锁条目数偏高: $size（等待者计数回收可能失效，请检查 releaseRef）")
        }
    }

    private companion object {
        /** 条目数达到该阈值时打一条诊断日志。 */
        const val SUSPICIOUS_KEY_COUNT = 2048
    }
}
