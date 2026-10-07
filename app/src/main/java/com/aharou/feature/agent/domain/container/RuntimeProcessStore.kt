package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.ProcessIdentity
import com.aharou.core.util.writeTextSafely
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条「本 App 启动过的容器进程」记录，用于重启后辨认残留。 */
@Serializable
data class RuntimeProcessRecord(
    val pid: Int,
    val startTimeTicks: Long,
    val bootId: String,
    val startedAt: Long,
    val command: String,
)

@Serializable
private data class RuntimeProcessData(val records: List<RuntimeProcessRecord> = emptyList())

/**
 * 容器进程账本：`filesDir/aharou/runtime-processes.json`。
 *
 * App 被系统杀掉时，它起过的 proot 会变成孤儿继续跑；重启后光看内存是不知道的，用户也无从察觉。
 * 这里只存「宿主 pid + 启动时钟 + 本次开机标识」这一组强身份，核验与终止都交给 [ProcessIdentity]——
 * 记录本身不具备任何杀伤力，是核验结果才决定要不要动手。
 */
@Singleton
class RuntimeProcessStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 本次进程存活期内启动过的 pid：它们不算「上次遗留」。 */
    private val thisSession = Collections.synchronizedSet(mutableSetOf<Int>())

    private fun file(): File = File(context.filesDir, "aharou/runtime-processes.json")

    /** 记下一条刚启动的容器进程。拿不到启动时钟的不记——之后没有可核验的身份。 */
    @Synchronized
    fun record(pid: Int, startTimeTicks: Long?, command: String) {
        val ticks = startTimeTicks ?: return
        val bootId = ProcessIdentity.readBootId() ?: return
        thisSession.add(pid)
        val records = read().filterNot { it.pid == pid }
        write(
            records + RuntimeProcessRecord(
                pid = pid,
                startTimeTicks = ticks,
                bootId = bootId,
                startedAt = System.currentTimeMillis(),
                command = command.take(MAX_COMMAND_CHARS),
            )
        )
    }

    /** 进程已确认终止时清掉它的记录。 */
    @Synchronized
    fun clear(pid: Int) {
        thisSession.remove(pid)
        val records = read()
        val kept = records.filterNot { it.pid == pid }
        if (kept.size != records.size) write(kept)
    }

    /**
     * 上次运行遗留、现在仍活着的容器进程；顺带把已消失/已被复用的记录清掉。
     *
     * 只有「同一次开机 + 启动时钟对得上 + 状态是活跃」才算遗留——跨设备重启后同一个 pid 与时钟
     * 都不再指向同一个进程。
     */
    @Synchronized
    fun stale(): List<RuntimeProcessRecord> {
        val bootId = ProcessIdentity.readBootId()
        val records = read()
        val stale = mutableListOf<RuntimeProcessRecord>()
        val kept = mutableListOf<RuntimeProcessRecord>()
        records.forEach { record ->
            val alive = record.bootId == bootId &&
                ProcessIdentity.state(record.pid, record.startTimeTicks) == ProcessIdentity.State.MATCHED_ACTIVE
            when {
                !alive -> Unit
                record.pid in thisSession -> kept.add(record)
                else -> {
                    kept.add(record)
                    stale.add(record)
                }
            }
        }
        if (kept.size != records.size) write(kept)
        return stale
    }

    /** 终止一条遗留进程；成功则连记录一起清掉。 */
    fun terminate(record: RuntimeProcessRecord): Boolean {
        val done = ProcessIdentity.terminateTree(ProcessIdentity.Handle(record.pid, record.startTimeTicks))
        if (done) clear(record.pid) else FileLogger.w(TAG, "遗留进程 ${record.pid} 未确认终止，保留记录")
        return done
    }

    private fun read(): List<RuntimeProcessRecord> = runCatching {
        val f = file()
        if (!f.exists()) emptyList() else json.decodeFromString<RuntimeProcessData>(f.readText()).records
    }.getOrElse {
        FileLogger.w(TAG, "读进程账本失败: ${it.message}")
        emptyList()
    }

    private fun write(records: List<RuntimeProcessRecord>) {
        file().writeTextSafely(json.encodeToString(RuntimeProcessData(records)), TAG)
    }

    private companion object {
        const val TAG = "RuntimeProcessStore"
        const val MAX_COMMAND_CHARS = 200
    }
}
