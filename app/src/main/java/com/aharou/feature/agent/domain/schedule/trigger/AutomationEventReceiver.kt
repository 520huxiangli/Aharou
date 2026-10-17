package com.aharou.feature.agent.domain.schedule.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors

/**
 * 开机与应用被更新：触发 boot 类自动化任务，并恢复周期检查。
 *
 * 只处理这两个系统广播；别的 action 一律不认，避免被伪造的广播拉起（飞过来的 action 不校验就
 * 当成开机，等于给任何应用一个白送的触发入口）。接收器在清单里带 intent-filter，因而必须显式声明
 * `android:exported="false"`——系统广播不受此限制，非系统来源才拦。
 *
 * 重活一律丢给协程：onReceive 在主线程上跑，查库、排调度虽快也不该占着它。
 */
class AutomationEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit

            else -> return
        }
        // goAsync：告诉系统「这条广播还在处理」，别急着回收进程——冷启动时进程正是被这条广播拉起来的，
        // 处理完再 finish。
        val pending = goAsync()
        val bus = EntryPointAccessors.fromApplication(
            context.applicationContext, AutomationEventEntryPoint::class.java
        ).eventTriggerBus()
        bus.postBoot().invokeOnCompletion { pending.finish() }
    }
}