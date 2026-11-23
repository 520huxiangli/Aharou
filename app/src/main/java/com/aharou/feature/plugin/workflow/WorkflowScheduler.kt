package com.aharou.feature.plugin.workflow

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 工作流调度：到点捞出要跑的工作流并执行，跑完推进下一次。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`core/workflow/WorkflowScheduler.kt` +
 * `WorkflowWorker.kt` + `WorkflowSchedulerInitializer.kt`，**不搬它那套「一工作流一个
 * PeriodicWork」的做法**，而是接到 Aharou 已有的调度形态上：
 * - 周期兜底用一条 15 分钟一次的 WorkManager 周期任务（与 [ScheduledTaskScheduler] 同一个节拍、
 *   同一个最小周期常量），到点检查所有工作流的 `nextRunAt`；
 * - 准点靠一条延迟到「最早到期时刻」的一次性 WorkManager 任务，跑完再重排。
 *
 * 这样不新增 AlarmManager 体系，也不需要为每个工作流各挂一条 WorkManager 记录；
 * 代价是间隔小于 15 分钟的调度只能按 15 分钟精度触发，与自动化任务一致。
 */
@Singleton
class WorkflowScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: WorkflowStore,
    private val executor: WorkflowExecutor,
) {

    private val runLock = Mutex()

    /**
     * 重算所有工作流的下一次到期时刻并重排唤醒。
     * 工作流增删改、启停、模板导入、以及每次到点执行后都调用它。
     */
    suspend fun reschedule() = withContext(Dispatchers.IO) {
        runLock.withLock {
            val now = System.currentTimeMillis()
            val nextTimes = mutableListOf<Long>()
            for (workflow in store.list()) {
                if (!workflow.enabled || workflow.scheduleFinished) continue
                val trigger = WorkflowTriggers.scheduleTrigger(workflow) ?: continue
                if (!WorkflowTriggers.isEnabled(trigger)) continue
                var armed = workflow
                if (armed.nextRunAt == null) {
                    val next = WorkflowTriggers.nextRunAt(trigger, now)
                    if (next == null) {
                        FileLogger.w(TAG, "工作流调度无法计算下一次时间，跳过: ${workflow.id} ${WorkflowTriggers.describe(trigger)}")
                        continue
                    }
                    armed = store.save(armed.copy(nextRunAt = next))
                }
                armed.nextRunAt?.let { nextTimes.add(it) }
            }
            if (nextTimes.isEmpty()) {
                cancel(context)
            } else {
                enqueueTick(context)
                nextTimes.minOrNull()?.let { armWake(context, it) }
            }
        }
    }

    /**
     * 跑所有到期的工作流，返回执行的条数。
     *
     * 单条工作流失败只记账（[WorkflowRunRecord] + 统计字段），不影响同批其他工作流。
     */
    suspend fun runDue(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val executed = runLock.withLock {
            val due = store.list().filter { workflow ->
                workflow.enabled &&
                    !workflow.scheduleFinished &&
                    (workflow.nextRunAt ?: Long.MAX_VALUE) <= now
            }
            var count = 0
            for (workflow in due) {
                val trigger = WorkflowTriggers.scheduleTrigger(workflow) ?: continue
                if (!WorkflowTriggers.isEnabled(trigger)) continue
                // 迟到太久不补跑：开机/休眠后把积压的调度一次性全跑一遍没有意义。
                val scheduledAt = workflow.nextRunAt ?: now
                val repeat = WorkflowTriggers.repeats(trigger)
                if (now - scheduledAt > MISSED_GRACE_MS) {
                    FileLogger.w(TAG, "工作流调度迟到过久，跳过本次: ${workflow.name} (${workflow.id})")
                    store.save(
                        workflow.copy(
                            nextRunAt = if (repeat) WorkflowTriggers.nextRunAt(trigger, now) else null,
                            scheduleFinished = !repeat,
                        )
                    )
                    continue
                }
                runScheduled(workflow, trigger)
                count++
            }
            count
        }
        reschedule()
        executed
    }

    /** 手动触发：立即执行一次，不动调度时间。 */
    suspend fun triggerNow(workflowId: String, triggerNodeId: String? = null): WorkflowExecutionResult =
        withContext(Dispatchers.IO) {
            runLock.withLock {
                val workflow = store.load(workflowId)
                    ?: return@withLock WorkflowExecutionResult(
                        workflowId = workflowId,
                        success = false,
                        nodeResults = emptyMap(),
                        message = "工作流不存在: $workflowId",
                    )
                // 没指定触发节点时优先走「手动触发」，没有手动触发节点就退回第一个触发节点：
                // 插件面板里的「点一下就跑」不能因为模板只配了定时触发而直接报错。
                val resolvedTrigger = triggerNodeId
                    ?: workflow.nodes.filterIsInstance<TriggerNode>()
                        .firstOrNull { it.triggerType == "manual" }?.id
                    ?: workflow.nodes.filterIsInstance<TriggerNode>().firstOrNull()?.id
                executeAndRecord(
                    workflow = workflow,
                    triggerNodeId = resolvedTrigger,
                    triggerExtras = mapOf("source" to "manual"),
                )
            }
        }

    private suspend fun runScheduled(workflow: Workflow, trigger: TriggerNode) {
        val now = System.currentTimeMillis()
        val repeat = WorkflowTriggers.repeats(trigger)
        val result = executeAndRecord(
            workflow = workflow,
            triggerNodeId = trigger.id,
            triggerExtras = mapOf(
                "source" to "schedule",
                "schedule_type" to WorkflowTriggers.scheduleType(trigger).orEmpty(),
                "scheduled_at" to (workflow.nextRunAt ?: now).toString(),
            ),
        )
        // 执行过程中的保存（统计）以磁盘上的最新版本为准，避免覆盖掉别的字段。
        val latest = store.load(workflow.id) ?: workflow
        store.save(
            latest.copy(
                nextRunAt = if (repeat) WorkflowTriggers.nextRunAt(trigger, now) else null,
                scheduleFinished = !repeat,
            )
        )
        FileLogger.i(
            TAG,
            "工作流定时执行${if (result.success) "成功" else "失败"}: ${workflow.name} (${workflow.id})",
        )
    }

    private suspend fun executeAndRecord(
        workflow: Workflow,
        triggerNodeId: String?,
        triggerExtras: Map<String, String>,
    ): WorkflowExecutionResult {
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            executor.execute(workflow, triggerNodeId = triggerNodeId, triggerExtras = triggerExtras)
        }.getOrElse { error ->
            FileLogger.e(TAG, "工作流执行异常: ${workflow.id}", error)
            WorkflowExecutionResult(
                workflowId = workflow.id,
                success = false,
                nodeResults = emptyMap(),
                message = "工作流执行异常: ${error.message}",
            )
        }
        val finishedAt = System.currentTimeMillis()
        val latest = store.load(workflow.id) ?: workflow
        store.save(
            latest.copy(
                lastExecutionTime = finishedAt,
                lastExecutionStatus = if (result.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                totalExecutions = latest.totalExecutions + 1,
                successfulExecutions = latest.successfulExecutions + if (result.success) 1 else 0,
                failedExecutions = latest.failedExecutions + if (result.success) 0 else 1,
            )
        )
        store.appendRun(
            WorkflowRunRecord(
                runId = UUID.randomUUID().toString(),
                workflowId = workflow.id,
                workflowName = workflow.name,
                triggerNodeId = triggerNodeId,
                startedAt = startedAt,
                finishedAt = finishedAt,
                success = result.success,
                message = result.message,
                failedNodes = result.nodeResults.filterValues { it is NodeExecutionState.Failed }.keys.toList(),
                skippedNodes = result.nodeResults.filterValues { it is NodeExecutionState.Skipped }.keys.toList(),
            )
        )
        return result
    }

    companion object {
        private const val TAG = "WorkflowScheduler"

        private const val UNIQUE_TICK = "plugin-workflow-tick"
        private const val UNIQUE_WAKE = "plugin-workflow-wake"
        private const val KEY_WAKE_AT = "wake_at"

        /** 周期兜底的节拍：与自动化任务共用 15 分钟下限。 */
        private val TICK_MINUTES = ScheduledTaskScheduler.MIN_INTERVAL_MINUTES.toLong()

        /** 迟到多久就不补跑：两个节拍之外视为「错过了」，只推进下一次。 */
        private const val MISSED_GRACE_MS = 12 * 60 * 60 * 1000L

        fun enqueueTick(context: Context) {
            val request = PeriodicWorkRequestBuilder<WorkflowWorker>(TICK_MINUTES, TimeUnit.MINUTES).build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_TICK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }.onFailure { FileLogger.w(TAG, "注册工作流周期检查失败", it) }
        }

        /** 排一条「在 [atMillis] 醒来」的一次性任务；已存在则替换。 */
        fun armWake(context: Context, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<WorkflowWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_WAKE_AT to atMillis))
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WAKE,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }.onFailure { FileLogger.w(TAG, "注册工作流准点唤醒失败", it) }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_TICK)
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WAKE)
            }.onFailure { FileLogger.w(TAG, "撤销工作流调度失败", it) }
        }
    }
}
