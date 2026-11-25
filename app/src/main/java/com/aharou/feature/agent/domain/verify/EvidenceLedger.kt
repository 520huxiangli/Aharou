package com.aharou.feature.agent.domain.verify

import com.aharou.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条工具产出证据。
 *
 * [excerpt] 是结果文本的**截断片段**，不是原文：整段存下来会让一轮几十次调用的输出把内存
 * 顶到几百 KB，而落地检查只需要逐字比对，不需要完整上下文。
 *
 * [argsSummary] 只用于人工排查（比如事后确认这条证据是哪次调用），**不参与**落地比对：
 * 参数是模型自己填的，拿它当证据等于让模型自证，用户转述进参数里的路径不该被当成工具核实过。
 */
data class Evidence(
    val toolName: String,
    val recordedAtMillis: Long,
    val argsSummary: String,
    val excerpt: String,
    val truncated: Boolean
)

/**
 * 本轮工具证据台账：纯内存、按会话隔离。
 *
 * 为什么按会话分桶：一个进程里常有多个会话并行跑工具（聊天页 + 定时任务 + 子代理），
 * 串台会让 A 会话的回答拿 B 会话的输出当依据。
 *
 * 为什么只留最近 [MAX_ENTRIES_PER_SESSION] 条：落地检查看的是「本轮」，更早的调用既占内存，
 * 又会让模型把十分钟前的旧路径当成刚刚核实过的结果。
 */
class EvidenceLedger {

    private val sessions = ConcurrentHashMap<String, MutableList<Evidence>>()

    /**
     * 登记一次工具产出。
     *
     * 写台账是旁路动作：任何意外都只记日志，绝不把异常抛回工具调用链——记不下来顶多这次
     * 少一条证据，不能因此让用户看到的工具结果变成失败。
     */
    fun record(sessionId: String?, toolName: String, argsSummary: String, resultText: String) {
        runCatching {
            val key = sessionId ?: DEFAULT_SESSION
            val evidence = Evidence(
                toolName = toolName,
                recordedAtMillis = System.currentTimeMillis(),
                argsSummary = argsSummary.toSingleLine(MAX_ARGS_CHARS),
                excerpt = excerptOf(resultText),
                truncated = resultText.length > MAX_EXCERPT_CHARS
            )
            // computeIfAbsent 保证同一会话只有一个桶：getOrPut 在并发下会各建一个、
            // 后写入的那份直接丢证据。
            val bucket = sessions.computeIfAbsent(key) { mutableListOf() }
            synchronized(bucket) {
                bucket += evidence
                while (bucket.size > MAX_ENTRIES_PER_SESSION) bucket.removeAt(0)
            }
        }.onFailure {
            // 连日志都可能不可用（如纯 JVM 单测里没有 android.util.Log），再包一层保证不抛。
            runCatching { FileLogger.w(TAG, "证据登记失败，已跳过：${toolName}", it) }
        }
    }

    /**
     * 未绑定会话时的简化入口。
     *
     * 主循环接入时必须走带 [sessionId] 的重载，这里只给手写脚本/单测用，落到 [DEFAULT_SESSION] 桶。
     */
    fun record(toolName: String, argsSummary: String, resultText: String) {
        record(null, toolName, argsSummary, resultText)
    }

    /** 取某会话当前全部证据的快照（拷贝，调用方随便改不影响台账）。 */
    fun snapshot(sessionId: String?): List<Evidence> {
        val bucket = sessions[sessionId ?: DEFAULT_SESSION] ?: return emptyList()
        return synchronized(bucket) { bucket.toList() }
    }

    /** 清空某会话；一轮结束或会话删除时调用，避免长期驻留。 */
    fun clear(sessionId: String?) {
        sessions.remove(sessionId ?: DEFAULT_SESSION)
    }

    companion object {
        /** 单条证据片段上限。再长对「能否逐字找到」没有增量，只是白占内存。 */
        const val MAX_EXCERPT_CHARS = 500

        /** 保留开头一段、结尾一段：工具输出往往是「标题/状态在头、结论或报错在尾」，
         *  只留开头容易把真正的结果行截掉，等长截断又会把两头的关键信息都切碎。 */
        private const val HEAD_CHARS = 300
        private const val TAIL_CHARS = 180

        /** 省略号本身也占位，长度按 300 + 3 + 180 留余量，保证拼出来不超过上限。 */
        private const val ELLIPSIS = " … "

        private const val MAX_ARGS_CHARS = 160

        /** 每会话保留条数：够覆盖一轮的正常调用量，又不会无限增长。 */
        private const val MAX_ENTRIES_PER_SESSION = 60

        /** 未绑定会话时的桶名。用空串而不是 "no-session"，与 snapshot(null) 的语义对齐。 */
        const val DEFAULT_SESSION = ""

        private const val TAG = "EvidenceLedger"

        /** 单条工具结果压成可检索的片段；不超上限时原样返回。 */
        fun excerptOf(text: String): String {
            if (text.length <= MAX_EXCERPT_CHARS) return text
            return text.take(HEAD_CHARS) + ELLIPSIS + text.takeLast(TAIL_CHARS)
        }

        /** 参数预览压成单行：换行和缩进在比对里没有意义，却会让日志与展示很脏。 */
        private fun String.toSingleLine(maxChars: Int): String {
            val oneLine = trim().replace(WHITESPACE_RE, " ")
            return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
        }

        private val WHITESPACE_RE = Regex("\\s+")
    }
}
