package com.aharou.feature.agent.domain.memory

/**
 * 跨设备同步冲突里「这一份属于哪一边」。
 *
 * 冲突时本机版本被保留、远端版本另存进 archive/，两边都会被打上 `pending: true`，
 * 但它们对使用者的含义完全相反：本机那份仍在生效，归档那份只是留档。渲染与界面都要能分清，
 * 否则一次同步就能把一条常驻红线降级成「不得当作既定事实」而没人发现。
 *
 * 写入方（[MemorySyncManager] 往 description 追的冲突说明）与识别方共用这里同一份文案，
 * 避免两处各写一句、改了一边就认不出来。
 */
internal enum class MemoryConflictSide(val counterpart: String) {
    /** 本机正在生效的那份：仓库工作树副本与本机原件都归这一类。 */
    LOCAL("远端版本"),

    /** 远端版本另存出来的那份（只在 archive/ 里），只是留档、不参与生效。 */
    REMOTE("本机版本");

    internal val markerSuffix: String get() = "，与${counterpart}冲突]"

    /** 写进 description 的冲突说明；日期只为让人看出冲突是哪天发生的。 */
    internal fun marker(date: String): String = "[待确认 $date$markerSuffix"

    companion object {
        /**
         * 从 description 认回冲突属于哪一侧。
         *
         * 带收尾的 `]` 一起匹配：正文里顺口提到「与远端版本冲突」不该被当成标记。
         * 认不出（用户改过描述、或摘要被截断）时返回 null，退回普通待确认的处理。
         */
        internal fun fromDescription(description: String): MemoryConflictSide? =
            MemoryConflictSide.entries.firstOrNull { description.contains(it.markerSuffix) }
    }
}

/** 本机仍在生效的冲突；[Memory.pending] 一起判，调用点不必各写一次。 */
internal fun isLocalSideConflict(memory: Memory): Boolean =
    memory.pending && MemoryConflictSide.fromDescription(memory.description) == MemoryConflictSide.LOCAL
