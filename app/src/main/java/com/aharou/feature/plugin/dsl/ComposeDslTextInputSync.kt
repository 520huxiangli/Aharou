/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ComposeDslTextInputSync.kt，LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：仅调整注释语言与措辞，逻辑逐行保留。
 */

package com.aharou.feature.plugin.dsl

/**
 * 输入框回显保护。
 *
 * DSL 树的文本由 JS 侧持有：每次按键都会把新文本回传给 JS，JS 更新状态后再把整棵树推回来。
 * 推回来的快照常常比输入框本地内容慢一拍，直接套用会把已删掉的字符「复活」、重置光标、
 * 打断输入法正在组合的候选词。所以必须区分三种情况：
 * - 快照与输入框一致：视为收敛，无事可做；
 * - 聚焦期间收到自己刚派发出的文本（或上一轮已同步的文本）：是自己的回显，丢弃；
 * - 其余情况（模板插入、切换条目、程序化赋值）才是真正的外部修改，需要套用。
 */

import kotlinx.coroutines.CompletableDeferred

internal class ComposeDslTextFieldEchoGuard(initialText: String) {

    enum class ExternalUpdate {
        /** 树已经追平输入框内容，无需处理。 */
        CONVERGED,

        /** 自己编辑产生的滞后回显，保留输入框当前状态。 */
        OWN_ECHO,

        /** 真正的外部修改，需要写回输入框。 */
        EXTERNAL_CHANGE
    }

    /** 最近一次已套用或已确认与输入框一致的外部文本。 */
    private var lastSyncedText: String = initialText

    /** 已派发但还没有快照确认的文本，按派发顺序。 */
    private val pendingEchoTexts = ArrayDeque<String>()

    /** 本地编辑派发到运行时的那一刻登记一条待确认记录。 */
    fun onLocalEditDispatched(text: String) {
        pendingEchoTexts.addLast(text)
    }

    fun reconcile(externalText: String, fieldText: String, isFocused: Boolean): ExternalUpdate {
        if (externalText == fieldText) {
            pendingEchoTexts.clear()
            lastSyncedText = externalText
            return ExternalUpdate.CONVERGED
        }
        if (isFocused) {
            if (externalText == lastSyncedText) {
                return ExternalUpdate.OWN_ECHO
            }
            val echoIndex = pendingEchoTexts.indexOf(externalText)
            if (echoIndex >= 0) {
                // 快照按派发顺序生成，命中位置之前的记录不会再回来，可以一起丢掉。
                repeat(echoIndex + 1) { pendingEchoTexts.removeFirstOrNull() }
                return ExternalUpdate.OWN_ECHO
            }
        }
        pendingEchoTexts.clear()
        lastSyncedText = externalText
        return ExternalUpdate.EXTERNAL_CHANGE
    }
}

/**
 * 输入派发串行队列。
 *
 * 每次按键并发派发会让运行时收到的顺序与用户输入顺序不一致，运行时状态落后于屏幕；
 * 保证同一时刻只有一个派发在途，运行时状态就始终是编辑序列的前缀，队列排空后推回来的树
 * 会与输入框收敛。
 *
 * 所有方法都应在主线程调用。
 */
internal class ComposeDslTextInputDispatchQueue(
    private val onAllSettled: () -> Unit
) {
    class Entry(val actionId: String, val text: String) {
        val completion = CompletableDeferred<Unit>()
    }

    /** 尚未完成的条目，按派发顺序；首个可能正在派发中。 */
    private val entries = ArrayDeque<Entry>()
    private var dispatchInFlight = false
    private lateinit var startDispatch: (Entry, onSettled: () -> Unit) -> Unit

    fun hasPending(): Boolean = entries.isNotEmpty()

    suspend fun awaitAll() {
        entries.toList().forEach { entry -> runCatching { entry.completion.await() } }
    }

    /** 不再派发，直接把所有待处理条目标记为完成（重渲染或销毁时用）。 */
    fun completeAll() {
        entries.forEach { entry ->
            if (!entry.completion.isCompleted) {
                entry.completion.complete(Unit)
            }
        }
        entries.clear()
        dispatchInFlight = false
    }

    fun enqueue(
        actionId: String,
        text: String,
        startDispatch: (Entry, onSettled: () -> Unit) -> Unit
    ) {
        this.startDispatch = startDispatch
        entries.addLast(Entry(actionId, text))
        pump()
    }

    private fun pump() {
        if (dispatchInFlight) {
            return
        }
        val next = entries.firstOrNull() ?: return
        dispatchInFlight = true
        startDispatch(next) { onEntrySettled(next) }
    }

    private fun onEntrySettled(entry: Entry) {
        dispatchInFlight = false
        if (!entries.remove(entry)) {
            // 队列已先被清空，这次派发回来不需要再驱动后续。
            return
        }
        if (!entry.completion.isCompleted) {
            entry.completion.complete(Unit)
        }
        if (entries.isEmpty()) {
            onAllSettled()
        } else {
            pump()
        }
    }
}
