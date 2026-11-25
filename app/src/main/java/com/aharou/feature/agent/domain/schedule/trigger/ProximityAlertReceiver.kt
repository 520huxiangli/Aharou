package com.aharou.feature.agent.domain.schedule.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import dagger.hilt.android.EntryPointAccessors

/**
 * 位置围栏进出广播。
 *
 * 系统在设备进入/离开围栏时发这条广播，`KEY_PROXIMITY_ENTERING` 标出方向。只认自己注册的
 * action，别的 action 一律丢弃——围栏的 PendingIntent 是 MUTABLE 的，不校验 action 等于
 * 给任何能往这条 Intent 里塞东西的组件一个白送的触发入口。
 */
class ProximityAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceTriggerManager.ACTION_PROXIMITY) return
        val taskId = intent.getStringExtra(GeofenceTriggerManager.EXTRA_TASK_ID) ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)

        // 投递要查库并催醒 Worker，不能占着主线程；goAsync 让系统等我们处理完再回收。
        val pending = goAsync()
        val bus = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutomationEventEntryPoint::class.java,
        ).eventTriggerBus()
        bus.postLocation(taskId, entering).invokeOnCompletion { pending.finish() }
    }
}
