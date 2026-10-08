package com.aharou.core.memory

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆操作流水：谁、什么时候、把哪条记忆写成了什么。
 *
 * 记忆系统最容易出的问题不是抛异常，而是「糊里糊涂就写坏了」——某条记忆是被覆盖、
 * 被跳过、还是整理压根没跑，只看记忆文件本身看不出来。所以每次写入、归档、裁决都留一行，
 * 供设置页的「最近记忆操作」翻查。
 *
 * 落盘为 JSONL，只保留最近 [MAX_RECORDS] 行：这是诊断流水不是档案，无限增长只会拖慢读写；
 * 真正的旧版本在记忆目录的 `archive/` 里。
 */
@Singleton
class MemoryTraceLog @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 一条流水。[detail] 只放一句话理由，正文一律不进流水。 */
    data class Record(
        val at: Long,
        val source: String,
        val action: String,
        val name: String,
        val scope: String?,
        val detail: String?,
        val sessionId: String?
    )

    companion object {
        private const val TAG = "MemoryTraceLog"

        /** 流水文件名，落在记忆目录的兄弟位置，不混进记忆清单。 */
        private const val FILE_NAME = "memory-trace.jsonl"

        /** 保留条数上限：超过就重写文件只留最近的。 */
        private const val MAX_RECORDS = 300

        /** 单条 detail 的字符上限。 */
        private const val MAX_DETAIL_CHARS = 400

        const val ACTION_SAVE = "save"
        const val ACTION_NEW = "new"
        const val ACTION_EDIT = "edit"
        const val ACTION_DELETE = "delete"
        const val ACTION_ARCHIVE = "archive"
        const val ACTION_MERGE = "merge"
        const val ACTION_CONFLICT = "conflict"
        const val ACTION_SKIP = "skip"

        const val SOURCE_TOOL = "tool"
        const val SOURCE_CURATOR = "curator"
        const val SOURCE_DISTILLER = "distiller"
    }

    private val file: File get() = File(context.filesDir, "aharou-global/$FILE_NAME")

    /** 追加一条流水；写失败只记日志，绝不影响调用方的写入流程。 */
    fun record(
        source: String,
        action: String,
        name: String,
        scope: String? = null,
        detail: String? = null,
        sessionId: String? = null
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("src", source)
                .put("action", action)
                .put("name", name)
                .put("scope", scope ?: "")
                .put("detail", detail?.take(MAX_DETAIL_CHARS) ?: "")
                .put("session", sessionId ?: "")
            file.appendText(obj.toString() + "\n")
            trimIfNeeded()
        }.onFailure { FileLogger.w(TAG, "写记忆流水失败: ${it.message}") }
    }

    /** 最近 [limit] 条，最新的在最前。 */
    fun recent(limit: Int = 50): List<Record> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines()
            .mapNotNull { parse(it) }
            .takeLast(limit)
            .asReversed()
    }.getOrElse {
        FileLogger.w(TAG, "读记忆流水失败: ${it.message}")
        emptyList()
    }

    private fun parse(line: String): Record? = runCatching {
        val obj = JSONObject(line)
        Record(
            at = obj.optLong("t"),
            source = obj.optString("src"),
            action = obj.optString("action"),
            name = obj.optString("name"),
            scope = obj.optString("scope").takeIf { it.isNotEmpty() },
            detail = obj.optString("detail").takeIf { it.isNotEmpty() },
            sessionId = obj.optString("session").takeIf { it.isNotEmpty() }
        )
    }.getOrNull()

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_RECORDS) return
        file.writeTextSafely(lines.takeLast(MAX_RECORDS).joinToString("\n", postfix = "\n"), TAG)
    }
}
