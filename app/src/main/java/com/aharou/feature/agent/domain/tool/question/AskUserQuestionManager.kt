package com.aharou.feature.agent.domain.tool.question

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理 ask_user_question 工具的 park-and-resume 流程。
 *
 * 镜像 [com.aharou.feature.agent.domain.tool.ToolPermissionManager] 的设计：
 * 工具侧调用 [awaitAnswer] 挂起；UI 侧观察 [pendingQuestion] 渲染面板；
 * 用户选择后调用 [resolve] 唤醒工具侧。
 *
 * 同一时刻只允许一个待决问题（mutex 串行化），与权限弹窗互不干扰。
 *
 * 两个与「进程会死」有关的约束：
 * - 提问先落库（[PendingUserQuestionStore]），进程被杀后 [init] 会把待答问题恢复回面板；
 * - 等待有上限（[MAX_WAIT_MS]），超时给工具侧一个明确的失败结果，绝不让工具永久挂起。
 *
 * [store] 允许缺省（见无参构造）：单元测试拿不到数据库时只是失去重启恢复能力。
 */
@Singleton
class AskUserQuestionManager @Inject constructor(
    private val store: PendingUserQuestionStore?
) {

    /** 单元测试直接无参构造：不接数据库，仅失去重启恢复能力。 */
    constructor() : this(null)

    private companion object {
        const val TAG = "AskUserQuestion"

        /** 最长等待上限：超过就判失效，工具拿到失败结果，面板也不会永远挂在屏幕上。 */
        const val MAX_WAIT_MS = 30L * 60 * 1000

        /** 重启恢复时若剩余可等时间不足这个数，直接判失效，以免恢复出一个马上过期的问题。 */
        const val MIN_RESTORE_WAIT_MS = 60L * 1000
    }

    private val requestMutex = Mutex()

    /** 只保护「当前展示的这个问题」这几个字段，别把挂起等待也包进来。 */
    private val lock = Any()

    private val _pendingQuestion = MutableStateFlow<PendingUserQuestion?>(null)

    /** UI 观察此 StateFlow 来决定是否显示问题面板。 */
    val pendingQuestion: StateFlow<PendingUserQuestion?> = _pendingQuestion.asStateFlow()

    private var currentId: String? = null
    private var currentDecision: CompletableDeferred<UserQuestionAnswer>? = null

    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restoreJob: Job? = null

    init {
        store?.let { pendingStore -> restoreJob = restoreScope.launch { restore(pendingStore) } }
    }

    /**
     * 工具侧调用：发起一个问题请求，落库后挂起等待用户回答（最多 [MAX_WAIT_MS]）。
     * 同一时刻只允许一个请求（由 [requestMutex] 保证串行）。
     */
    suspend fun awaitAnswer(question: PendingUserQuestion): AskUserQuestionResult = requestMutex.withLock {
        // 恢复出来的旧问题让位：面板同一时刻只能显示一个问题，而它是没人接续的僵尸等待。
        cancelRestore()
        store?.persist(question)
        val answer = waitForAnswer(question, MAX_WAIT_MS)
        if (answer == null) {
            FileLogger.i(TAG, "提问等待超时（${MAX_WAIT_MS / 60_000} 分钟），判失效 id=${question.id}")
            markCancelled(question.id, "等待超时")
            AskUserQuestionResult.Timeout(MAX_WAIT_MS)
        } else {
            store?.markAnswered(question.id, answer)
            AskUserQuestionResult.Answered(answer)
        }
    }

    /**
     * UI / 通知侧调用：用户完成选择后，将答案回传，唤醒挂起的 [awaitAnswer]。
     *
     * @return 是否命中当前问题；通知动作据此识别陈旧点击（问题已被别的入口答掉了）。
     */
    fun resolve(id: String, answer: UserQuestionAnswer): Boolean {
        val decision = synchronized(lock) {
            if (currentId != id) return false
            _pendingQuestion.value = null
            currentDecision.also { currentDecision = null }
        } ?: return false
        decision.complete(answer)
        return true
    }

    /** 展示问题并等待答案；超时或取消时返回 null（正常返回时面板已清空）。 */
    private suspend fun waitForAnswer(question: PendingUserQuestion, timeoutMs: Long): UserQuestionAnswer? {
        val decision = CompletableDeferred<UserQuestionAnswer>()
        synchronized(lock) {
            currentId = question.id
            currentDecision = decision
            _pendingQuestion.value = question
        }
        return try {
            withTimeoutOrNull(timeoutMs) { decision.await() }
        } finally {
            // 按 id 清：恢复的旧等待被取消时，可能已经有新的问题占住了面板。
            synchronized(lock) {
                if (currentId == question.id) {
                    currentId = null
                    currentDecision = null
                    _pendingQuestion.value = null
                }
            }
        }
    }

    /** 进程重启后把上一轮没答完的问题重新亮出来；超龄或读失败一律判失效。 */
    private suspend fun restore(pendingStore: PendingUserQuestionStore) {
        val restored = pendingStore.latestPending() ?: return
        val age = (System.currentTimeMillis() - restored.createdAt).coerceAtLeast(0L)
        val remaining = MAX_WAIT_MS - age
        if (remaining < MIN_RESTORE_WAIT_MS) {
            FileLogger.i(TAG, "待答问题 ${restored.question.id} 已超龄（${age / 1000}s），判失效")
            markCancelled(restored.question.id, "重启时已超过最长等待上限")
            return
        }
        if (synchronized(lock) { _pendingQuestion.value != null }) {
            FileLogger.w(TAG, "面板已有待答问题，跳过恢复 ${restored.question.id}")
            return
        }
        FileLogger.i(TAG, "恢复待答问题 ${restored.question.id}，剩余可等待 ${remaining / 1000}s")
        val answer = try {
            waitForAnswer(restored.question, remaining)
        } catch (e: CancellationException) {
            markCancelled(restored.question.id, "被新的提问顶替")
            throw e
        }
        if (answer != null) {
            pendingStore.markAnswered(restored.question.id, answer)
        } else {
            FileLogger.i(TAG, "恢复的待答问题 ${restored.question.id} 等待超时，判失效")
            markCancelled(restored.question.id, "恢复后等待超时")
        }
    }

    /** 新的实时提问要占面板：取消恢复出来的旧等待（它没人接续，答案无处交付）。 */
    private fun cancelRestore() {
        restoreJob?.cancel()
        restoreJob = null
    }

    /** 判失效的落库必须在取消中也执行，否则恢复的等待被取消时永远留在 PENDING。 */
    private suspend fun markCancelled(id: String, reason: String) {
        val pendingStore = store ?: return
        withContext(NonCancellable) { pendingStore.markCancelled(id, reason) }
    }
}
