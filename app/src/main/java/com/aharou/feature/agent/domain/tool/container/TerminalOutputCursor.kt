package com.aharou.feature.agent.domain.tool.container

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `terminal` 工具的输出去重游标：按标签记录已经返回给模型的内容，下次只回新追加的部分。
 *
 * 终端标签的屏幕缓冲保留整段 transcript，read/send 每次都取全量会把同一段历史反复灌进
 * 上下文（常驻 dev server、构建日志尤其明显）。做成单例是因为 [TerminalSessionTool] 由
 * ToolRegistry 注入、没有稳定生命周期，状态放在工具对象里会随工具重建而丢失。
 */
@Singleton
class TerminalOutputCursor @Inject constructor() {

    private val seen = HashMap<String, String>()

    /**
     * 返回 [current] 相对上次已返回内容的新增部分，并把游标推进到 [current]。
     * 首次读到该标签时返回全量——此前没有向模型返回过任何内容。
     */
    @Synchronized
    fun advance(tabId: String, current: String): String {
        val previous = seen.put(tabId, current)
        return if (previous == null) current else outputDelta(previous, current)
    }

    /** 只推进游标、不作为「新增」返回：调用方（start）已经把整段输出放进结果里了。 */
    @Synchronized
    fun mark(tabId: String, current: String) {
        seen[tabId] = current
    }

    @Synchronized
    fun clear(tabId: String) {
        seen.remove(tabId)
    }

    /** 丢弃已不存在的标签，避免长期运行后按标签累积的文本一直占内存。 */
    @Synchronized
    fun retainOnly(tabIds: Set<String>) {
        seen.keys.retainAll(tabIds)
    }
}

/**
 * [current] 相对 [previous] 的新增后缀。
 *
 * 终端 transcript 是滚动窗口：新输出追加在后面，超过缓冲行数后最旧的行会被丢弃，
 * 所以不能只按长度截取，而是找 previous 后缀与 current 前缀的最长重叠；
 * 无重叠（清屏、切换 alt-screen、内容被重置）时整段都算新增。
 */
internal fun outputDelta(previous: String, current: String): String {
    if (current == previous) return ""
    if (current.startsWith(previous)) return current.substring(previous.length)

    val maxOverlap = minOf(previous.length, current.length)
    for (length in maxOverlap downTo 1) {
        // 逐个长度比时不能先 current.take(length)（长 transcript 上 O(n²) 分配）；
        // 重叠区间的首字符必然相同，先用它预筛再交给 regionMatches 逐字符比对。
        if (previous[previous.length - length] != current[0]) continue
        if (previous.regionMatches(previous.length - length, current, 0, length)) {
            return current.substring(length)
        }
    }
    return current
}
