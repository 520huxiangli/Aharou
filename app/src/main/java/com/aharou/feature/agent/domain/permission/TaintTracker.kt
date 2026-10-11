package com.aharou.feature.agent.domain.permission

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级污点标记（taint tracking）：某会话读过私密数据（邮件/联系人/日历/工作区外文件/记忆读取，
 * 按仓库实际有的工具判定）后，把该会话标记为 tainted。此后该会话任何「外发到非白名单主机或目标未知」
 * 的调用一律升为 ASK，且**除 AUTO 外任何模式都翻不过**。
 *
 * 关键约束（务必保持）：
 * - **按会话隔离**：一个会话读了邮件，不该让旁边的会话也变严——故以 sessionId 为键分别记录；
 * - **只存内存**：重启即失，不落盘、不跨会话传递；
 * - **AUTO 模式不生效**：用户已定 AUTO 保持全放行，污点拦截只在 BUILD（ask/strict）下生效，参见
 *   [ToolPermissionPolicyEngine] 中相关分支的中文注释，勿当 bug 修改。
 *
 * 实现为进程内单例（[ConcurrentHashMap.newKeySet] 保证多会话并行下的并发安全）。
 */
object TaintTracker {
    private val tainted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 把某会话标记为已读私密数据。sessionId 为空时不记录（无会话上下文，无法隔离）。 */
    fun taint(sessionId: String) {
        if (sessionId.isNotBlank()) tainted.add(sessionId)
    }

    /** 该会话当前是否已被污染。 */
    fun isTainted(sessionId: String): Boolean =
        sessionId.isNotBlank() && tainted.contains(sessionId)

    /** 清除某会话的污点（会话被删除/清空时调用；重启即整体失效）。 */
    fun clear(sessionId: String) {
        tainted.remove(sessionId)
    }

    /** 当前是否存在被污染的会话（状态视图用，例如设置页展示「本会话读过私密数据」）。 */
    fun anyTainted(): Boolean = tainted.isNotEmpty()
}
