package com.aharou.feature.onboarding.domain

import com.aharou.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 新手引导协调器：连接 UI 状态机 [OnboardingStateHolder] 与持久化。
 *
 * 状态与步号一起落盘——只存状态的话，进程被杀重启后只能从第一步重演，
 * 用户以为已经走过的几步又得再点一遍。
 */
class OnboardingCoordinator(
    private val stateHolder: OnboardingStateHolder,
    private val onPersist: suspend (OnboardingStatus) -> Unit,
    private val scope: CoroutineScope,
    /** 步号持久化（1-based）；不传则只存状态，供不需要续跑的场景用。 */
    private val onPersistStep: suspend (Int) -> Unit = {}
) {
    /** 启动引导判定：仅当未完成/进行中时激活，并从 [startStep]（1-based）续跑。 */
    fun initialize(status: OnboardingStatus, startStep: Int = FIRST_STEP) {
        if (status == OnboardingStatus.IN_PROGRESS) {
            stateHolder.start(stepAt(startStep))
        }
    }

    /** 推进到下一步（或完成）。 */
    fun nextStep() {
        stateHolder.nextStep()
        persistCurrent()
    }

    /** 回退或跳转到指定步骤（如关闭弹窗时回退到触发按钮）。 */
    fun goToStep(targetStep: OnboardingStep) {
        stateHolder.goToStep(targetStep)
        persistCurrent()
    }

    /** 用户主动跳过引导。 */
    fun skip() {
        stateHolder.skip()
        persist(OnboardingStatus.SKIPPED)
    }

    /** 用户完成引导。 */
    fun complete() {
        stateHolder.completeNow()
        persist(OnboardingStatus.COMPLETED)
    }

    /** 重置并重新启动引导（设置页触发）。 */
    fun resetAndStart() {
        stateHolder.start()
        persist(OnboardingStatus.IN_PROGRESS)
        // 重来一遍就从第一步开始，旧步号不能留着
        scope.launch { onPersistStep(FIRST_STEP) }
    }

    /** 状态与步号一起写；结束态（完成/跳过）把步号复位，下次重新运行从第一步开始。 */
    private fun persistCurrent() {
        val current = stateHolder.state.value
        persist(current.status)
        if (current.active) {
            val step = current.step.stepIndex
            scope.launch { onPersistStep(step) }
        }
    }

    private fun persist(status: OnboardingStatus) {
        scope.launch { onPersist(status) }
        if (status != OnboardingStatus.IN_PROGRESS) {
            scope.launch { onPersistStep(FIRST_STEP) }
        }
    }

    private fun stepAt(index: Int): OnboardingStep =
        OnboardingStep.all.getOrElse(index - 1) { OnboardingStep.all.first() }

    private companion object {
        const val FIRST_STEP = 1
    }
}
