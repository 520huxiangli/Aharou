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
    /**
     * 调度类型：[TYPE_INTERVAL] 周期 / [TYPE_DAILY] 每天固定时刻 / [TYPE_ONCE] 单次。
     * 新增于库版本 71，旧数据迁移后统一为 interval，行为与升级前一致。
     */
    val scheduleType: String = TYPE_INTERVAL,
    /** 每天固定时刻用：当天内的分钟数 0-1439（默认 09:00）。 */
    val dailyMinuteOfDay: Int = 9 * 60,
    /** 单次任务用：触发时刻（epoch 毫秒）；0 表示未设置。 */
    val onceAtMillis: Long = 0L,
    /**
     * 靠什么触发：[TYPE_TIME] 到点 / [TYPE_NOTIFICATION] 收到通知 / [TYPE_BATTERY] 电量变化 /
     * [TYPE_NETWORK] 网络变化 / [TYPE_BOOT] 开机。新增于库版本 72，旧数据统一为 time。
     *
     * 与 [scheduleType] 是两个维度：只有 [TYPE_TIME] 才看 [scheduleType]，也只有它的
     * [nextRunAt] 是调度器算出来的；事件类的 [nextRunAt] 由事件总线在触发那一刻写入。
     */
    val triggerType: String = TYPE_TIME,
    /** notification 用：只匹配这个包名（应用）的通知；空串表示任意应用的通知都触发。 */
    val triggerPackage: String = "",
    /** battery 用：电量降到该百分比（0-100）时触发；-1 表示不用这个条件。 */
    val triggerBatteryBelow: Int = -1,
    /** battery 用：插上充电时触发。 */
    val triggerOnCharging: Boolean = false,
    /** network 用：只匹配该 WiFi 名；空串表示任意网络变化都触发。 */
    val triggerSsid: String = "",
    /** 条件：只在当天第 [conditionWindowFrom] 分钟到第 [conditionWindowTo] 分钟之间运行；-1 表示该端不限。 */
    val conditionWindowFrom: Int = -1,
    /** 见 [conditionWindowFrom]；from > to 表示窗口跨午夜（如 23:00~06:00）。 */
    val conditionWindowTo: Int = -1,
    /** 条件：必须在充电中才运行。 */
    val condRequireCharging: Boolean = false,
    /** 条件：电量不得低于该百分比才运行；-1 表示不限。 */
    val condMinBattery: Int = -1,
    /** 防抖窗口（分钟）：窗口内同类事件重复到来只跑一次；0 表示不防抖。只对事件触发生效。 */
    val debounceMinutes: Int = 10,
    /** 运行模式：[RUN_SINGLE] 上一次派发还没跑完就跳过本次。 */
    val runMode: String = RUN_SINGLE,
    val enabled: Boolean = true,
    /** 下次运行时间（epoch 毫秒）。 */
    val nextRunAt: Long,
    /** 上次运行时间；从未运行为 null。 */
    val lastRunAt: Long? = null,
    /**
     * 上次结果（取值与 [ScheduledTaskWorker] 的 `OUTCOME_*` 一致）：dispatched 已派发；
     * skipped_missed 错过太久；skipped_condition 条件不满足；skipped_debounce 在防抖窗口内；
     * skipped_running 上一次还在跑；skipped 投递失败。
     */
    val lastOutcome: String? = null,
    /** 已运行次数。 */
    val runCount: Int = 0,
    /** 运行次数上限；0 表示不限。 */
    val maxRuns: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        /** 周期：每 intervalMinutes 跑一次。 */
        const val TYPE_INTERVAL = "interval"

        /** 每天固定时刻跑一次（dailyMinuteOfDay）。 */
        const val TYPE_DAILY = "daily"

        /** 只跑一次（onceAtMillis），跑完自动停用。 */
        const val TYPE_ONCE = "once"

        /** 时间触发：到点就跑，此时才看 [scheduleType]。 */
        const val TYPE_TIME = "time"

        /** 收到通知触发（可按 [triggerPackage] 只看某个应用）。 */
        const val TYPE_NOTIFICATION = "notification"

        /** 电量变化触发（看 [triggerBatteryBelow] / [triggerOnCharging]）。 */
        const val TYPE_BATTERY = "battery"

        /** 网络变化触发（看 [triggerSsid]）。 */
        const val TYPE_NETWORK = "network"

        /** 开机触发。 */
        const val TYPE_BOOT = "boot"

        /** 同一任务上一次派发还没跑完就跳过本次。 */
        const val RUN_SINGLE = "single"
    }
}
