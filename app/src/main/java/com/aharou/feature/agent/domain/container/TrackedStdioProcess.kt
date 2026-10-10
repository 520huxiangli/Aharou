package com.aharou.feature.agent.domain.container

/**
 * 已登记进 [RuntimeProcessStore] 账本的容器 stdio 进程句柄。
 *
 * [process] 供调用方读写协议流；[pid] 是账本里的宿主 pid，为 null 表示没拿到进程身份、
 * 未入账（此时 [clearLedger] 空转）。进程确认终止后必须调 [clearLedger] 清掉账本记录，
 * 否则残留记录会一直堆积（[RuntimeProcessStore.stale] 每次都会读全表）。
 */
class TrackedStdioProcess(
    val process: Process,
    val pid: Int?,
    private val onClosed: (Int) -> Unit
) {
    /** 清掉本进程的账本记录（与 [LinuxContainerEngine.startTrackedStdioProcess] 成对）。 */
    fun clearLedger() {
        pid?.let(onClosed)
    }
}
