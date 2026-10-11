package com.aharou.feature.agent.domain.mobileagent

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 一次手机操作任务的 trace 摘要（列表用）。 */
data class TraceSummary(
    val id: String,
    val goal: String,
    val app: String,
    val status: String,
    val steps: Int,
    val started: Long,
)

/**
 * 手机操作 trace 的存取：每次循环落一份 JSONL（每步一行的「看到什么 / 想了什么 / 做了什么 /
 * 实际下发什么 / 耗时 / 错误」），外加每步的截图文件，供 [renderText] / [renderHtml] 回放。
 *
 * 存放位置为 App 私有目录 `filesDir/mobile-traces/`，不依赖容器是否就绪。保留最近
 * [KEEP_TRACES] 份（含各自的截图），更早的连同图片一起清掉。参考 nanoMuse 的 Trace。
 */
@Singleton
class MobileTraceStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dir: File get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun start(goal: String, app: String, sessionId: String?): MobileTrace {
        val id = "mt-" + System.currentTimeMillis() + "-" + Integer.toHexString((goal.hashCode() and 0xffff))
        prune()
        val file = File(dir, "$id.jsonl")
        val trace = MobileTrace(id, file, dir)
        trace.begin(goal = goal, app = app, sessionId = sessionId)
        return trace
    }

    fun list(): List<TraceSummary> {
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { summarize(it) }
            ?: emptyList()
    }

    fun renderText(id: String): String? {
        val records = readRecords(id) ?: return null
        if (records.isEmpty()) return "（trace $id 为空）"
        val task = records.firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "task" } ?: JsonObject(emptyMap())
        val end = records.lastOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "end" }
        val sb = StringBuilder()
        sb.append("# ").append(str(task, "goal").ifEmpty { "(no goal)" }).append('\n')
        sb.append("id=").append(id)
        str(task, "app").takeIf { it.isNotEmpty() }?.let { sb.append(" · app=").append(it) }
        end?.let { sb.append(" · ").append(str(it, "status")).append(" · ").append(str(it, "steps")).append(" steps") }
        sb.append('\n')
        records.filter { it["kind"]?.jsonPrimitive?.contentOrNull == "step" }.forEach { step ->
            sb.append("\nStep ").append(str(step, "step"))
            (step["screen"] as? JsonObject)?.let { scr -> str(scr, "caption").takeIf { it.isNotEmpty() }?.let { sb.append("  [").append(it).append(']') } }
            sb.append("  ").append(str(step, "latency_ms")).append("ms\n")
            str(step, "thought").takeIf { it.isNotEmpty() }?.let { sb.append("  想一想：").append(it).append('\n') }
            str(step, "action").takeIf { it.isNotEmpty() }?.let { sb.append("  → ").append(it).append('\n') }
            (step["params"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { sb.append("  下发：").append(it).append('\n') }
            str(step, "raw").takeIf { it.isNotEmpty() }?.let { sb.append("  未解析的回复：").append(it).append('\n') }
            str(step, "error").takeIf { it.isNotEmpty() }?.let { sb.append("  错误：").append(it).append('\n') }
        }
        end?.let { e -> str(e, "message").takeIf { it.isNotEmpty() }?.let { sb.append("\n结论：").append(str(e, "status")).append(" — ").append(it).append('\n') } }
        return sb.toString()
    }

    fun renderHtml(id: String): String? {
        val records = readRecords(id) ?: return null
        val task = records.firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "task" } ?: JsonObject(emptyMap())
        val end = records.lastOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "end" }
        val parts = ArrayList<String>()
        parts.add("<!doctype html><meta charset='utf-8'><title>mobile trace $id</title>")
        parts.add("<style>body{font:14px/1.45 system-ui,sans-serif;margin:24px;color:#1b1b1f;background:#f6f6f8}" +
            "h1{font-size:18px;margin:0 0 4px}.meta{color:#5c5c66;margin-bottom:20px}" +
            ".step{display:flex;gap:18px;align-items:flex-start;background:#fff;border-radius:12px;padding:14px;margin-bottom:14px;box-shadow:0 1px 2px rgba(0,0,0,.06)}" +
            ".shot{flex:none;width:270px}.shot img{width:100%;display:block;border-radius:8px}" +
            ".n{font-weight:600}.t{color:#5c5c66}.a{margin:6px 0}.err{color:#b3261e}" +
            "code{background:#eeeef2;padding:1px 5px;border-radius:4px;font-size:12px}</style>")
        parts.add("<h1>" + escape(str(task, "goal").ifEmpty { "mobile task" }) + "</h1>")
        val metaLine = buildString {
            append(id)
            str(task, "app").takeIf { it.isNotEmpty() }?.let { append(" · app ").append(it) }
            end?.let { append(" · ").append(str(it, "status")).append(" · ").append(str(it, "steps")).append(" steps") }
        }
        parts.add("<div class='meta'>" + escape(metaLine) + "</div>")
        records.filter { it["kind"]?.jsonPrimitive?.contentOrNull == "step" }.forEach { step ->
            parts.add("<div class='step'><div class='shot'>")
            val img = (step["screen"] as? JsonObject)?.let { str(it, "image") }.orEmpty()
            inlineImage(img)?.let { parts.add("<img src='$it' alt=''>") } ?: parts.add("<div class='t'>(no screenshot)</div>")
            parts.add("</div><div>")
            val screen = step["screen"] as? JsonObject
            parts.add("<div><span class='n'>Step " + str(step, "step") + "</span> <span class='t'>" +
                escape(screen?.let { str(it, "caption") }.orEmpty()) + " · " + str(step, "latency_ms") + " ms</span></div>")
            str(step, "thought").takeIf { it.isNotEmpty() }?.let { parts.add("<div class='t'>" + escape(it) + "</div>") }
            str(step, "action").takeIf { it.isNotEmpty() }?.let { parts.add("<div class='a'><b>" + escape(it) + "</b></div>") }
            (step["params"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { parts.add("<div><code>" + escape(it.toString()) + "</code></div>") }
            str(step, "raw").takeIf { it.isNotEmpty() }?.let { parts.add("<div class='err'>未解析的回复：" + escape(it.take(600)) + "</div>") }
            str(step, "error").takeIf { it.isNotEmpty() }?.let { parts.add("<div class='err'>" + escape(it) + "</div>") }
            parts.add("</div></div>")
        }
        end?.let { e -> str(e, "message").takeIf { it.isNotEmpty() }?.let { parts.add("<p><b>" + escape(str(e, "status")) + "</b>: " + escape(it) + "</p>") } }
        return parts.joinToString("\n")
    }

    // ------------------------------------------------------------------ 内部

    private fun summarize(file: File): TraceSummary? {
        val id = file.name.removeSuffix(".jsonl")
        val records = readRecords(id) ?: return null
        if (records.isEmpty()) return null
        val task = records.firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "task" } ?: JsonObject(emptyMap())
        val end = records.lastOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "end" }
        val stepCount = end?.let { str(it, "steps").toIntOrNull() }
            ?: records.count { it["kind"]?.jsonPrimitive?.contentOrNull == "step" }
        return TraceSummary(
            id = id,
            goal = str(task, "goal"),
            app = str(task, "app"),
            status = end?.let { str(it, "status") }.orEmpty().ifEmpty { "running" },
            steps = stepCount,
            started = task["t"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: file.lastModified(),
        )
    }

    private fun readRecords(id: String): List<JsonObject>? {
        val file = File(dir, "$id.jsonl")
        if (!file.isFile) return null
        return file.readLines().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let {
                runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
            }
        }
    }

    private fun inlineImage(name: String): String? {
        if (name.isEmpty()) return null
        val file = File(dir, name)
        if (!file.isFile) return null
        return runCatching { "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(file.readBytes()) }.getOrNull()
    }

    /** 只留最近 [KEEP_TRACES] 份 trace，更早的 jsonl 与其截图一并清掉。 */
    private fun prune() {
        runCatching {
            val traces = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() } ?: return@runCatching
            traces.drop(KEEP_TRACES - 1).forEach { stale ->
                val id = stale.name.removeSuffix(".jsonl")
                dir.listFiles { f -> f.isFile && f.name.startsWith("$id-") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
                stale.delete()
            }
        }.onFailure { FileLogger.w(TAG, "清理旧 trace 失败：${it.message}") }
    }

    private fun str(obj: JsonObject?, key: String): String =
        obj?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val TAG = "MobileTraceStore"
        const val DIR_NAME = "mobile-traces"
        const val KEEP_TRACES = 200
    }
}

