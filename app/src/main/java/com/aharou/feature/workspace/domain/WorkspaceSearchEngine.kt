package com.aharou.feature.workspace.domain

import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一条工作区搜索命中：文件名命中（[line] 为 0）或内容命中（[line] 为 1 起行号，
 * [column]/[length] 给出该行内的命中区间）。
 */
data class WorkspaceSearchHit(
    /** 容器路径，如 `~/workspace/app/build.gradle.kts`。 */
    val path: String,
    /** 文件名（路径最后一段）。 */
    val name: String,
    /** 相对工作区根的目录（无目录时为空串），供结果行做次要信息展示。 */
    val directory: String,
    /** 1 起行号；0 表示这条是文件名命中、没有具体行。 */
    val line: Int,
    /** 命中的整行文本（已去行尾换行）；文件名命中为空串。 */
    val text: String,
    /** 1 起列号；0 表示无（文件名命中）。 */
    val column: Int,
    /** 命中长度（字符数）；0 表示无。 */
    val length: Int
) {
    val isFileName: Boolean get() = line == 0
}

/** 一次搜索的结果：[hits] 为命中列表（文件名命中在前、内容命中在后），[truncated] 表示因上限截断。 */
data class WorkspaceSearchResult(
    val hits: List<WorkspaceSearchHit>,
    val truncated: Boolean,
    /** 实际后端：rg（容器内 ripgrep）/ walk（App 内遍历）/ none（空关键词）。 */
    val engine: String
)

/**
 * 工作区搜索：文件名 + 文件内容，一次调用同时出两类命中。
 *
 * 主路径走容器内的 ripgrep——与 VS Code / Trae 的全局搜索同一套路：内容用 `rg --json`
 * （事件流里直接带路径、行号、整行文本与命中区间），文件名用 `rg --files` 后在客户端过滤
 * （ripgrep 只搜内容，文件名匹配本就是客户端的事）。容器未就绪或容器内没装 rg 时
 * 回退到 [FileAccessProvider] 直接遍历（本地 java.io / 远程 SFTP 都能走），代价是慢，故设更紧的上限。
 */
