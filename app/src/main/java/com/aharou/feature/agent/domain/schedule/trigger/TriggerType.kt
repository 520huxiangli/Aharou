package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity

/**
 * 触发类型的判定。
 *
 * 取值本身定义在 [ScheduledTaskEntity]（与表列同源），这里只放跨模块共用的谓词，
 * 省得调度、求值、UI 各处在字符串常量上各判各的。
 */
object TriggerType {

    /** 全部取值，顺序即列表里的展示顺序。 */
    val ALL = listOf(
        ScheduledTaskEntity.TYPE_TIME,
        ScheduledTaskEntity.TYPE_NOTIFICATION,
        ScheduledTaskEntity.TYPE_BATTERY,
        ScheduledTaskEntity.TYPE_NETWORK,
        ScheduledTaskEntity.TYPE_BOOT,
        ScheduledTaskEntity.TYPE_APP_START,
    )

    fun isTimeTrigger(type: String): Boolean = type == ScheduledTaskEntity.TYPE_TIME

    fun isTimeTrigger(task: ScheduledTaskEntity): Boolean = isTimeTrigger(task.triggerType)

    /** 事件类触发：靠外部事件发生，没有「到点」这回事。 */
    fun isEventTrigger(task: ScheduledTaskEntity): Boolean = !isTimeTrigger(task)

    fun isKnown(type: String): Boolean = type in ALL
}