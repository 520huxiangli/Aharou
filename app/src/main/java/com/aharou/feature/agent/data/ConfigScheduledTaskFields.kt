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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * 定时任务 → 配置通道（集合式）。
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
        task(id) ?: throw ConfigError.InvalidValue("定时任务不存在：$id")

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
        override val displayName = "定时任务"
        override val description =
            "按周期自动在指定会话里跑一条指令。集合 id 即任务 id；add 建任务、remove 删任务。" +
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
                    "低于 ${ScheduledTaskScheduler.MIN_INTERVAL_MINUTES} 会被收敛；改周期会按新周期重排下次运行。",
                    schema = ConfigSchema.Int(min = 1),
                ) { ConfigValue.Int(t.intervalMinutes) } write { v ->
                    val minutes = ScheduledTaskScheduler.normalizeInterval(intOf(v))
                    mutate(forId) {
                        it.copy(
                            intervalMinutes = minutes,
                            nextRunAt = ScheduledTaskScheduler.nextRunAfter(now(), minutes),
                            updatedAt = now(),
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

                ReadOnlyField("$basePath.$forId.run_count", "已运行次数（$forId）", "累计触发次数。", ConfigSchema.Int(min = 0)) {
                    ConfigValue.Int(t.runCount)
                },

                ReadOnlyField("$basePath.$forId.next_run_at", "下次运行（$forId）", "epoch 毫秒；由调度层维护。", ConfigSchema.Str()) {
                    ConfigValue.Str(t.nextRunAt.toString())
                },

                ReadOnlyField("$basePath.$forId.last_run_at", "上次运行（$forId）", "epoch 毫秒；从未运行为空串。", ConfigSchema.Str()) {
                    ConfigValue.Str(t.lastRunAt?.toString().orEmpty())
                },

                ReadOnlyField("$basePath.$forId.last_outcome", "上次结果（$forId）", "dispatched（已派发）/ skipped（错过未补跑）；从未运行为空串。", ConfigSchema.Str()) {
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

            val saved = runBlocking {
                tasks.save(
                    existing = null,
                    name = name,
                    prompt = prompt,
                    targetSessionId = (obj["target_session_id"] as? ConfigValue.Str)?.value?.ifBlank { null },
                    workspacePath = (obj["workspace_path"] as? ConfigValue.Str)?.value?.ifBlank { null },
                    intervalMinutes = (obj["interval_minutes"] as? ConfigValue.Int)?.value
                        ?: ScheduledTaskScheduler.MIN_INTERVAL_MINUTES,
                    maxRuns = (obj["max_runs"] as? ConfigValue.Int)?.value ?: 0,
                    enabled = (obj["enabled"] as? ConfigValue.Bool)?.value ?: true,
                )
            }
            return saved.id
        }

        override fun remove(id: String) {
            require(id)
            runBlocking { tasks.delete(id) }
        }

        /** 一个读写标量字段：省掉 12 处重复的 ClosureField 样板。 */
        private fun scalar(
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
}
