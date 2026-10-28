package com.aharou.core.memory

import com.aharou.core.util.FileLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 记忆使用统计：哪条被真正读过、读过几次、最近一次什么时候。
 *
 * 单独存一个 JSON，不写回记忆文件的 frontmatter——记忆文件的 mtime 既参与注入排序，
 * 又是「长期未改动即遗忘」的判据；读一次就刷一次 mtime 会让陈旧条目永远显得新鲜，
 * 白抖 KV Cache 不说，还会让遗忘机制失效。
 */
@Singleton
class MemoryUsageStore @Inject constructor(
    private val memoryStore: AharouMemoryStore
) {
    private class Entry(var count: Int = 0, var lastUsedAt: Long = 0L)

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var loaded = false

    private val file: File get() = File(memoryStore.dir, FILE_NAME)

    /** 记一次「取用」。读正文成功时调用。 */
    fun record(scope: String, name: String) {
        synchronized(lock) {
            ensureLoaded()
            val entry = entries.getOrPut(key(scope, name)) { Entry() }
            entry.count += 1
            entry.lastUsedAt = System.currentTimeMillis()
            persist()
        }
    }

    /** 最近一次取用时刻（epoch millis）；从未取用过返回 0。 */
    fun lastUsedAt(scope: String, name: String): Long = synchronized(lock) {
        ensureLoaded()
        entries[key(scope, name)]?.lastUsedAt ?: 0L
    }

    /** 累计取用次数。 */
    fun useCount(scope: String, name: String): Int = synchronized(lock) {
        ensureLoaded()
        entries[key(scope, name)]?.count ?: 0
    }

    /** 删除某条记录（自动降级标记在回升后要作废，否则会被重复当成待恢复）。 */
    fun clear(scope: String, name: String) = synchronized(lock) {
        ensureLoaded()
        if (entries.remove(key(scope, name)) != null) persist()
    }

    private fun key(scope: String, name: String) = "${scope.lowercase()}:${name.lowercase()}"

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val target = file
        if (!target.isFile) return
        runCatching {
            Json.parseToJsonElement(target.readText()).jsonObject.forEach { (k, v) ->
                val item = v.jsonObject
                entries[k] = Entry(
                    count = item["count"]?.jsonPrimitive?.intOrNull ?: 0,
                    lastUsedAt = item["lastUsedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            }
        }.onFailure { FileLogger.w(TAG, "读取记忆使用统计失败", it) }
    }

    private fun persist() {
        runCatching {
            val payload = buildJsonObject {
                entries.forEach { (k, entry) ->
                    put(k, buildJsonObject {
                        put("count", JsonPrimitive(entry.count))
                        put("lastUsedAt", JsonPrimitive(entry.lastUsedAt))
                    })
                }
            }
            memoryStore.dir.mkdirs()
            target().writeText(payload.toString())
        }.onFailure { FileLogger.w(TAG, "写入记忆使用统计失败", it) }
    }

    private fun target(): File = file

    private companion object {
        const val TAG = "MemoryUsageStore"
        const val FILE_NAME = ".memory-usage.json"
    }
}
