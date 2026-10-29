package com.aharou.feature.agent.domain.memory

/**
 * 「自动整理记忆」一次执行的结果。
 *
 * 与 [MemoryCompactReport] 分开是因为两者的执行逻辑与反馈口径不同：compact 是手动点击、
 * 本次立刻合并多少就回显多少（合并 N 组、归档 M 条）；organize 是每日自动跑、只报最终数字
 * 「合并了几组、重写了几条摘要」，不报归档与跳过数（用户不需要一醒来就看一堆数字）。
 */
data class MemoryOrganizeReport(
    val merged: Int = 0,
    val described: Int = 0,
)
