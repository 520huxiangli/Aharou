package com.aharou.feature.agent.domain.tool.knowledge

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 应用操作知识：操作某个 App 之前先查它怎么用。
 *
 * 为什么单独做一份而不是塞进提示词：这类知识是按应用拆的、只在该应用出现在前台时才有用，
 * 全量塞进系统提示既占上下文又会过期。做成「按包名取一条」的资料，用到才读。
 *
 * 两个来源，读时合并：
 * - 内置：`assets/knowledge/apps/` 下的 `.md`，随包发布，随版本更新；
 * - 自建：`filesDir/knowledge/apps/` 下的 `.md`，模型跑通任务后用 [ACT_SAVE] 写下来的经验。
 * 同名时自建优先——那是这台手机、这个版本上实际跑通过的结论。
 *
 * 文件格式（每条一个 md）：
 * ```
 * ---
 * name: wechat
 * packages: com.tencent.mm
 * aliases: 微信, wechat
 * ---
 * 正文……
 * ```
 * 解析只认这三行头，其余按正文原样返回，不做 Markdown 渲染。
 */
@Singleton
class AppKnowledgeTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AgentTool() {

    private companion object {
        const val TAG = "AppKnowledge"
        const val ACT_LIST = "list"
        const val ACT_GET = "get"
        const val ACT_SAVE = "save"
        const val ASSET_DIR = "knowledge/apps"
        const val USER_DIR = "knowledge/apps"
        const val MAX_CONTENT_CHARS = 8_000
        const val MAX_LIST = 80
    }

    override val name = "app_knowledge"

