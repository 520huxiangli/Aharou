package com.aharou.feature.agent.domain.tool

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按会话记录「这个会话最后一次看到的文件内容指纹」，用于写前版本校验（乐观并发控制）。
 *
 * ### 解决什么
 *
 * 锁（[WorkspaceFileLock]）只能覆盖「同时刻」的并发：A 持有锁写完后释放，B 再拿锁写的是
 * **B 自己那份过期内容**——锁拦不住这种「跨时刻」的覆盖，因为 B 的读发生在很久以前
 * （LLM 推理窗口是秒到分钟级，期间文件可能已被改过多次）。
 *
 * 校验的是**内容**而不是锁，因此它对锁管不到的写入同样有效：容器内 `sed -i`、远端其他进程、
 * 用户编辑器保存，都会让内容变化，从而被检出。
 *
 * ### 协议
 *
 * - **读**：`readFile` 成功后 [record] 当前指纹。
 * - **写**：写前比对 [get] 与当前指纹；不一致即拒绝（返回 `STALE_FILE`），由模型重新读取后重试。
 * - **没读过就写**（[get] 返回 null）**放行**：新文件、覆盖配置等场景模型不会先读，一律拒绝会打断
 *   正常流程。此时代价是漏判一次过期写，但那种情况本来也无从判断。
 *
 * ### 为什么按会话隔离
 *
 * 会话 A 读过的版本对会话 B 没有意义（B 可能压根没读过该文件）。按 `sessionId` 分桶，才能表达
 * 「**你**手上的内容过期了」这一语义。
 *
 * ### 容量
 *
 * 每个会话最多保留 [MAX_ENTRIES_PER_SESSION] 条，按访问序 LRU 淘汰。LLM 会话可能持续数小时并碰过
 * 成千上万个文件，不设上限就是内存泄漏。
 */
@Singleton
class FileVersionRegistry @Inject constructor() {

    /**
     * sessionId → (逻辑路径 → 指纹)。
     *
     * 内层用**访问序** LinkedHashMap 并在超出上限时淘汰最久未用的条目；因为要维持访问序，
     * 内层所有访问都在 `synchronized` 下进行。
     */
    private val sessions = ConcurrentHashMap<String, MutableMap<String, String>>()

    /**
     * 记录 [sessionId] 最后一次看到的 [key] 的内容指纹。
     *
     * [sessionId] 为 null/空白时静默跳过（无会话上下文的调用，如后台任务，不参与校验）。
     */
    fun record(sessionId: String?, key: String, fingerprint: String) {
        val sid = sessionId?.trim().orEmpty()
        if (sid.isEmpty() || key.isEmpty()) return
        val map = sessions.computeIfAbsent(sid) { newSessionMap() }
        synchronized(map) { map[key] = fingerprint }
    }

    /** 取 [sessionId] 记录的 [key] 指纹；从未记录过返回 null。 */
    fun get(sessionId: String?, key: String): String? {
        val sid = sessionId?.trim().orEmpty()
        if (sid.isEmpty() || key.isEmpty()) return null
        val map = sessions[sid] ?: return null
        synchronized(map) { return map[key] }
    }

    /**
     * 登记一次**不属于任何 AI 会话**的写入（典型是编辑器保存）。
     *
     * 这里刻意不按会话分桶：编辑器是用户本人的操作，用户改完文件后，**所有**会话手上的版本都已过期，
     * 因此每个已存在的会话桶都要更新。只记在"通配桶"里会漏判——写前校验查的是自己会话的桶。
     */
    fun recordExternal(key: String, fingerprint: String) {
        if (key.isEmpty()) return
        for (map in sessions.values) {
            synchronized(map) { map[key] = fingerprint }
        }
    }

    /** 忘记某会话对某路径的记录（如文件被删除、或回滚后需要重新建立基线）。 */
    fun forget(sessionId: String, key: String) {
        val map = sessions[sessionId] ?: return
        synchronized(map) { map.remove(key) }
    }

    /** 会话结束时清空其全部记录，避免长期驻留。 */
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** 当前跟踪的会话数，供测试与诊断使用。 */
    fun trackedSessionCount(): Int = sessions.size

    /** 某会话当前记录条数，供测试与诊断使用。 */
    fun trackedEntryCount(sessionId: String): Int {
        val map = sessions[sessionId] ?: return 0
        synchronized(map) { return map.size }
    }

    private fun newSessionMap(): MutableMap<String, String> =
        // 不用 Collections.synchronizedMap：包装器与内层 map 是两把不同的锁，而所有访问点都显式
        // synchronized(map)，只用内层锁才是一致的。
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > MAX_ENTRIES_PER_SESSION
        }

    private companion object {
        /**
         * 单会话最多保留的「路径 → 指纹」条数。512 条足够覆盖一次任务实际会回改的文件数；
         * 超出的部分按 LRU 淘汰，最坏后果是那部分文件退化成「没读过就写」的放行语义，而不是误报冲突。
         */
        const val MAX_ENTRIES_PER_SESSION = 512
    }
}
