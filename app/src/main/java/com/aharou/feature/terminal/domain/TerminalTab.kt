package com.aharou.feature.terminal.domain

import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 终端标签的运行状态。Finished 保留在列表里不移除，供用户/AI 回看输出。
 */
sealed interface RunState {
    data object Running : RunState
    data class Finished(val exitCode: Int) : RunState
}

/** 后台任务完成通知携带的终端输出行数。 */
const val TAIL_LINES = 50

/** 截取终端 transcript 的最后 n 行；null 输入返回 null。 */
fun String?.takeTailLines(n: Int): String? =
    this?.lines()?.takeLast(n)?.joinToString("\n")

/**
 * 后台命令结束时 emit 的事件，供 ViewModel 订阅后通知 AI。
 */
data class TabFinishedEvent(
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    /** 发起该后台命令的会话 id；回调据此路由回原会话，而非用户当前所在会话。 */
    val sourceSessionId: String?,
    /** 标签启动时刻（epoch 毫秒）。事件是在标签已改为 Finished 之后才发的，调用方不能再去查「当前是否在跑」，故把时长依据随身带着。 */
    val startedAt: Long = 0L,
    /** 结束时终端 transcript 的最后 [TAIL_LINES] 行快照。事件可能被缓存到会话空闲才合并发送，期间标签可能已关闭，故在 emit 处提前截取。 */
    val tailOutput: String? = null
)

/**
 * 一个终端标签：会话 + 渲染视图 + 元数据。
 *
 * [view] 由 Compose 在创建 [TerminalView] 后回填；切换标签时复用同一会话、重新挂载视图。
 * [client] 的 viewProvider 始终读 [view]，故无论视图如何重建都能把输出刷到当前挂载的视图。
 *
 * 本地与远程终端模式共用此类型——区别仅在 [session] 背后的 [com.termux.terminal.SessionBackend]
 * （本地 SubprocessBackend fork 进程，远程 SSH shell 流）。
 */
class TerminalTab(
    val id: String,
    title: String,
    val session: TerminalSession,
    val isBackground: Boolean,
    val command: String?,
    /** 标签创建时刻（epoch 毫秒）；0 表示未知。桌宠等 UI 用它算「已跑多久」。 */
    val startedAt: Long = 0L,
    val notifyOnExit: Boolean = false,
    /** 发起该后台命令的会话 id；交互标签为 null。回调据此路由回原会话。 */
    val sourceSessionId: String? = null,
    /**
     * 启动那一刻绑定的工作区路径。proot 挂载参数写死在进程 argv 里，之后切工作区不会跟随，
     * 故必须记下来：与发起会话当前工作区不一致时，AI 工具会提醒。交互标签为 null。
     */
    val workspacePath: String? = null,
    val client: com.termux.terminal.TerminalSessionClient? = null,
    runState: RunState
) {
    var title: String = title
        internal set

    @Volatile
    var view: TerminalView? = null

    var runState: RunState = runState
        internal set

    /**
     * 是否已向 AI 发出过完成事件。兜底监控与 onFinished 都可能触发完成，
     * 用此标记保证同一命令只回调一次，避免进程迟退时重复通知。
     */
    @Volatile
    var finishedNotified: Boolean = false
}

/**
 * 终端标签摘要，供 AI 的 terminal 工具列出标签时使用（不携带 session/view 等运行时对象）。
 */
data class TabInfo(
    val id: String,
    val title: String,
    val isBackground: Boolean,
    val running: Boolean,
    val command: String?,
    val workspacePath: String? = null,
    val startedAt: Long = 0L
)

/** 终端标签 → 摘要（不携带 session/view 等运行时对象）。 */
fun TerminalTab.toTabInfo(): TabInfo = TabInfo(
    id = id,
    title = title,
    isBackground = isBackground,
    running = runState is RunState.Running,
    command = command,
    workspacePath = workspacePath,
    startedAt = startedAt
)

/** 正在运行的后台标签摘要；桌宠等系统级 UI 用它展示「后台任务」。 */
fun List<TerminalTab>.runningBackgroundTabInfos(): List<TabInfo> =
    filter { it.isBackground && it.runState is RunState.Running }.map { it.toTabInfo() }
