package com.aharou.feature.agent.domain.schedule.trigger

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 事件触发总线：把「通知到了 / 开机了 / 状态变了」翻译成「某个任务该跑了」。
 *
 * 只做一件事——把命中任务的 [ScheduledTaskEntity.nextRunAt] 拨到事件发生的当下，再催一次
 * [com.aharou.feature.agent.domain.schedule.ScheduledTaskWorker]。认领、迟到判定、条件求值、
 * 防抖、派发仍旧全走 Worker 那条老链路，这里不另造派发路径：事件类任务的 nextRunAt 平时停放在
 * Long.MAX_VALUE（见 Worker 的说明），只在被投递的那一刻短暂有效。
 *
 * 触发参数（只看某个应用的通知、只在插上充电时、只认某个 WiFi）在这一层过滤；
 * 事件现场（通知标题/正文、电量、WiFi 名）暂存在内存里，交给 Worker 渲染提示词占位符。
 */
@Singleton
class EventTriggerBus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScheduledTaskDao,
    private val scheduler: ScheduledTaskScheduler,
) {

    /** 事件现场暂存：taskId → 现场。内存态、取走即删；进程重启就没了——事件本来就是一次性的。 */
    private val payloads = ConcurrentHashMap<String, EventPayload>()

    /** 投递都丢到这个协程里跑，绝不在系统回调（主线程）上等。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 本次进程是否已投递过「应用启动」触发：进程重建才会重置，见 [postAppStart]。 */
    private val appStartDispatched = AtomicBoolean(false)

    /**
     * 通知事件入口。**立即返回**：本方法在系统通知回调的主线程上被调用，任何等待都会拖慢
     * 整机通知体验，所以只把现场丢进内部协程。
     */
    fun postNotification(packageName: String, title: String, text: String) {
        val payload = EventPayload(
            app = packageName,
            title = title,
            text = text,
            timeMillis = System.currentTimeMillis(),
        )
        scope.launch {
            dispatch(ScheduledTaskEntity.TYPE_NOTIFICATION, payload) {
                it.triggerPackage.isEmpty() || it.triggerPackage.equals(packageName, ignoreCase = true)
            }
        }
    }

    /**
     * 开机 / 应用被更新入口：投递 boot 类任务，并恢复调度（开机后周期检查得重新排上）。
     * 不挂起，返回的 Job 供静态接收器收尾——接收器用了 `goAsync()`，要等投递结束再 finish。
     */
    fun postBoot(): Job = scope.launch {
        dispatch(ScheduledTaskEntity.TYPE_BOOT, EventPayload(timeMillis = System.currentTimeMillis())) { true }
        runCatching { scheduler.ensureScheduled(context) }
            .onFailure { FileLogger.e(TAG, "开机恢复自动化任务调度失败", it) }
    }

    /**
     * App 启动入口：本进程启动后投递一次 app_start 类任务。
     *
     * 与 [postBoot] 同一套语义——只认启用中的任务，把 nextRunAt 拨到当下再交给 Worker 认领；
     * 开机时工作区与容器同样未必就绪，这里不对就绪状态另作等待，拉起容器交给会话层按普通对话去做。
     *
     * `onCreate` 每个进程本就只跑一次，[appStartDispatched] 再兜一道：将来若从别的启动点
     * （Activity 重建、配置变更）再调到，也只认第一次。
     */
    fun postAppStart() {
        if (!appStartDispatched.compareAndSet(false, true)) return
        scope.launch {
            // 稍等片刻再投递：会话层（AIAgentViewModel）在 Activity 起来后才订阅 ScheduledRunBus，
            // 订阅前投递的请求会被无 replay 的 SharedFlow 直接丢弃——任务记成「已派发」却没人跑。
            delay(APP_START_SETTLE_MS)
            dispatch(ScheduledTaskEntity.TYPE_APP_START, EventPayload(timeMillis = System.currentTimeMillis())) { true }
        }
    }

    /** 周期检查发现状态变化时调用：电量/充电变化看 battery 类任务，WiFi 变化看 network 类任务。 */
    suspend fun dispatchDeviceStateChange(check: DeviceStateCheck) {
        val payload = EventPayload(
            batteryPercent = check.current.batteryPercent,
            ssid = check.current.ssid,
            timeMillis = System.currentTimeMillis(),
        )
        if (check.batteryChanged || check.chargingChanged) {
            dispatch(ScheduledTaskEntity.TYPE_BATTERY, payload) { matchesBattery(it, check) }
        }
        if (check.ssidChanged) {
            dispatch(ScheduledTaskEntity.TYPE_NETWORK, payload) { matchesNetwork(it, check) }
        }
    }

    /** Worker 取走该任务的事件现场；取到即删。被跳过、没走到派发的会留下残留，下一次事件会覆盖掉它。 */
    fun takePayload(taskId: String): EventPayload? = payloads.remove(taskId)

    /**
     * 通用投递：查出该类触发下启用、未超运行上限、且本次事件命中的任务，逐个过防抖 → 存现场 →
     * 拨 nextRunAt，最后催一次检查。
     */
    private suspend fun dispatch(
        type: String,
        payload: EventPayload,
        matches: (ScheduledTaskEntity) -> Boolean,
    ) {
        val now = System.currentTimeMillis()
        val candidates = runCatching { dao.enabledByTriggerType(type) }.getOrElse { e ->
            FileLogger.e(TAG, "读取事件触发任务失败: $type", e)
            return
        }.filter { (it.maxRuns == 0 || it.runCount < it.maxRuns) && matches(it) }
        if (candidates.isEmpty()) return

        var armed = 0
        candidates.forEach { task ->
            if (withinDebounce(task, now)) {
                FileLogger.i(TAG, "「${task.name}」在防抖窗口内，本次事件忽略")
                return@forEach
            }
            // 现场先存后拨：拨完 Worker 可能立刻被唤醒，晚一步就取不到标题/正文。
            payloads[task.id] = payload
            // 单列 UPDATE 而不是整行回写：本轮读到的快照之后，设置页可能已经改过同一行。
            val ok = runCatching { dao.armEvent(task.id, now, now) > 0 }
                .onFailure { FileLogger.e(TAG, "投递事件给「${task.name}」失败", it) }
                .getOrDefault(false)
            if (ok) armed++ else payloads.remove(task.id)
        }

        if (armed > 0) {
            // 与精确闹钟到点排的是同一个 OneTimeWork，只是抢在 15 分钟周期之前把刚落地的 nextRunAt 兑现掉。
            runCatching { ScheduledTaskScheduler.scheduleNow(context) }
                .onFailure { FileLogger.e(TAG, "催醒自动化任务检查失败", it) }
            FileLogger.i(TAG, "事件触发（$type）已投递 $armed 个任务")
        }
    }

    private fun matchesBattery(task: ScheduledTaskEntity, check: DeviceStateCheck): Boolean =
        (task.triggerOnCharging && check.chargingStarted) ||
            (task.triggerBatteryBelow >= 0 && check.droppedBelow(task.triggerBatteryBelow))

    /** 只在 ssid 真的变了时才会被问到，所以这里不必再判变化，只看名字对不对得上。 */
    private fun matchesNetwork(task: ScheduledTaskEntity, check: DeviceStateCheck): Boolean =
        task.triggerSsid.isEmpty() || task.triggerSsid.equals(check.current.ssid, ignoreCase = true)

    /**
     * 防抖：同一任务在一个窗口内被事件重复命中也只算一次。
     *
     * 基准是 [ScheduledTaskEntity.lastRunAt]——Worker 的防抖判定用的是同一个字段，两处口径不一致
     * 就会互相打架（这边放行、那边拦掉，用户看到的就是「有时触发有时不触发」）。
     *
     * 另加一条「已经拨过还等着认领」的判定：[ScheduledTaskEntity.lastRunAt] 只在 Worker 认领时才
     * 刷新，从投递到认领之间若又来了事件，光看 lastRunAt 会重复投递。
     */
    private fun withinDebounce(task: ScheduledTaskEntity, now: Long): Boolean {
        val window = task.debounceMinutes * 60_000L
        if (window <= 0L) return false
        if (task.nextRunAt in (now - window)..now) return true
        val last = task.lastRunAt ?: return false
        return now - last < window
    }

    private companion object {
        const val TAG = "EventTriggerBus"

        /** App 启动投递前的静置时长，等会话层先订阅上总线，见 [postAppStart]。 */
        const val APP_START_SETTLE_MS = 5_000L
    }
}

/**
 * 通知监听服务与静态接收器都由系统构造、不在 Hilt 的注入图里，依赖得经 EntryPoint 取
 * （写法与 [com.aharou.feature.agent.domain.schedule.ScheduledTaskEntryPoint] 一致）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationEventEntryPoint {
    fun eventTriggerBus(): EventTriggerBus
}