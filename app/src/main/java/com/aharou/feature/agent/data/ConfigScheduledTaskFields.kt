package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.ScheduledTaskRepository
import com.aharou.feature.agent.domain.schedule.ScheduledTaskScheduler
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * 自动化任务 → 配置通道（集合式）。
 *
 * 集合 id 就是任务 id：`add` 建一个任务并返回新 id，`remove` 删掉整个任务。调度状态
 * （下次运行、已运行次数、上次结果）做成**只读**——它们是 Worker 认领时写的，
 * 外部改写会破坏「认领即推进」的语义，让同一个到点时刻跑两遍。
 *
 * 仓库接口是 suspend、集合 API 是同步的，这里用 `runBlocking` 桥接，与
 * [ConfigMcpFields] / [ConfigEnvFields] 同一套既有写法（调用方在后台线程）。
 */
@Singleton
class ConfigScheduledTaskFields @Inject constructor(
    private val tasks: ScheduledTaskRepository
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(TaskCollection())
    }

    private fun all(): List<ScheduledTaskEntity> = runBlocking { tasks.all() }

    private fun task(id: String): ScheduledTaskEntity? = all().firstOrNull { it.id == id }

    private fun require(id: String): ScheduledTaskEntity =
        task(id) ?: throw ConfigError.InvalidValue("自动化任务不存在：$id")

    private fun mutate(id: String, transform: (ScheduledTaskEntity) -> ScheduledTaskEntity) {
        runBlocking { tasks.upsert(transform(require(id))) }
    }

    private fun now() = System.currentTimeMillis()

    private fun strOf(v: ConfigValue): String =
        (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")

    private fun intOf(v: ConfigValue): Int =
        (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")

    private fun boolOf(v: ConfigValue): Boolean =
        (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")

    private inner class TaskCollection : ConfigCollection {
        override val basePath = "scheduledTasks"
        override val displayName = "自动化任务"
        override val description =
            "按时间或事件（收到通知 / 电量变化 / 网络变化 / 开机）自动在指定会话里跑一条指令。" +
                "集合 id 即任务 id；add 建任务、remove 删任务。" +
                "周期下限 ${ScheduledTaskScheduler.MIN_INTERVAL_MINUTES} 分钟，低于会被收敛。"

        override fun childIds(): List<String> = all().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            val t = task(forId) ?: return emptyList()
            return listOf(
                scalar("$basePath.$forId.name", "名称（$forId）", "任务名，出现在设置页、会话标题与通知里。") {
                    ConfigValue.Str(t.name)
                } write { v -> mutate(forId) { it.copy(name = strOf(v), updatedAt = now()) } },

                scalar(
                    "$basePath.$forId.prompt",
                    "指令（$forId）",
                    "每次运行下发给 AI 的内容。",
                    schema = ConfigSchema.Str(maxLength = 20_000),
                ) { ConfigValue.Str(t.prompt) } write { v ->
                    mutate(forId) { it.copy(prompt = strOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.interval_minutes",
                    "周期分钟（$forId）",
                    "低于 ${ScheduledTaskScheduler.MIN_INTERVAL_MINUTES} 会被收敛；区间任务改周期会按新周期重排下次运行（daily / once 不看这个值）。",
                    schema = ConfigSchema.Int(min = 1),
                ) { ConfigValue.Int(t.intervalMinutes) } write { v ->
                    val minutes = ScheduledTaskScheduler.normalizeInterval(intOf(v))
                    val at = now()
                    mutate(forId) { task ->
                        task.copy(
                            intervalMinutes = minutes,
                            nextRunAt = if (TriggerType.isTimeTrigger(task) &&
                                task.scheduleType == ScheduledTaskEntity.TYPE_INTERVAL
                            ) {
                                ScheduledTaskScheduler.nextRunAfter(at, minutes)
                            } else {
                                task.nextRunAt
                            },
                            updatedAt = at,
                        )
                    }
                },

                scalar(
                    "$basePath.$forId.schedule_type",
                    "运行方式（$forId）",
                    "trigger_type 为 time 时有效：interval 按周期重复 / daily 每天固定时刻 / once 只跑一次。" +
                        "改动后按新运行方式重排下次运行；事件类触发不看这个字段。",
                    schema = ConfigSchema.StrEnum(SCHEDULE_TYPES),
                ) { ConfigValue.Str(t.scheduleType) } write { v ->
                    val type = strOf(v)
                    val at = now()
                    mutate(forId) { task ->
                        val updated = task.copy(scheduleType = type, updatedAt = at)
                        updated.copy(
                            nextRunAt = if (TriggerType.isTimeTrigger(task)) {
                                ScheduledTaskScheduler.nextRunFor(updated, at)
                            } else {
                                task.nextRunAt
                            }
                        )
                    }
                },

                scalar(
                    "$basePath.$forId.daily_minute_of_day",
                    "每天时刻（$forId）",
                    "schedule_type 为 daily 时有效：当天第几分钟（0=00:00、510=08:30）；改动后重排下次运行。",
                    schema = ConfigSchema.Int(min = 0, max = 24 * 60 - 1),
                ) { ConfigValue.Int(t.dailyMinuteOfDay) } write { v ->
                    val minute = intOf(v).coerceIn(0, 24 * 60 - 1)
                    val at = now()
                    mutate(forId) { task ->
                        val updated = task.copy(dailyMinuteOfDay = minute, updatedAt = at)
                        updated.copy(
                            nextRunAt = if (TriggerType.isTimeTrigger(task) &&
                                task.scheduleType == ScheduledTaskEntity.TYPE_DAILY
                            ) {
                                ScheduledTaskScheduler.nextDailyOccurrence(minute, at)
                            } else {
                                task.nextRunAt
                            }
                        )
                    }
                },

                scalar(
                    "$basePath.$forId.once_at_millis",
                    "单次时刻（$forId）",
                    "schedule_type 为 once 时有效：epoch 毫秒字符串（与 next_run_at 同一口径），0 表示未设置；" +
                        "改动后重排下次运行。",
                ) { ConfigValue.Str(t.onceAtMillis.toString()) } write { v ->
                    val millis = parseOnceAt(strOf(v))
                    val at = now()
                    mutate(forId) { task ->
                        val updated = task.copy(onceAtMillis = millis, updatedAt = at)
                        updated.copy(
                            nextRunAt = if (TriggerType.isTimeTrigger(task) &&
                                task.scheduleType == ScheduledTaskEntity.TYPE_ONCE
                            ) {
                                millis
                            } else {
                                task.nextRunAt
                            }
                        )
                    }
                },

                scalar("$basePath.$forId.enabled", "启用（$forId）", "关闭后不再触发。", schema = ConfigSchema.Bool) {
                    ConfigValue.Bool(t.enabled)
                } write { v -> runBlocking { tasks.setEnabled(forId, boolOf(v)) } },

                scalar("$basePath.$forId.max_runs", "运行上限（$forId）", "0 表示不限。", schema = ConfigSchema.Int(min = 0)) {
                    ConfigValue.Int(t.maxRuns)
                } write { v -> mutate(forId) { it.copy(maxRuns = intOf(v), updatedAt = now()) } },

                scalar(
                    "$basePath.$forId.target_session_id",
                    "目标会话（$forId）",
                    "在哪个会话里跑；留空表示每次新建会话（此时用 workspace_path）。",
                ) { ConfigValue.Str(t.targetSessionId.orEmpty()) } write { v ->
                    mutate(forId) { it.copy(targetSessionId = strOf(v).ifBlank { null }, updatedAt = now()) }
                },

                scalar("$basePath.$forId.workspace_path", "工作区（$forId）", "新建会话时使用的工程工作区；在既有会话里跑时以该会话自身为准。") {
                    ConfigValue.Str(t.workspacePath.orEmpty())
                } write { v -> mutate(forId) { it.copy(workspacePath = strOf(v).ifBlank { null }, updatedAt = now()) } },

                scalar(
                    "$basePath.$forId.trigger_type",
                    "触发方式（$forId）",
                    "time 到点（按任务的运行方式：周期 / 每天定时 / 只跑一次）｜notification 收到通知｜" +
                        "battery 电量变化｜network WiFi 连上或断开｜boot 开机｜app_start 应用启动。" +
                        "改成事件类后不再按时间自动跑，要等事件发生才跑；改回 time 会按运行方式重排下次运行。",
                    schema = ConfigSchema.StrEnum(TriggerType.ALL),
                ) { ConfigValue.Str(t.triggerType) } write { v ->
                    val type = strOf(v)
                    mutate(forId) {
                        it.copy(
                            triggerType = type,
                            // 只有换触发方式才动到期时刻：事件类不能留着按时间算出来的值（没等到事件就先跑了），
                            // 换回 time 则按运行方式重排；写回原值不动计划，免得顺手把快到的任务推迟一轮。
                            nextRunAt = when {
                                TriggerType.isTimeTrigger(type) == TriggerType.isTimeTrigger(it.triggerType) -> it.nextRunAt
                                TriggerType.isTimeTrigger(type) -> ScheduledTaskScheduler.nextRunFor(it, now())
                                else -> Long.MAX_VALUE
                            },
                            updatedAt = now(),
                        )
                    }
                },

                scalar(
                    "$basePath.$forId.trigger_package",
                    "通知来源应用（$forId）",
                    "触发方式为 notification 时有效：只匹配这个包名的通知（如 com.tencent.mm 只认微信）；" +
                        "留空表示任意应用的通知都触发。",
                ) { ConfigValue.Str(t.triggerPackage) } write { v ->
                    mutate(forId) { it.copy(triggerPackage = strOf(v).trim(), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.trigger_battery_below",
                    "电量低于（$forId）",
                    "触发方式为 battery 时有效：电量降到该百分比（0-100）时触发；-1 表示不用这条。",
                    schema = ConfigSchema.Int(min = -1, max = 100),
                ) { ConfigValue.Int(t.triggerBatteryBelow) } write { v ->
                    mutate(forId) { it.copy(triggerBatteryBelow = intOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.trigger_on_charging",
                    "插电触发（$forId）",
                    "触发方式为 battery 时有效：true 表示插上充电时触发。",
                    schema = ConfigSchema.Bool,
                ) { ConfigValue.Bool(t.triggerOnCharging) } write { v ->
                    mutate(forId) { it.copy(triggerOnCharging = boolOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.trigger_ssid",
                    "WiFi 名（$forId）",
                    "触发方式为 network 时有效：连上或离开这个 WiFi 时触发；留空表示任意 WiFi 变化都触发。",
                ) { ConfigValue.Str(t.triggerSsid) } write { v ->
                    mutate(forId) { it.copy(triggerSsid = strOf(v).trim(), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.condition_window_from",
                    "生效时段起（$forId）",
                    "条件：时段起点，用当天第几分钟表示（0=00:00、510=08:30）；-1 表示该端不限。" +
                        "起点大于终点表示跨午夜（1380 到 360 即 23:00~06:00）。",
                    schema = ConfigSchema.Int(min = -1, max = 24 * 60 - 1),
                ) { ConfigValue.Int(t.conditionWindowFrom) } write { v ->
                    mutate(forId) { it.copy(conditionWindowFrom = intOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.condition_window_to",
                    "生效时段止（$forId）",
                    "条件：时段终点，口径同「生效时段起」；两端都是 -1 表示全天都可以跑。",
                    schema = ConfigSchema.Int(min = -1, max = 24 * 60 - 1),
                ) { ConfigValue.Int(t.conditionWindowTo) } write { v ->
                    mutate(forId) { it.copy(conditionWindowTo = intOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.cond_require_charging",
                    "仅充电时（$forId）",
                    "条件：true 表示必须在充电中才跑；读不到充电状态时这一次记为条件不满足。",
                    schema = ConfigSchema.Bool,
                ) { ConfigValue.Bool(t.condRequireCharging) } write { v ->
                    mutate(forId) { it.copy(condRequireCharging = boolOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.cond_min_battery",
                    "最低电量（$forId）",
                    "条件：电量不低于该百分比（0-100）才跑；-1 表示不限。读不到电量时这一次记为条件不满足。",
                    schema = ConfigSchema.Int(min = -1, max = 100),
                ) { ConfigValue.Int(t.condMinBattery) } write { v ->
                    mutate(forId) { it.copy(condMinBattery = intOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.debounce_minutes",
                    "防抖分钟（$forId）",
                    "事件触发专用：窗口内同样的触发重复到来只跑一次（默认 10）；0 表示不防抖。" +
                        "时间触发的节奏由运行方式决定，不看这个值。",
                    schema = ConfigSchema.Int(min = 0),
                ) { ConfigValue.Int(t.debounceMinutes) } write { v ->
                    mutate(forId) { it.copy(debounceMinutes = intOf(v), updatedAt = now()) }
                },

                scalar(
                    "$basePath.$forId.run_mode",
                    "运行模式（$forId）",
                    "single：上一次派发后 5 分钟内又来触发就跳过本次。目前只有这一个取值。",
                    schema = ConfigSchema.StrEnum(listOf(ScheduledTaskEntity.RUN_SINGLE)),
                ) { ConfigValue.Str(t.runMode) } write { v ->
                    mutate(forId) { it.copy(runMode = strOf(v), updatedAt = now()) }
                },

                ReadOnlyField("$basePath.$forId.run_count", "已运行次数（$forId）", "累计触发次数。", ConfigSchema.Int(min = 0)) {
                    ConfigValue.Int(t.runCount)
                },

                ReadOnlyField("$basePath.$forId.next_run_at", "下次运行（$forId）", "epoch 毫秒；时间触发由调度层维护，事件触发在事件发生那一刻写入、平时是一个远期值（表示在等事件）。", ConfigSchema.Str()) {
                    ConfigValue.Str(t.nextRunAt.toString())
                },

                ReadOnlyField("$basePath.$forId.last_run_at", "上次运行（$forId）", "epoch 毫秒；从未运行为空串。", ConfigSchema.Str()) {
                    ConfigValue.Str(t.lastRunAt?.toString().orEmpty())
                },

                ReadOnlyField("$basePath.$forId.last_outcome", "上次结果（$forId）", "dispatched（已派发）/ skipped_missed（错过太久未补跑）/ skipped_condition:原因（条件不满足，原因取 out_of_window / not_charging / battery_too_low / state_unknown，旧值只有 skipped_condition）/ skipped_debounce（在防抖窗口内）/ skipped_running（上一次还在跑）；从未运行为空串。", ConfigSchema.Str()) {
                    ConfigValue.Str(t.lastOutcome.orEmpty())
                },
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("scheduledTasks 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            if (name.isBlank()) throw ConfigError.InvalidValue("缺少 name")
            val prompt = (obj["prompt"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            if (prompt.isBlank()) throw ConfigError.InvalidValue("缺少 prompt")

            val scheduleType = (obj["schedule_type"] as? ConfigValue.Str)?.value?.trim()
                ?.ifEmpty { ScheduledTaskEntity.TYPE_INTERVAL }
                ?: ScheduledTaskEntity.TYPE_INTERVAL
            if (scheduleType !in SCHEDULE_TYPES) {
                throw ConfigError.InvalidValue(
                    "schedule_type 取值非法：$scheduleType；可用 ${SCHEDULE_TYPES.joinToString("/")}"
                )
            }
            val dailyMinuteOfDay = (obj["daily_minute_of_day"] as? ConfigValue.Int)?.value
                ?: (9 * 60)
            val onceAtMillis = parseOnceAt((obj["once_at_millis"] as? ConfigValue.Str)?.value.orEmpty())
            // once 没给时刻就永远跑不了：建的时候直接拦下，别建出一个永不触发的任务。
            if (scheduleType == ScheduledTaskEntity.TYPE_ONCE && onceAtMillis <= 0L) {
                throw ConfigError.InvalidValue("schedule_type 为 once 时需要 once_at_millis（epoch 毫秒）")
            }

            val saved = runBlocking {
                val base = tasks.save(
                    existing = null,
                    name = name,
                    prompt = prompt,
                    targetSessionId = (obj["target_session_id"] as? ConfigValue.Str)?.value?.ifBlank { null },
                    workspacePath = (obj["workspace_path"] as? ConfigValue.Str)?.value?.ifBlank { null },
                    intervalMinutes = (obj["interval_minutes"] as? ConfigValue.Int)?.value
                        ?: ScheduledTaskScheduler.MIN_INTERVAL_MINUTES,
                    scheduleType = scheduleType,
                    dailyMinuteOfDay = dailyMinuteOfDay,
                    onceAtMillis = onceAtMillis,
                    maxRuns = (obj["max_runs"] as? ConfigValue.Int)?.value ?: 0,
                    enabled = (obj["enabled"] as? ConfigValue.Bool)?.value ?: true,
                )
                // 触发类字段走「先 save 后补写」两步（save 现已接收这些字段，可以合并成一次调用）；
                // 没给的字段一律沿用实体默认值，不在 add 里另养一套默认数。
                val type = (obj["trigger_type"] as? ConfigValue.Str)?.value?.trim().orEmpty()
                    .ifEmpty { base.triggerType }
                if (!TriggerType.isKnown(type)) {
                    throw ConfigError.InvalidValue(
                        "trigger_type 取值非法：$type；可用 ${TriggerType.ALL.joinToString("/")}"
                    )
                }
                val task = base.copy(
                    triggerType = type,
                    triggerPackage = (obj["trigger_package"] as? ConfigValue.Str)?.value?.trim()
                        ?: base.triggerPackage,
                    triggerBatteryBelow = (obj["trigger_battery_below"] as? ConfigValue.Int)?.value
                        ?: base.triggerBatteryBelow,
                    triggerOnCharging = (obj["trigger_on_charging"] as? ConfigValue.Bool)?.value
                        ?: base.triggerOnCharging,
                    triggerSsid = (obj["trigger_ssid"] as? ConfigValue.Str)?.value?.trim()
                        ?: base.triggerSsid,
                    conditionWindowFrom = (obj["condition_window_from"] as? ConfigValue.Int)?.value
                        ?: base.conditionWindowFrom,
                    conditionWindowTo = (obj["condition_window_to"] as? ConfigValue.Int)?.value
                        ?: base.conditionWindowTo,
                    condRequireCharging = (obj["cond_require_charging"] as? ConfigValue.Bool)?.value
                        ?: base.condRequireCharging,
                    condMinBattery = (obj["cond_min_battery"] as? ConfigValue.Int)?.value
                        ?: base.condMinBattery,
                    debounceMinutes = (obj["debounce_minutes"] as? ConfigValue.Int)?.value
                        ?: base.debounceMinutes,
                    runMode = (obj["run_mode"] as? ConfigValue.Str)?.value?.trim()?.ifBlank { null }
                        ?: base.runMode,
                    // 事件类任务不能留着 save() 按周期算出来的到期时刻，否则没等到事件就先跑了。
                    nextRunAt = if (TriggerType.isTimeTrigger(type)) base.nextRunAt else Long.MAX_VALUE,
                )
                tasks.upsert(task)
                task
            }
            return saved.id
        }

        override fun remove(id: String) {
            require(id)
            runBlocking { tasks.delete(id) }
        }

        /** 一个读写标量字段：省掉逐个重复的 ClosureField 样板。 */        private fun scalar(
            path: String,
            displayName: String,
            description: String,
            schema: ConfigSchema = ConfigSchema.Str(),
            read: () -> ConfigValue,
        ): ScalarDraft = ScalarDraft(path, displayName, description, schema, read)

        private inner class ScalarDraft(
            private val path: String,
            private val displayName: String,
            private val description: String,
            private val schema: ConfigSchema,
            private val read: () -> ConfigValue,
        ) {
            infix fun write(writer: (ConfigValue) -> Unit): ConfigField = ClosureField(
                path = path,
                displayName = displayName,
                description = description,
                valueSchema = schema,
                reader = read,
                writer = writer,
            )
        }
    }

    /** once_at_millis 收 epoch 毫秒字符串；空串表示未设置。 */
    private fun parseOnceAt(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        val millis = text.toLongOrNull()
            ?: throw ConfigError.InvalidValue("once_at_millis 需要 epoch 毫秒数字（如 1792838400000）：$text")
        if (millis < 0) throw ConfigError.InvalidValue("once_at_millis 不能为负：$text")
        return millis
    }

    private companion object {
        /** schedule_type 的合法取值，与设置页的「运行方式」一致。 */
        val SCHEDULE_TYPES = listOf(
            ScheduledTaskEntity.TYPE_INTERVAL,
            ScheduledTaskEntity.TYPE_DAILY,
            ScheduledTaskEntity.TYPE_ONCE,
        )
    }
}
