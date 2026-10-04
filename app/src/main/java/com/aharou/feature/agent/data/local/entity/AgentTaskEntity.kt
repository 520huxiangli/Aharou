package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 子代理任务状态。
 *
 * SPAWNED 事件是「子会话已创建、工作流即将启动」的唯一可观测信号，任务落库即为
 * [RUNNING]，此后只能被终态事件或启动时的断点恢复改写，故没有可观测的排队阶段。
 */
enum class AgentTaskStatus {
    /** 已派发、正在执行。 */
    RUNNING,
    /** 子代理正常跑到结束。 */
    COMPLETED,
    /** 子代理执行报错结束。 */
    FAILED,
    /** 被主动停止（task stop / 删除子会话）。 */
    CANCELLED,
    /** 应用进程被关闭导致的遗留任务，启动时由断点恢复流程标记。 */
    INTERRUPTED
}

/**
 * 子代理任务：一次 task 派发 = 一个子会话 = 一条记录，状态由
 * [com.aharou.feature.agent.data.local.task.SubAgentTaskTracker] 订阅子代理事件驱动。
 *
 * 与只存在于内存的运行态不同，这张表让「子代理跑到一半进程被杀」这件事可追溯。
 */
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["parentSessionId"]),
        Index(value = ["status"])
    ]
)
data class AgentTaskEntity(
    /** 任务 id：即子代理会话 id（子会话与任务一一对应）。 */
    @PrimaryKey val id: String,
    /** 派生该子代理的父会话 id。 */
    val parentSessionId: String,
    /** 派发时下发的任务指令（SPAWNED 事件携带）。 */
    val instruction: String,
    /** 取值见 [AgentTaskStatus]。 */
    val status: String,
    /** 结束原因：FAILED 为错误信息，CANCELLED 为停止说明，INTERRUPTED 为中断说明；正常完成与运行中为 null。 */
    val result: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** 进入终态的时间；运行中为 null。 */
    val finishedAt: Long? = null
)
