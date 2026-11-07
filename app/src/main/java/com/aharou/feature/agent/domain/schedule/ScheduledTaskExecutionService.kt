package com.aharou.feature.agent.domain.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.domain.runner.AgentTurnCompletion
import com.aharou.feature.agent.domain.runner.AgentTurnResult
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.AgentTurnTrigger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 到点的自动化任务的后台执行者。
 *
 * 任务到点时主界面可能不在前台（甚至进程刚被拉起），ViewModel 收不到运行请求；
 * Worker 这时启动本服务，服务用自己的作用域调 [AgentTurnRunner] 把任务跑完——
 * 与 QQ/微信机器人后台回消息、语音通话后台回合同一套无界面执行链路。
 * 跑完（或失败）即 stopSelf，通知栏的前台通知随之消失。
 */
@AndroidEntryPoint
class ScheduledTaskExecutionService : Service() {

    @Inject
    lateinit var agentTurnRunner: AgentTurnRunner

    @Inject
    lateinit var scheduledTaskDao: ScheduledTaskDao

    @Inject
    lateinit var sessionUseCase: SessionUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val executions = ScheduledTaskExecutions()
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        executions.onStart(startId)
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        val taskName = intent?.getStringExtra(EXTRA_TASK_NAME) ?: taskId.orEmpty()
        val claimedAt = intent?.takeIf { it.hasExtra(EXTRA_CLAIMED_AT) }?.getLongExtra(EXTRA_CLAIMED_AT, 0L)
        if (!startForegroundWithNotification(taskName)) {
            if (taskId != null) serviceScope.launch {
                recordOutcome(taskId, taskName, claimedAt, ScheduledTaskOutcome.FAILED)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            executions.cancelAll()
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (taskId.isNullOrBlank() || intent == null || !intent.hasExtra(EXTRA_PROMPT)) {
            FileLogger.w(TAG, "自动化任务服务缺少参数，跳过本次请求")
            stopIfIdle()
            return START_NOT_STICKY
        }
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val workspacePath = intent.getStringExtra(EXTRA_WORKSPACE_PATH)
        val targetSessionId = intent.getStringExtra(EXTRA_TARGET_SESSION_ID)
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            runTask(taskId, taskName, prompt, workspacePath, targetSessionId, claimedAt)
        }
        executions.register(startId, job)
        job.invokeOnCompletion {
            executions.finish(startId)?.let { stopSelfResult(it) }
        }
        job.start()
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (promoted) executions.idleStartId()?.let { stopSelfResult(it) }
    }

    private suspend fun runTask(
        taskId: String,
        taskName: String,
        prompt: String,
        workspacePath: String?,
        targetSessionId: String?,
        claimedAt: Long?,
    ) {
        var outcome = ScheduledTaskOutcome.FAILED
        try {
            val existing = targetSessionId?.takeIf { it.isNotBlank() }?.let { sessionUseCase.getSessionById(it) }
            val effectiveRoot = existing?.workspacePath ?: workspacePath?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Scheduled task has no session or workspace")
            val sessionId = existing?.id ?: agentTurnRunner.ensureSession(effectiveRoot)
            val completion = AgentTurnCompletion()
            val result = coroutineScope {
                val execution = launch {
                    try {
                        agentTurnRunner.run(
                            AgentTurnRequest(
                                sessionId = sessionId,
                                text = prompt,
                                projectRoot = effectiveRoot,
                                isAutoTrigger = true,
                                trigger = AgentTurnTrigger.SCHEDULED_TASK,
                                skipTitleUpdate = true,
                            )
                        ).collect { event ->
                            completion.observe(event)
                            if (event is AgentEvent.Failed) {
                                FileLogger.w(TAG, "自动化任务执行失败: $taskName - ${event.error}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        completion.fail(e)
                        FileLogger.e(TAG, "自动化任务服务执行异常: $taskName", e)
                    }
                }
                completion.bind(execution)
                completion.await()
            }
            outcome = when (result) {
                AgentTurnResult.COMPLETED -> ScheduledTaskOutcome.COMPLETED
                AgentTurnResult.FAILED -> ScheduledTaskOutcome.FAILED
                AgentTurnResult.CANCELLED -> ScheduledTaskOutcome.FAILED
            }
        } catch (e: CancellationException) {
            outcome = ScheduledTaskOutcome.FAILED
            FileLogger.i(TAG, "自动化任务已取消: $taskName")
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "自动化任务服务执行异常: $taskName", e)
        } finally {
            withContext(NonCancellable) { recordOutcome(taskId, taskName, claimedAt, outcome) }
        }
    }

    private suspend fun recordOutcome(taskId: String, taskName: String, claimedAt: Long?, outcome: String) {
        runCatching { scheduledTaskDao.recordOutcomeForRun(taskId, claimedAt, outcome, System.currentTimeMillis()) }
            .onFailure { FileLogger.w(TAG, "回写自动化任务结果失败: $taskName", it) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 8+ 要求通知频道先建后用，否则 startForeground 抛 Bad notification 直接崩。 */
    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.scheduled_task_service_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundWithNotification(taskName: String): Boolean {
        if (promoted) return true
        fun promote(notification: Notification) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
        val primary = runCatching { promote(buildNotification(taskName)) }
        if (primary.isSuccess) {
            promoted = true
            return true
        }
        FileLogger.e(TAG, "启动自动化任务前台通知失败", primary.exceptionOrNull())
        val fallback = runCatching {
            promote(NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(TAG)
                .setOngoing(true)
                .build())
        }
        promoted = fallback.isSuccess
        if (!promoted) FileLogger.e(TAG, "兜底自动化任务前台通知失败", fallback.exceptionOrNull())
        return promoted
    }

    private fun buildNotification(taskName: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScheduledTaskExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.scheduled_task_service_title))
            .setContentText(taskName)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.im_service_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ScheduledTaskExecutionService"
        private const val CHANNEL_ID = "aharou_scheduled_task_execution"
        private const val NOTIFICATION_ID = 42

        const val ACTION_STOP = "com.aharou.agent.action.SCHEDULED_TASK_STOP"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_NAME = "task_name"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_WORKSPACE_PATH = "workspace_path"
        const val EXTRA_TARGET_SESSION_ID = "target_session_id"
        const val EXTRA_CLAIMED_AT = "claimed_at"

        /** Worker 投递失败时启动本服务；Android 8+ 必须走 startForegroundService。 */
        fun start(context: Context, taskId: String, taskName: String, prompt: String, workspacePath: String?, targetSessionId: String?, claimedAt: Long? = null) {
            val intent = Intent(context, ScheduledTaskExecutionService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_WORKSPACE_PATH, workspacePath)
                putExtra(EXTRA_TARGET_SESSION_ID, targetSessionId)
                if (claimedAt != null) putExtra(EXTRA_CLAIMED_AT, claimedAt)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
