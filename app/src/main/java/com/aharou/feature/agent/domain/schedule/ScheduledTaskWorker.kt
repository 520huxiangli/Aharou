package com.aharou.feature.agent.domain.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.trigger.ConditionContext
import com.aharou.feature.agent.domain.schedule.trigger.ConditionEvaluator
import com.aharou.feature.agent.domain.schedule.trigger.ConditionResult
import com.aharou.feature.agent.domain.schedule.trigger.DeviceStateMonitor
import com.aharou.feature.agent.domain.schedule.trigger.DeviceStateSnapshot
import com.aharou.feature.agent.domain.schedule.trigger.EventPayload
import com.aharou.feature.agent.domain.schedule.trigger.EventTriggerBus
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
import com.aharou.feature.agent.domain.schedule.trigger.renderPrompt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 自动化任务检查 Worker：每 15 分钟醒一次，认领到点的任务并把它们派给会话层。
 *
 * 只做四件事——比对设备状态、查到期任务、用 compare-and-set 认领、发一条运行请求；真正的 AI 运行由
 * 会话层负责（见 [ScheduledRunBus]）。这样调度出问题不会拖垮会话链路，反之亦然。
 *
 * **两类触发共用一条认领路径**：时间类任务的 nextRunAt 由调度器算（到点冒头）；事件类任务
 * 自己不会冒头，由事件总线在事件发生那一刻把 nextRunAt 写成当时时刻投递进来，Worker 照旧
 * 认领。所以这里不需要为事件类单开分支——认领之后的一切判定对两类一致，行为不会分叉。
 * 反过来，事件类任务的 nextRunAt **只在被事件投递时短暂有效**，跑完就停放到 Long.MAX_VALUE：
 * 否则按时间算出来的值一到点就会被当成时间触发自己跑掉，那就不是事件触发了。
 * 同理，创建/启用事件类任务那边也必须按这个口径停放（否则还没发生事件就已经到点）。
 *
 * **错过不补跑**：迟到超过一个周期的（例如 App 很久没打开）只推进时间并记 `skipped_missed`，
 * 否则一开机可能连着补跑好几轮，既烧 token 也容易把会话刷满。
 * **为何不用 @HiltWorker**：见 MemorySyncWorker 的说明——那个注解的处理与 Dagger 的 KSP
 * 处理器在本工程内冲突，会把 Hilt 的 `*_GeneratedInjector` 整体搞丢。普通 Worker + [EntryPoint]
 * 可由 HiltWorkerFactory 的反射兜底正常构造。
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, ScheduledTaskEntryPoint::class.java
        )
        val dao = entryPoint.scheduledTaskDao()
        val runBus = entryPoint.scheduledRunBus()
        val scheduler = entryPoint.scheduledTaskScheduler()
        val eventBus = entryPoint.eventTriggerBus()
        val now = System.currentTimeMillis()

        // 每轮顺带比对一次设备状态（电量/充电/WiFi），变了就交给事件总线去命中 battery/network 类任务。
        // 电量与网络没有实时监听——静态注册在 Android 8 起被禁，实时盯得常驻前台服务，已否决——
        // 所以状态类事件最迟要等这一轮检查才发现，最大延迟一个周期（15 分钟），是设计取舍不是 bug。
        val state = entryPoint.deviceStateMonitor().check()
        if (state.hasChanges) {
            FileLogger.i(TAG, "设备状态变化：${state.summary()}")
            eventBus.dispatchDeviceStateChange(state)
        }

        val due = runCatching { dao.due(now) }.getOrElse { e ->
            FileLogger.e(TAG, "读取到点的定时任务失败", e)
            return Result.retry()
        }
        // 条件求值与事件现场都用上面那一次读数，整轮只读一次电量/充电状态。
        val conditions = ConditionContext(
            nowMillis = now,
            batteryPercent = state.current.batteryPercent,
            charging = state.current.charging,
        )

        due.forEach { task ->
            val once = task.scheduleType == ScheduledTaskEntity.TYPE_ONCE
            // 单次任务没有「下一次」；事件类任务的 nextRunAt 只是「被事件投递进来了」的标记，
            // 跑完同样没有下一次可言——都占一个远期值，等下一次事件再来改写它。
            val nextRunAt = if (once || TriggerType.isEventTrigger(task)) {
                Long.MAX_VALUE
            } else {
                ScheduledTaskScheduler.nextRunFor(task, now)
            }
            // 认领：只有 nextRunAt 仍是我们读到的旧值时才推进，抢不到就跳过（别的检查已处理）。
            val claimed = runCatching {
                dao.claim(
                    id = task.id,
                    expectedNextRunAt = task.nextRunAt,
                    nextRunAt = nextRunAt,
                    now = now
                )
            }.getOrElse { e ->
                FileLogger.e(TAG, "认领自动化任务失败: ${task.id}", e)
                return@forEach
            }
            if (claimed == 0) return@forEach
            if (once) dao.setEnabled(task.id, false, now)

            val lateMs = now - task.nextRunAt
            if (lateMs > graceMillis(task)) {
                dao.recordOutcome(task.id, OUTCOME_SKIPPED_MISSED, now)
                FileLogger.w(TAG, "自动化任务错过太久，未补跑: ${task.name}")
                return@forEach
            }

            // 过了迟到这关才轮到条件：条件求值 → 防抖 → 准入，哪一步不过都只推进时间并记下具体原因。
            val condition = ConditionEvaluator.evaluate(task, conditions)
            if (condition is ConditionResult.Unsatisfied) {
                dao.recordOutcome(task.id, OUTCOME_SKIPPED_CONDITION, now)
                FileLogger.w(TAG, "自动化任务条件不满足(${condition.failure})，跳过: ${task.name}")
                return@forEach
            }
            if (withinDebounce(task, now)) {
                dao.recordOutcome(task.id, OUTCOME_SKIPPED_DEBOUNCE, now)
                FileLogger.w(TAG, "自动化任务在防抖窗口内，跳过: ${task.name}")
                return@forEach
            }
            if (stillRunning(task, now)) {
                dao.recordOutcome(task.id, OUTCOME_SKIPPED_RUNNING, now)
                FileLogger.w(TAG, "自动化任务上一次还在跑，跳过: ${task.name}")
                return@forEach
            }

            val emitted = runBus.emit(
                ScheduledRunRequest(
                    taskId = task.id,
                    taskName = task.name,
                    prompt = renderPrompt(task.prompt, eventPayload(task, now, state.current, eventBus)),
                    targetSessionId = task.targetSessionId,
                    workspacePath = task.workspacePath
                )
            )
            dao.recordOutcome(task.id, if (emitted) OUTCOME_DISPATCHED else OUTCOME_SKIPPED, now)
            if (!emitted) FileLogger.w(TAG, "自动化任务投递失败（无消费方就绪）: ${task.name}")
        }

        // 认领改变了 nextRunAt，精确闹钟要跟着指向新的「最早到期」任务。
        runCatching { scheduler.rescheduleAlarm(applicationContext) }
            .onFailure { FileLogger.e(TAG, "重排精确闹钟失败", it) }
        return Result.success()
    }

    /**
     * 迟到多久还算「该跑」：周期任务给一个周期，定时与单次任务给两小时。
     * 提醒类任务晚了半天才响没有意义，宁可不跑。
     */
    private fun graceMillis(task: ScheduledTaskEntity): Long = when (task.scheduleType) {
        ScheduledTaskEntity.TYPE_DAILY, ScheduledTaskEntity.TYPE_ONCE -> 2 * 60 * 60 * 1000L
        else -> ScheduledTaskScheduler.normalizeInterval(task.intervalMinutes) * 60_000L
    }

    /**
     * 防抖只对事件触发有意义：一串同样的通知不该连着跑好几遍。时间类任务的节奏由
     * interval/scheduleType 决定，间隔本来就大于防抖窗口，判它只是噪音。
     *
     * 基准取认领前读到的 lastRunAt——claim 会把它刷成 now，拿认领后的值比就永远落在窗口内。
     */
    private fun withinDebounce(task: ScheduledTaskEntity, now: Long): Boolean {
        if (!TriggerType.isEventTrigger(task)) return false
        val window = task.debounceMinutes * 60_000L
        if (window <= 0L) return false
        val last = task.lastRunAt ?: return false
        return now - last < window
    }

    /**
     * runMode=single 的近似实现：链路里没有「上一次派发结束了吗」的回执——派发之后就交给会话层了，
     * 总线是单向的，这里也看不到会话状态。于是退化成「距上次运行不足 [RUNNING_GUARD_MINUTES] 分钟
     * 就当作还在跑」。对周期任务无影响（间隔远大于这个阈值），只挡住事件连着来时的重叠执行。
     * 基准同样是认领前的 lastRunAt。
     */
    private fun stillRunning(task: ScheduledTaskEntity, now: Long): Boolean {
        if (task.runMode != ScheduledTaskEntity.RUN_SINGLE) return false
        val last = task.lastRunAt ?: return false
        return now - last < RUNNING_GUARD_MINUTES * 60_000L
    }

    /**
     * 事件现场：优先用事件总线带进来的那份（通知标题/正文、WiFi 名这些只有事件发生那一刻才知道），
     * 缺的字段再用本轮快照补上；时间类任务与开机触发没有总线现场，走的就是补全这条路。
     */
    private fun eventPayload(
        task: ScheduledTaskEntity,
        now: Long,
        snapshot: DeviceStateSnapshot,
        eventBus: EventTriggerBus,
    ): EventPayload {
        val live = eventBus.takePayload(task.id)
        if (live == null) {
            return EventPayload(
                app = task.triggerPackage,
                batteryPercent = snapshot.batteryPercent,
                ssid = snapshot.ssid,
                timeMillis = now,
            )
        }
        return live.copy(
            app = live.app.ifEmpty { task.triggerPackage },
            batteryPercent = live.batteryPercent ?: snapshot.batteryPercent,
            ssid = live.ssid.ifEmpty { snapshot.ssid },
            timeMillis = if (live.timeMillis > 0L) live.timeMillis else now,
        )
    }

    private companion object {
        const val TAG = "ScheduledTaskWorker"
        const val OUTCOME_DISPATCHED = "dispatched"

        /** 保留兼容：投递失败等旧路径仍写它，UI 按「错过未补跑」展示。 */
        const val OUTCOME_SKIPPED = "skipped"

        const val OUTCOME_SKIPPED_MISSED = "skipped_missed"
        const val OUTCOME_SKIPPED_CONDITION = "skipped_condition"
        const val OUTCOME_SKIPPED_DEBOUNCE = "skipped_debounce"
        const val OUTCOME_SKIPPED_RUNNING = "skipped_running"

        /** runMode=single 的近似判据：见 [stillRunning]。 */
        const val RUNNING_GUARD_MINUTES = 5L
    }
}

/** Worker 不在 Hilt 的注入图里，依赖得经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledTaskEntryPoint {
    fun scheduledTaskDao(): ScheduledTaskDao
    fun scheduledRunBus(): ScheduledRunBus
    fun scheduledTaskScheduler(): ScheduledTaskScheduler

    /** 事件现场（通知标题/正文等）随事件暂存在总线里，派发时取走；也是状态变化事件的入口。 */
    fun eventTriggerBus(): EventTriggerBus

    /** 本轮设备状态快照：既给条件求值，也给事件总线判断状态变化。 */
    fun deviceStateMonitor(): DeviceStateMonitor
}