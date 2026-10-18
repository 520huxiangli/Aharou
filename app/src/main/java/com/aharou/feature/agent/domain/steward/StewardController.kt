package com.aharou.feature.agent.domain.steward

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管控制器：每轮 AI 回答结束（空闲）时旁路问一次「用户最初那句做完了没」，
 * 没做完就把下一步指令送回同一个会话。
 *
 * 只负责判定与轮次记账，发送动作由会话链路通过 [onAiIdle] 的 send 回调注入——
 * 控制器不反向依赖 ViewModel，也不碰界面。
 */
@Singleton
class StewardController @Inject constructor(
    private val settings: StewardSettings,
    private val judge: StewardJudge,
) {
    private val _state = MutableStateFlow(StewardState())
    val state: StateFlow<StewardState> = _state.asStateFlow()

    /** 用户最初那句任务：判断「完成没」的基准，开托管时由界面侧写入。 */
    private var anchorInstruction: String = ""
    /** 基准所属会话：判定用它的供应商/模型，跟会话本身走而不是全局默认。 */
    private var anchorSessionId: String? = null
    private var loopCount = 0

    /** 同一轮不重入：判定还在跑时又来一次空闲回调，直接忽略。 */
    private val judging = AtomicBoolean(false)

    val enabled: Boolean get() = settings.enabled

    init {
        // 基准指令只活在进程内，重启后没有基准就无从判断——把开关按实际能力收回来，
        // 免得界面显示「开着」却什么也不做。
        if (settings.enabled) settings.enabled = false
        publish(StewardStatus.Idle)
    }

    fun enable(anchorInstruction: String, sessionId: String? = null) {
        this.anchorInstruction = anchorInstruction
        this.anchorSessionId = sessionId
        loopCount = 0
        settings.enabled = true
        publish(StewardStatus.Monitoring)
    }

    fun disable() {
        settings.enabled = false
        judging.set(false)
        publish(StewardStatus.Stopped)
    }

    fun setMaxLoops(value: Int) {
        settings.maxLoops = value
        publish(_state.value.status)
    }

    fun setUnlimited(value: Boolean) {
        settings.unlimited = value
        publish(_state.value.status)
    }

    /**
     * AI 空闲（本轮结束）时调用一次。
     *
     * @param lastReport 本轮 AI 的最后汇报文本。
     * @param send 把下一步指令送进同一会话的既有发送链路。
     * @return 是否已自动续发（true = 又开了一轮）。
     */
    suspend fun onAiIdle(lastReport: String, send: suspend (String) -> Unit): Boolean {
        if (!settings.enabled) return false
        if (anchorInstruction.isBlank()) return false
        if (!judging.compareAndSet(false, true)) return false
        try {
            publish(StewardStatus.Judging)
            val judgement = judge.judge(anchorInstruction, lastReport, anchorSessionId)
            if (judgement == null) {
                publish(StewardStatus.Monitoring)
                return false
            }
            if (judgement.completed) {
                FileLogger.d(TAG, "任务判定为已完成：${judgement.reason}")
                publish(StewardStatus.Completed)
                return false
            }
            val next = judgement.nextInstruction?.trim().orEmpty()
            if (next.isEmpty()) {
                // 判了「没完成」却给不出下一步：没有可发的指令，停在这里，不进死循环。
                FileLogger.w(TAG, "判定未完成但没给下一步指令，停止接管")
                publish(StewardStatus.Stopped)
                return false
            }
            val limit = if (settings.unlimited) HARD_MAX_LOOPS else settings.maxLoops
            if (loopCount >= limit) {
                if (settings.unlimited) {
                    FileLogger.w(TAG, "自动循环已达安全阀 $HARD_MAX_LOOPS 轮，停止接管")
                }
                publish(StewardStatus.Stopped)
                return false
            }
            loopCount++
            publish(StewardStatus.AutoSending)
            send(next)
            publish(StewardStatus.Monitoring)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "托管续发失败，跳过: ${e.message}", e)
            publish(StewardStatus.Monitoring)
            return false
        } finally {
            judging.set(false)
        }
    }

    private fun publish(status: StewardStatus) {
        _state.value = StewardState(
            enabled = settings.enabled,
            checking = status == StewardStatus.Judging,
            loopCount = loopCount,
            status = status,
        )
    }

    companion object {
        private const val TAG = "StewardController"

        /** 安全阀：勾了「不限轮数」也最多自动续发这么多轮，避免空转烧额度。 */
        const val HARD_MAX_LOOPS = 50
    }
}