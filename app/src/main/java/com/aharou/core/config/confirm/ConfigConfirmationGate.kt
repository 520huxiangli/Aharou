package com.aharou.core.config.confirm

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Funnels every aharou-config write through user confirmation. Mirrors
 * iOS `ConfigConfirmationGate`.
 *
 * 例外：AUTO 模式下不敏感的变更会在入队前被 [autoApprovePolicy] 直接判为已批准
 * （见 [ConfigAutoApprovePolicy]），既不占前橱、也不起动超时计时；敏感字段与读明文
 * 永远得人点。
 *
 * The gate is a single global queue: at most one confirm dialog shows
 * at a time. Subsequent requests stack up so the agent can fire a
 * rapid sequence of writes and the user reviews them one by one in
 * arrival order. Each request carries a 120-second timeout — past
 * that, the request resolves as [ConfirmOutcome.TimedOut] and any
 * future user interaction with the (stale) dialog is a no-op.
 *
 * Bridge entry: [requestConfirmation]. The UI observes [pending] (a
 * StateFlow) to render the front-of-queue dialog.
 */
object ConfigConfirmationGate {
    private const val TAG = "ConfigConfirmGate"

    /**
     * [T-android-config-confirm-timeout] Wall-clock window per request. The
     * timer starts the moment the change is enqueued and runs regardless of
     * foreground/background (behavior intentionally unchanged) — so when the
     * app is backgrounded the OLD 30s window elapsed before the user ever saw
     * the dialog (iOS reported three back-to-back timedOut with no chance to
     * approve). Widened to 2 minutes so a backgrounded user has time to react
     * to the [backgroundNotifier] nudge and return. Mirrors iOS 073fb9c5.
     */
    const val TIMEOUT_MS: Long = 120_000

    /**
     * [T-android-config-confirm-timeout] Posts a "config change awaiting
     * approval" notification when a change is pending and the app is
     * backgrounded. Injected by AIEditorApp.onCreate (kept out of this global
     * object so it stays Context-free and unit-testable). Returns true when a
     * notification was actually posted, so [notifiedIds] only records ids we
     * really notified.
     */
    @Volatile
    var backgroundNotifier: ((PendingConfigChange) -> Boolean)? = null

    /**
     * [T-android-config-confirm-timeout] Cancels a previously-posted
     * pending-approval notification once the change resolves. Injected
     * alongside [backgroundNotifier].
     */
    @Volatile
    var cancelNotification: ((String) -> Unit)? = null

    /**
     * AUTO 模式下「非敏感配置修改不弹窗」的判定钩子，由
     * [ConfigAutoApprovePolicy] 注入（与 [backgroundNotifier] 同一种注入风格，
     * 闸门本身保持无 Context 依赖、可单测）。返回 true 时 [requestConfirmation]
     * 不入队，直接按「已批准」返回，弹窗与 120 秒计时都不会发生。
     *
     * null 表示任何请求都必须人工确认——这是默认值，也是策略未接线时的安全兜底。
     */
    @Volatile
    var autoApprovePolicy: ((PendingConfigChange) -> Boolean)? = null

    /** 审计行里的来源标记：这次修改没弹窗，由 AUTO 模式策略自动批准。 */
    const val AUTO_APPROVE_CAPTION: String = "auto-approved (AUTO mode)"

    /** Ids we've already posted a background notification for, so a change that
     *  surfaces in the background AND later triggers a didEnterBackground pass
     *  isn't notified twice. Cleared per-id in [resolve]. */
    private val notifiedIds = HashSet<String>()

    /** Front-of-queue request, if any. UI binds to this. */
    private val _pending = MutableStateFlow<PendingConfigChange?>(null)
    val pending: StateFlow<PendingConfigChange?> = _pending.asStateFlow()

    /** Backlog of additional requests waiting their turn. */
    private val queue: ArrayDeque<QueuedRequest> = ArrayDeque()

    /** Continuation lookup keyed by request id, so timeout can resolve the right awaiter. */
    private val awaiters = HashMap<String, Continuation<ConfirmOutcome>>()
    private val timeouts = HashMap<String, Job>()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class QueuedRequest(
        val change: PendingConfigChange,
        val cont: Continuation<ConfirmOutcome>,
    )