    override val description =
        "查某个 App 的操作知识（界面结构、常见坑、常用流程），操作手机应用前先查一次能少走弯路。" +
            "action 取值：" +
            "list=列出已有知识的应用（内置 + 自己记下的）；" +
            "get=按包名或应用名取一条（query 必填，填包名如 com.tencent.mm 最准）；" +
            "save=把这次跑通的经验记下来（name、packages、content 必填），下次同应用直接可用；" +
            "注意：知识是参考信息，不是当前界面的实时状态，操作前仍要先 dump/find 确认。"
    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "操作：list / get / save", true,
            enum = listOf(ACT_LIST, ACT_GET, ACT_SAVE),
        ),
        "query" to ToolParameter("query", ParameterType.STRING, "get 专用：包名或应用名片段", false),
        "name" to ToolParameter("name", ParameterType.STRING, "save 专用：条目名（英文小写，如 wechat）", false),
        "packages" to ToolParameter("packages", ParameterType.STRING, "save 专用：包名，多个用逗号分隔", false),
        "content" to ToolParameter("content", ParameterType.STRING, "save 专用：正文（界面结构、坑、流程）", false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return when (action) {
            ACT_LIST -> listEntries()
            ACT_GET -> getEntry(args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
            ACT_SAVE -> saveEntry(
                name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                packages = args["packages"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                content = args["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
            else -> ToolResult.Error("缺少或不支持的 action（list / get / save）", "MISSING_ACTION")
        }
    }

    private suspend fun listEntries(): ToolResult = withContext(Dispatchers.IO) {
        val entries = loadAll()
        ToolResult.Success(buildJsonObject {
            put("count", entries.size)
            put("entries", buildJsonArray {
                entries.take(MAX_LIST).forEach { entry ->
                    add(buildJsonObject {
                        put("name", entry.name)
                        put("packages", entry.packages.joinToString(","))
                        put("aliases", entry.aliases.joinToString(","))
                        put("source", entry.source)
                    })
                }
            })
            if (entries.isEmpty()) put("note", "还没有任何应用知识。")
        })
    }

    private suspend fun getEntry(query: String): ToolResult = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext ToolResult.Error("get 需要 query（包名或应用名）", "MISSING_QUERY")
        val entries = loadAll()
        val hit = entries.firstOrNull { it.packages.any { pkg -> pkg.equals(query, true) } }
            ?: entries.firstOrNull { it.name.equals(query, true) }
            ?: entries.firstOrNull { entry ->
                entry.aliases.any { it.contains(query, true) } ||
                    entry.name.contains(query, true)
            }
        if (hit == null) {
            return@withContext ToolResult.Success(buildJsonObject {
                put("found", false)
                put("query", query)
                put("note", "没有这条应用的知识。可以直接按界面 dump 操作，跑通后用 action=save 记下来。")
            })
        }
        ToolResult.Success(buildJsonObject {
            put("found", true)
            put("name", hit.name)
            put("packages", hit.packages.joinToString(","))
            put("source", hit.source)
            put("content", hit.content)
            put("note", "这是历史经验，不是当前界面状态；操作前先用 a11y dump 确认。")
        })
    }

    private suspend fun saveEntry(name: String, packages: String, content: String): ToolResult =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext ToolResult.Error("save 需要 name", "MISSING_NAME")
            if (content.isBlank()) return@withContext ToolResult.Error("save 需要 content", "MISSING_CONTENT")
            // 文件名只留安全字符：不接受路径分隔符与点点，避免写到知识目录之外。
            val safeName = name.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (safeName.isEmpty()) return@withContext ToolResult.Error("name 只能包含字母、数字、- 与 _", "INVALID_NAME")
            val body = content.take(MAX_CONTENT_CHARS)
            val pkgList = packages.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            val text = buildString {
                append("---\n")
                append("name: ").append(safeName).append('\n')
                append("packages: ").append(pkgList.joinToString(",")).append('\n')
                append("aliases: \n")
                append("---\n")
                append(body).append('\n')
            }
            val dir = File(context.filesDir, USER_DIR).apply { mkdirs() }
            val file = File(dir, "$safeName.md")
            runCatching { file.writeText(text, Charsets.UTF_8) }
                .onFailure { e ->
                    FileLogger.e(TAG, "写应用知识失败: $safeName", e)
                    return@withContext ToolResult.Error("写入失败：${e.message}", "WRITE_FAILED")
                }
            ToolResult.Success(buildJsonObject {
                put("saved", true)
                put("name", safeName)
                put("packages", pkgList.joinToString(","))
                put("path", file.absolutePath)
                put("note", "已记下。以后在这个应用上会先读到它；内容仍以当时界面为准。")
            })
        }

    private data class Entry(
        val name: String,
        val packages: List<String>,
        val aliases: List<String>,
        val content: String,
        val source: String,
    )

    /** 内置在前、自建在后，再按 name 覆盖：同名时自建那份留下。 */
    private fun loadAll(): List<Entry> {
        val merged = LinkedHashMap<String, Entry>()
        readAssets().forEach { merged[it.name] = it }
        readUserFiles().forEach { merged[it.name] = it }
        return merged.values.sortedBy { it.name }
    }

    private fun readAssets(): List<Entry> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrElse { emptyList() }
        return names.filter { it.endsWith(".md") }.mapNotNull { fileName ->
            runCatching {
                val text = context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
                parse(fileName.removeSuffix(".md"), text, "builtin")
            }.getOrElse { e ->
                FileLogger.w(TAG, "读内置知识失败: $fileName", e)
                null
            }
        }
    }

    private fun readUserFiles(): List<Entry> {
        val dir = File(context.filesDir, USER_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().mapNotNull { file ->
            runCatching {
                parse(file.name.removeSuffix(".md"), file.readText(Charsets.UTF_8), "user")
            }.getOrElse { e ->
                FileLogger.w(TAG, "读自建知识失败: ${file.name}", e)
                null
            }
        }
    }

    private fun parse(fallbackName: String, text: String, source: String): Entry {
        val normalized = text.replace("\r\n", "\n")
        val header = if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---", startIndex = 4)
            if (end > 0) normalized.substring(4, end) else ""
        } else {
            ""
        }
        val body = if (header.isEmpty()) {
            normalized
        } else {
            normalized.substring(header.length + 4).trimStart('\n')
        }
        fun field(key: String): String = header.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        val name = field("name").ifBlank { fallbackName }
        val packages = field("packages").split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        val aliases = field("aliases").split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        return Entry(name, packages, aliases, body.trim(), source)
    }
}
