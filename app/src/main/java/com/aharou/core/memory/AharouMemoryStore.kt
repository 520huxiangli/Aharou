package com.aharou.core.memory

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aharou 记忆框架（只带机制、零隐私——空模板 + 运行时自建）。
 *
 * 文件位于 `<filesDir>/aharou-global/memory/`，与 SOUL.md 同目录；
 * 该目录已绑定进容器 `/root/.aharou/memory`，Agent 用文件/shell 也能读写。
 *
 * 结构：
 *  - `CORE.md`         核心档案（长期记住的关键信息）
 *  - `GLOBAL.md`       全局记忆（可选，用户/AI 维护）
 *  - `LOG-YYYY-MM-DD.md` 每日日志（append）
 *  - `facts.json`      事实库（append：{t, src, text}）
 *  - `mindstream.jsonl` 心流（append）
 */
@Singleton
class AharouMemoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "AharouMemory"

        /** 注入提示词时日志尾巴上限（字符）。 */
        private const val MAX_LOG_TAIL_CHARS = 1600

        private val DEFAULT_CORE = """
            # 核心档案

            （在这里记录需要长期记住的核心信息：用户偏好、重要约定、关键事实。可被 AI 通过 memory 工具更新。）
        """.trimIndent() + "\n"
    }

    /** 记忆根目录（宿主侧；容器内 = /root/.aharou/memory）。 */
    val dir: File = File(File(context.filesDir, "aharou-global"), "memory")

    init {
        // 旧版 memory 工具的全局记忆曾落在 filesDir/aharou/memory：那个路径既被容器里
        // /root/.aharou/memory 的子挂载挡住，也不在设置 → 记忆页的读取范围内。
        // 一次性并入 canonical 目录，只搬不删。
        runCatching {
            val legacy = File(File(context.filesDir, "aharou"), "memory")
            if (legacy.isDirectory) {
                dir.mkdirs()
                legacy.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { src ->
                    val dst = File(dir, src.name)
                    if (!dst.exists()) src.copyTo(dst, overwrite = false)
                }
            }
        }.onFailure { FileLogger.w(TAG, "迁移旧全局记忆目录失败", it) }
    }

    // ── 记忆管理（设置页用） ──
    private val prefs = context.getSharedPreferences("aharou_memory_prefs", Context.MODE_PRIVATE)

    /** 默认启用记忆：关闭后提示词不再注入核心档案与每日日志。 */
    fun isMemoryEnabled(): Boolean = prefs.getBoolean("default_enabled", true)

    fun setMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("default_enabled", enabled).apply()
    }

    data class MemoryFileInfo(val name: String, val sizeBytes: Long, val modifiedAt: Long)

    /** 列出记忆目录下全部文件（按修改时间倒序）。 */
    fun listFiles(): List<MemoryFileInfo> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .map { MemoryFileInfo(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }

    /** 读取记忆文件（拒绝路径穿越）。 */
    fun readFile(name: String): String? {
        if (name.contains('/') || name.contains("..")) return null
        val target = File(dir, name)
        return target.takeIf { it.exists() && it.isFile }?.let {
            runCatching { it.readText() }.getOrNull()
        }
    }

    /** 删除记忆文件（拒绝路径穿越）。 */
    fun deleteFile(name: String): Boolean {
        if (name.contains('/') || name.contains("..")) return false
        return runCatching { File(dir, name).delete() }.getOrDefault(false)
    }

    fun ensureExists() {
        runCatching {
            dir.mkdirs()
            val core = File(dir, "CORE.md")
            if (!core.exists()) core.writeTextSafely(DEFAULT_CORE, TAG)
        }.onFailure { FileLogger.w(TAG, "ensureExists failed: ${it.message}") }
    }

    fun file(name: String): File = File(dir, name)

    /** 追加一条每日日志（按天归档；返回写入的文件）。 */
    fun appendDailyLog(entry: String): File {
        val now = Date()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(now)
        val target = File(dir, "LOG-$day.md")
        dir.mkdirs()
        if (!target.exists()) target.writeTextSafely("# $day 日志\n\n", TAG)
        target.appendText("- [$time] ${entry.trim()}\n")
        return target
    }

    /** 追加一条事实到事实库。 */
    fun appendFact(fact: String, source: String? = null) {
        runCatching {
            dir.mkdirs()
            val target = File(dir, "facts.json")
            val arr = runCatching { JSONArray(target.readText()) }.getOrDefault(JSONArray())
            arr.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("src", source ?: "")
                    .put("text", fact.trim())
            )
            target.writeTextSafely(arr.toString(), TAG)
        }.onFailure { FileLogger.w(TAG, "appendFact failed: ${it.message}") }
    }

    /** 追加一条心流（想法/心情的顺滑记录）。 */
    fun appendMindstream(text: String) {
        runCatching {
            dir.mkdirs()
            val line = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("text", text.trim())
            File(dir, "mindstream.jsonl").appendText(line.toString() + "\n")
        }.onFailure { FileLogger.w(TAG, "appendMindstream failed: ${it.message}") }
    }

    /** 读某天日志（默认今天）。 */
    fun dailyLog(day: String? = null): String? {
        val target = File(
            dir,
            "LOG-${day ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.md"
        )
        return target.takeIf { it.exists() }?.readText()
    }

    /** 读核心档案（CORE.md）。 */
    fun readCore(): String? = File(dir, "CORE.md").takeIf { it.exists() }?.readText()

    /** 覆盖写核心档案（CORE.md）。 */
    fun writeCore(text: String) {
        runCatching {
            dir.mkdirs()
            File(dir, "CORE.md").writeTextSafely(text, TAG)
        }.onFailure { FileLogger.w(TAG, "writeCore failed: ${it.message}") }
    }

    /**
     * 组装注入系统提示词的记忆段：核心档案 + 全局记忆 + 今天日志的尾巴。
     * 全部为空时返回 null（不注入任何东西）。
     */
    fun buildPromptSection(): String? {
        if (!isMemoryEnabled()) return null
        ensureExists()
        val sb = StringBuilder()
        val core = File(dir, "CORE.md").takeIf { it.exists() }?.readText()?.trim()
        if (!core.isNullOrEmpty()) sb.append("### 核心档案\n").append(core).append("\n")
        val global = File(dir, "GLOBAL.md").takeIf { it.exists() }?.readText()?.trim()
        if (!global.isNullOrEmpty()) sb.append("\n### 全局记忆\n").append(global).append("\n")
        val log = runCatching { dailyLog() }.getOrNull()?.trim()
        if (!log.isNullOrEmpty()) {
            val tail = if (log.length <= MAX_LOG_TAIL_CHARS) log else "…" + log.takeLast(MAX_LOG_TAIL_CHARS)
            sb.append("\n### 今天日志\n").append(tail).append("\n")
        }
        if (sb.isEmpty()) return null
        return "## 记忆（持久，跨会话；更新用 memory 工具或直接读写 ~/.aharou/memory/）\n" + sb
    }
}