    /**
     * Suspend until the user resolves the request (approve / reject /
     * per-row reject) or the timeout fires.
     *
     * @param autoMode 发起这次请求的**会话**是否处于 AUTO 模式（由 `config` 工具从
     *   `AgentContext.mode` 传入，会话级、并行安全：每个请求按自己会话的模式判定，
     *   不受界面当前选中哪个会话影响）。只有它为真且 [autoApprovePolicy] 判定不敏感时
     *   才自动批准。
     */
    suspend fun requestConfirmation(change: PendingConfigChange, autoMode: Boolean = false): ConfirmOutcome {
        // 自动批准必须发生在入队之前：一旦进队就会占用前橱、弹窗、启动超时计时，
        // 而 AUTO 模式下用户本就没打算看这些弹窗。
        if (autoMode && !change.alwaysConfirm && autoApprovePolicy?.invoke(change) == true) {
            FileLogger.i(TAG, "auto-approved ${change.items.joinToString { it.path }}")
            return ConfirmOutcome.Approved(change.items, auto = true)
        }
        return suspendCoroutine { cont ->
            scope.launch {
                mutex.withLock {
                    awaiters[change.id] = cont
                    timeouts[change.id] = scope.launch {
                        delay(TIMEOUT_MS)
                        timeout(change.id)
                    }
                    if (_pending.value == null) {
                        _pending.value = change
                        // [T-android-config-confirm-timeout] If already
                        // backgrounded when this becomes front-of-queue, the
                        // user won't see the dialog — nudge them so they can
                        // return before the 120s timeout.
                        notifyIfBackgrounded(change)
                    } else {
                        queue.addLast(QueuedRequest(change, cont))
                    }
                }
            }
        }
    }

    /** UI callback: user tapped "Apply" with the post-toggle item state. */
    fun userApprove(items: List<PendingConfigChangeItem>) {
        val current = _pending.value ?: return
        scope.launch { resolve(current.id, ConfirmOutcome.Approved(items)) }
    }

    /** UI callback: user tapped Cancel. */
    fun userReject() {
        val current = _pending.value ?: return
        scope.launch { resolve(current.id, ConfirmOutcome.Rejected) }
    }

    private suspend fun timeout(id: String) {
        if (awaiters[id] == null) return
        FileLogger.i(TAG, "timeout for change $id")
        resolve(id, ConfirmOutcome.TimedOut)
    }

    private suspend fun resolve(id: String, outcome: ConfirmOutcome) {
        val cont: Continuation<ConfirmOutcome>?
        val advance: Boolean
        var advancedTo: PendingConfigChange? = null
        mutex.withLock {
            cont = awaiters.remove(id)
            timeouts.remove(id)?.cancel()
            notifiedIds.remove(id)
            advance = _pending.value?.id == id
            if (advance) {
                val next = queue.removeFirstOrNull()
                _pending.value = next?.change
                advancedTo = next?.change
            }
        }
        // The change is done — clear any pending-approval notification for it.
        cancelNotification?.invoke(id)
        // [T-android-config-confirm-timeout] The newly-surfaced change also
        // needs a background nudge if the user is away.
        advancedTo?.let { notifyIfBackgrounded(it) }
        cont?.resume(outcome)
    }

    /**
     * [T-android-config-confirm-timeout] Notify for whatever is currently
     * front-of-queue. Called from the app's foreground → background transition
     * (AIEditorApp lifecycle callback): a dialog that was showing in the
     * foreground and is left behind when the user switches away should still
     * nudge them. Once-per-id via [notifiedIds]; the notifier itself no-ops
     * when the app is (still) foreground.
     */
    fun notifyPending() {
        _pending.value?.let { notifyIfBackgrounded(it) }
    }

    /**
     * Post the background notification for [change] at most once, recording the
     * id only when a notification was actually sent. The injected notifier
     * applies the foreground / setting / permission guards.
     */
    private fun notifyIfBackgrounded(change: PendingConfigChange) {
        val notifier = backgroundNotifier ?: return
        scope.launch {
            mutex.withLock {
                if (notifiedIds.contains(change.id)) return@launch
                // Still front-of-queue? (resolve could have advanced past it.)
                if (_pending.value?.id != change.id) return@launch
                if (notifier(change)) notifiedIds.add(change.id)
            }
        }
    }
}
