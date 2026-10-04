package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时任务：到点后在指定会话（[targetSessionId] 为空时每次新建会话）里跑一轮 AI。
 *
 * 表里只存「任务定义 + 调度状态」；实际那轮运行仍走普通会话链路，不另造一套 agent 循环。
 * [nextRunAt] 由 [com.aharou.feature.agent.data.local.dao.ScheduledTaskDao.claim] 用
 * compare-and-set 推进，保证同一到点时刻不会被重复触发。
 */
@Entity(
    tableName = "scheduled_tasks",
    indices = [Index(value = ["enabled", "nextRunAt"])]
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    /** 任务名：出现在设置页、会话预览与通知里。 */
    val name: String,
    /** 每次运行下发的指令。 */
    val prompt: String,
    /** 在哪个会话里跑；null 表示每次新建会话。 */
    val targetSessionId: String? = null,
    /** 新建会话时使用的工程工作区；在既有会话里跑时以该会话自己的工作区为准。 */
    val workspacePath: String? = null,
    /** 周期（分钟）。WorkManager 周期任务最短 15 分钟，保存时按此下限收敛。 */
    val intervalMinutes: Int,
    val enabled: Boolean = true,
    /** 下次运行时间（epoch 毫秒）。 */
    val nextRunAt: Long,
    /** 上次运行时间；从未运行为 null。 */
    val lastRunAt: Long? = null,
    /** 上次结果：dispatched（已派发）/ skipped（错过未补跑）。 */
    val lastOutcome: String? = null,
    /** 已运行次数。 */
    val runCount: Int = 0,
    /** 运行次数上限；0 表示不限。 */
    val maxRuns: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)
