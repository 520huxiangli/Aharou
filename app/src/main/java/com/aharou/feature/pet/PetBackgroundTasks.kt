package com.aharou.feature.pet

/** 后台任务的来源：终端标签或子代理。只影响文案口径。 */
enum class PetTaskKind { TERMINAL, SUBAGENT }

/**
 * 桌宠要展示的一条后台任务。来源有两类：后台终端标签与运行中的子代理。
 *
 * 文案格式串由调用方从资源里取（用户可见文案不许硬编码在这里），本文件只负责挑选格式与算时间。
 */
data class PetTaskRef(
    /** 稳定 id：终端用 `term:<tabId>`、子代理用 `sub:<sessionId>`。桌宠据它补算「首次看见」的时刻。 */
    val id: String,
    val kind: PetTaskKind,
    /** 可直接显示的名字：终端的命令或标题；子代理取不到名字时为空串。 */
    val label: String = "",
    /** 启动时刻（epoch 毫秒）。 */
    val startedAt: Long
)

/**
 * 后台任务的气泡文案。没有任务时返回 null，调用方据此清掉气泡。
 *
 * 只有一个**有名字**的任务时直接用它的名字（「npm run dev · 1:23」）；全是子代理时用「子代理 2 个 · 1:23」；
 * 其余（多个终端任务混在一起）用计数口径「后台 3 个任务 · 1:23」。
 *
 * @param namedFormat 形如 `%1$s · %2$s`：任务名 + 已跑时长。
 * @param subagentFormat 形如 `子代理 %1$d 个 · %2$s`：子代理数 + 已跑时长。
 * @param countFormat 形如 `后台 %1$d 个任务 · %2$s`：任务数 + 已跑时长。
 */
fun backgroundTaskLine(
    tasks: List<PetTaskRef>,
    now: Long,
    namedFormat: String,
    subagentFormat: String,
    countFormat: String
): String? {
    if (tasks.isEmpty()) return null
    val elapsed = formatElapsed(now - tasks.minOf { it.startedAt })
    val only = tasks.singleOrNull()
    return when {
        only != null && only.label.isNotBlank() -> namedFormat.format(only.label, elapsed)
        tasks.all { it.kind == PetTaskKind.SUBAGENT } -> subagentFormat.format(tasks.size, elapsed)
        else -> countFormat.format(tasks.size, elapsed)
    }
}

/**
 * 已跑时长：不足一小时写成 `m:ss`，超过写成 `h:mm:ss`。
 * 时钟回拨或 startedAt 缺失导致的负数按 0 处理，不显示负号。
 */
fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