@Singleton
class WorkspaceSearchEngine @Inject constructor(
    private val commandEngine: CommandEngine,
    private val fileAccess: FileAccessProvider,
    private val workspaceRepository: WorkspaceRepository
) {

    private companion object {
        const val TAG = "WorkspaceSearch"

        /** 命中总数上限（文件名 + 内容合计），超出即截断并置 [WorkspaceSearchResult.truncated]。 */
        const val MAX_HITS = 200

        /** `rg --json` 单次读取的最大行数（每条命中还带 begin/end 行，留足余量）。 */
        const val MAX_JSON_LINES = 8000

        /** `rg --files` 单次读取的最大文件名条数；取满即视为截断。 */
        const val MAX_LISTED_FILES = 4000

        /** 单个文件最多取几条内容命中，避免一个大文件刷屏。 */
        const val MAX_HITS_PER_FILE = 20

        const val SEARCH_TIMEOUT_MS = 20_000L

        /** 回退遍历（容器未就绪/未装 rg）的预算：深度、扫描文件数、单文件大小。 */
        const val FALLBACK_MAX_DEPTH = 8
        const val FALLBACK_SCAN_FILES = 300
        const val FALLBACK_MAX_FILE_BYTES = 256L * 1024

        const val ENGINE_RG = "rg"
        const val ENGINE_WALK = "walk"
        const val ENGINE_NONE = "none"

        val JSON = Json { ignoreUnknownKeys = true }

        /** 回退遍历时直接跳过的二进制/大体积扩展名（rg 主路径由 ripgrep 自行判定二进制）。 */
        val BINARY_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz",
            "mp3", "wav", "ogg", "m4a", "flac", "aac",
            "mp4", "mkv", "mov", "avi", "webm",
            "zip", "gz", "bz2", "xz", "7z", "rar", "jar", "apk", "aab", "dex",
            "so", "dylib", "bin", "exe", "class", "o", "a",
            "ttf", "otf", "woff", "woff2", "pdf",
            "db", "sqlite", "jks", "keystore", "p12"
        )
    }

    /** 在工作区里搜 [rawQuery]：先试容器内 ripgrep，不可用则回退 App 内遍历。 */
    suspend fun search(rawQuery: String): WorkspaceSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return WorkspaceSearchResult(emptyList(), false, ENGINE_NONE)
        return searchWithRipgrep(query) ?: searchByWalking(query)
    }

    // ── 主路径：容器内 ripgrep ──

    private suspend fun searchWithRipgrep(query: String): WorkspaceSearchResult? {
        val projectPath = workspaceRepository.currentPath().takeIf { it.isNotBlank() } ?: return null

        val contentResult = commandEngine.runCommandSyncIfReady(
            command = "rg --json --no-config --hidden --ignore-case --crlf " +
                "--max-filesize 2M --max-count $MAX_HITS_PER_FILE --max-columns 400 -- " +
                "${shellQuote(query)} . | head -n $MAX_JSON_LINES",
            projectPath = projectPath,
            timeoutMs = SEARCH_TIMEOUT_MS
        ) ?: return null
        if (isRgMissing(contentResult.output)) {
            FileLogger.d(TAG, "容器内没有 rg，回退 App 内遍历")
            return null
        }

        val hits = mutableListOf<WorkspaceSearchHit>()
        parseRgJsonMatches(contentResult.output, hits)

        val names = commandEngine.runCommandSyncIfReady(
            command = "rg --files --no-config --hidden . | head -n $MAX_LISTED_FILES",
            projectPath = projectPath,
            timeoutMs = SEARCH_TIMEOUT_MS
        )
        var nameTruncated = false
        val nameHits = if (names == null || isRgMissing(names.output)) {
            emptyList()
        } else {
            val listed = names.output.lineSequence().filter { it.isNotBlank() }.toList()
            nameTruncated = listed.size >= MAX_LISTED_FILES
            matchFileNames(listed, query, MAX_HITS)
        }

        // 文件名命中排在前面（用户多半在找文件），内容命中随后；整体不超过 MAX_HITS。
        val merged = (nameHits + hits).take(MAX_HITS)
        val truncated = nameTruncated || nameHits.size + hits.size > MAX_HITS
        FileLogger.d(TAG, "rg 搜索完成 query=$query 文件名命中=${nameHits.size} 内容命中=${hits.size}")
        return WorkspaceSearchResult(merged, truncated, ENGINE_RG)
    }

    /**
     * 解析 `rg --json` 事件流：只取 `type=match`（`begin`/`end`/`context`/`summary` 忽略）。
     * 事件里 path 是相对当前目录的路径，行号与整行文本、首个命中区间都在 data 下。
     */
    private fun parseRgJsonMatches(output: String, out: MutableList<WorkspaceSearchHit>) {
        for (line in output.lineSequence()) {
            if (out.size >= MAX_HITS) return
            if (!line.contains("\"type\":\"match\"")) continue
            val data = runCatching { JSON.parseToJsonElement(line).jsonObject["data"]?.jsonObject }
                .getOrNull() ?: continue
            val relative = data["path"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.removePrefix("./")
                ?: continue
            val lineNumber = data["line_number"]?.jsonPrimitive?.intOrNull ?: continue
            val text = data["lines"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            val subMatch = data["submatches"]?.jsonArray?.firstOrNull()?.jsonObject
            val start = subMatch?.get("start")?.jsonPrimitive?.intOrNull ?: 0
            val end = subMatch?.get("end")?.jsonPrimitive?.intOrNull ?: start
            out += hit(
                relative = relative,
                line = lineNumber,
                text = text.trimEnd('\n', '\r'),
                column = start + 1,
                length = (end - start).coerceAtLeast(0)
            )
        }
    }

    // ── 回退路径：App 内遍历 ──

    private suspend fun searchByWalking(query: String): WorkspaceSearchResult = withContext(Dispatchers.IO) {
        val files = runCatching {
            fileAccess.listFilesRecursive(WorkspacePathMapper.CONTAINER_ROOT, FALLBACK_MAX_DEPTH)
                .filterNot { rel -> rel.split('/').any { it == ".git" } }
        }.getOrElse { error ->
            FileLogger.w(TAG, "遍历工作区失败", error)
            emptyList()
        }

        val hits = mutableListOf<WorkspaceSearchHit>()
        hits += matchFileNames(files, query, MAX_HITS)

        var scanned = 0
        var truncated = files.size > FALLBACK_SCAN_FILES
        for (relative in files) {
            if (hits.size >= MAX_HITS || scanned >= FALLBACK_SCAN_FILES) {
                truncated = true
                break
            }
            val name = relative.substringAfterLast('/')
            if (name.substringAfterLast('.', "").lowercase() in BINARY_EXTS) continue
            val path = "${WorkspacePathMapper.CONTAINER_ROOT}/$relative"
            val size = runCatching { fileAccess.fileSize(path) }.getOrDefault(0L)
            if (size <= 0L || size > FALLBACK_MAX_FILE_BYTES) continue
            scanned++
            val lines = runCatching { fileAccess.readLines(path) }.getOrNull() ?: continue
            var inFile = 0
            var lineNumber = 0
            for (text in lines) {
                lineNumber++
                if (inFile >= MAX_HITS_PER_FILE || hits.size >= MAX_HITS) break
                val column = text.indexOf(query, ignoreCase = true)
                if (column < 0) continue
                inFile++
                hits += hit(relative, lineNumber, text.trimEnd(), column + 1, query.length)
            }
        }

        FileLogger.d(TAG, "遍历搜索完成 query=$query 命中=${hits.size} 扫描文件=$scanned")
        WorkspaceSearchResult(hits.take(MAX_HITS), truncated, ENGINE_WALK)
    }

    // ── 公共小件 ──

    /** 文件名/相对路径的客户端匹配：名字先于路径命中，便于「找文件」时把真正的同名文件排在前面。 */
    private fun matchFileNames(relativePaths: List<String>, query: String, limit: Int): List<WorkspaceSearchHit> {
        val lowered = query.lowercase()
        val byName = mutableListOf<WorkspaceSearchHit>()
        val byPath = mutableListOf<WorkspaceSearchHit>()
        for (relative in relativePaths) {
            val clean = relative.removePrefix("./")
            if (clean.isEmpty()) continue
            val name = clean.substringAfterLast('/')
            when {
                name.lowercase().contains(lowered) -> byName += hit(clean)
                clean.lowercase().contains(lowered) -> byPath += hit(clean)
                else -> continue
            }
            if (byName.size >= limit) break
        }
        return (byName + byPath).take(limit)
    }

    private fun hit(
        relative: String,
        line: Int = 0,
        text: String = "",
        column: Int = 0,
        length: Int = 0
    ) = WorkspaceSearchHit(
        path = "${WorkspacePathMapper.CONTAINER_ROOT}/$relative",
        name = relative.substringAfterLast('/'),
        directory = relative.substringBeforeLast('/', ""),
        line = line,
        text = text,
        column = column,
        length = length
    )

    /** 127 = shell 找不到命令；输出里通常也能看到 command not found，双保险。 */
    private fun isRgMissing(output: String): Boolean =
        output.contains("command not found", ignoreCase = true) ||
            output.contains("rg: not found", ignoreCase = true)

}
