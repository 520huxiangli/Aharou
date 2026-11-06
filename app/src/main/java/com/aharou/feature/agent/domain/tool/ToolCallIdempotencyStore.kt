package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具调用的进程内幂等表：按「会话 + toolCallId」记住参数签名与执行结果。
 *
 * 防的是同一笔工具调用被重复执行——重试、断流续跑、用户重发都会让同一条 assistant 消息里的
 * toolCall 再走一遍执行链，对 `git commit`、写盘、部署这类命令就是实打实的重复副作用。
 * 模型给的 toolCall id 在同一次调用里是稳定的，所以拿它当键；参数不同说明不是同一笔调用，
 * 直接拒绝重跑比替模型猜意图安全。
 *
 * 只放内存：这条链要的是「同一次运行内不重复」，重启后历史消息里的旧调用已经不在这条链上，
 * 落库既没必要也会把结果文本塞进数据库。
 */
@Singleton
class ToolCallIdempotencyStore @Inject constructor() {

    /** 一次工具调用的缓存结果。与工作流内部的 ToolRunResult 同构，但不引用它（那个类型是私有的）。 */
    class CachedResult(
        val raw: String,
        val isError: Boolean,
        val attachments: List<AgentAttachment>,
        val images: List<AgentImage>
    )

    sealed interface Decision {
        /** 没执行过（或上次没留下结果），照常执行。 */
        object Execute : Decision

        /** 同 id 同参：直接复用上次结果。 */
        data class Replay(val result: CachedResult) : Decision

        /** 同 id 不同参：拒绝重跑。 */
        object Conflict : Decision
    }

    private class Entry(val signature: String, val result: CachedResult)

    /**
     * 容量 200：长会话里几十次写盘/命令是常态，200 条足够覆盖一轮任务的重试窗口；
     * 再大只是拿内存存早就没人回放的老结果（每条都带着完整结果文本）。
     * 超出后按插入顺序淘汰最旧的一条——被淘汰的调用即使再重跑，也只是回到「无记录」的默认行为。
     */
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun check(sessionId: String?, toolCallId: String, signature: String): Decision =
        synchronized(entries) {
            val entry = entries[mapKey(sessionId, toolCallId)]
            when {
                entry == null -> Decision.Execute
                entry.signature != signature -> Decision.Conflict
                else -> Decision.Replay(entry.result)
            }
        }

    fun record(sessionId: String?, toolCallId: String, signature: String, result: CachedResult) {
        synchronized(entries) {
            val key = mapKey(sessionId, toolCallId)
            val existing = entries[key]
            // 签名不同的记录不覆盖：那是另一笔调用的结果，覆盖掉会让后续同参重跑错误地复用它。
            if (existing == null || existing.signature == signature) {
                entries[key] = Entry(signature, result)
            }
        }
    }

    private fun mapKey(sessionId: String?, toolCallId: String): String =
        sessionId.orEmpty() + '\u0000' + toolCallId
}

/** 容量上限：长会话里几十次写盘/命令是常态，200 条足够覆盖一轮任务的重试窗口。 */
private const val MAX_ENTRIES = 200

/**
 * 只有会留下外部痕迹或改长期状态的工具才做「同 id 不重跑」保护。
 *
 * 读类工具（readFile / list / search / websearch / webfetch / viewImage / sessions 等）重跑一次
 * 只是多花一次读取，缓存反而会让模型看到过期数据——它们的副作用为零，不值得为它们牺牲新鲜度。
 * 名单按 ToolRegistry 的注册名写死；新增工具时如果它会写盘、改远端或改动长期状态，记得加进来。
 */
internal val SIDE_EFFECT_TOOL_NAMES: Set<String> = setOf(
    "Bash", "Shizuku", "terminal", "a11y", "vscreen", "device", "browser",
    "editFile", "writeFile", "sendFile", "sendToChat", "generateImage",
    "todo", "memory", "config", "planMode", "task", "manageMcp", "petAvatar",
    // 问用户与向父会话发消息也是不可重复的：重跑一次就是再拦一次人、再发一条消息。
    "askUserQuestion", "messageParent"
)

/** 同 id 同参的回放结果前面加这一行，让模型（和翻记录的用户）知道这次没有真的重跑。 */
internal const val TOOL_CALL_REPLAY_NOTICE = "[系统说明] 该工具调用此前已执行过，这是上次的结果，未重复执行。"

/** 同 id 不同参的拒绝原因码：参数变了就不敢替模型猜哪次才是本意。 */
internal const val TOOL_CALL_ARGUMENT_MISMATCH_CODE = "TOOL_CALL_ARGUMENT_MISMATCH"