/**
 * 一次任务的 trace 写入端：`begin` 一条 task、每步一条 step、结尾一条 end。
 * 落盘失败只记日志（不能因为 trace 写不下去而让任务失败）。
 */
class MobileTrace internal constructor(
    val id: String,
    private val file: File?,
    private val dir: File?,
) {
    private val startedAt = System.currentTimeMillis() / 1000.0

    internal fun begin(goal: String, app: String, sessionId: String?) {
        append(buildJsonObject {
            put("kind", "task")
            put("id", id)
            put("t", startedAt)
            put("goal", goal)
            put("app", app)
            put("session", sessionId ?: "")
        })
    }

    fun step(
        stepNo: Int,
        caption: String,
        image: AgentImage?,
        thought: String?,
        action: String?,
        params: Map<String, JsonElement>?,
        raw: String?,
        latencyMs: Long,
        error: String?,
    ) {
        val imageName = saveImage(stepNo, image)
        append(buildJsonObject {
            put("kind", "step")
            put("step", stepNo)
            put("t", System.currentTimeMillis() / 1000.0)
            put("screen", buildJsonObject {
                put("caption", caption)
                if (imageName != null) put("image", imageName)
            })
            put("thought", thought ?: "")
            put("action", action ?: "")
            if (params != null) put("params", JsonObject(params))
            put("raw", raw?.take(2000) ?: "")
            put("latency_ms", latencyMs)
            put("error", error ?: "")
        })
    }

    fun end(status: String, message: String, steps: Int) {
        append(buildJsonObject {
            put("kind", "end")
            put("t", System.currentTimeMillis() / 1000.0)
            put("status", status)
            put("message", message)
            put("steps", steps)
            put("seconds", (System.currentTimeMillis() / 1000.0 - startedAt))
        })
    }

    private fun saveImage(stepNo: Int, image: AgentImage?): String? {
        val data = image?.base64Data
        val target = dir
        if (data.isNullOrEmpty() || target == null) return null
        val name = "$id-$stepNo.jpg"
        return runCatching {
            File(target, name).writeBytes(Base64.getDecoder().decode(data))
            name
        }.getOrNull()
    }

    private fun append(record: JsonObject) {
        val target = file ?: return
        runCatching { target.appendText(record.toString() + "\n") }
            .onFailure { FileLogger.w("MobileTrace", "写 trace 失败：${it.message}") }
    }
}
