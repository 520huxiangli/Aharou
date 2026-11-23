package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `fs.*` 的补充原语：编辑、区间读、查找、grep、解包。
 *
 * 全部经 [PluginHostPaths] 的公开入口（read / write / list / stat / readBinary / writeBinary / mkdir）
 * 完成路径解析，因此 `data://`、`~`、越界过滤与「不许写根」这些约束跟既有方法完全一致；
 * 这里不自己做路径拼接之外的解析，也不接受未经它解析的路径。
 *
 * 递归遍历统一受扫描上限约束：插件在超大目录上 find/grep 时得到的是「扫了多少、是否截断」，
 * 而不是一个把宿主拖到无响应的长任务。
 */
@Singleton
class PluginFsService @Inject constructor() {

    /** 唯一替换：找不到旧内容、或多处命中却没允许全替换时给明确错误。 */
    fun edit(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val path = PluginHostPaths.requireString(params, "path")
        val oldContent = PluginHostPaths.requireString(params, "oldContent")
        val newContent = PluginHostPaths.requireString(params, "newContent")
        if (oldContent.isEmpty()) throw paramInvalid("oldContent is empty")
        val replaceAll = optionalBoolean(params, "replaceAll") ?: false
        val content = readText(paths, path)
        val occurrences = countOccurrences(content, oldContent)
        if (occurrences == 0) {
            throw PluginHostException("CONTENT_NOT_FOUND", "oldContent not found in $path")
        }
        if (occurrences > 1 && !replaceAll) {
            throw PluginHostException(
                "CONTENT_NOT_UNIQUE",
                "oldContent matches $occurrences places in $path; pass replaceAll=true or narrow it"
            )
        }
        val updated = if (replaceAll) content.replace(oldContent, newContent) else content.replaceFirst(oldContent, newContent)
        paths.write(path, updated)
        return buildJsonObject {
            put("ok", true)
            put("replacements", occurrences)
            put("size", updated.toByteArray(Charsets.UTF_8).size)
        }
    }

    /**
     * 按字节区间读取（左闭右开）。用字节而不是字符偏移：插件拿到的通常是文件的字节位置，
     * 按字符切会让中英混排的文件偏移对不上。切成两半的多字节字符会变成替换字符，属预期。
     */
    fun readRange(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val path = PluginHostPaths.requireString(params, "path")
        val start = optionalLong(params, "start") ?: 0L
        if (start < 0) throw paramInvalid("start must be >= 0")
        val maxBytes = optionalLong(params, "maxBytes") ?: MAX_RANGE_BYTES
        if (maxBytes <= 0) throw paramInvalid("maxBytes must be > 0")
        val bytes = readBytes(paths, path, maxBytes.coerceAtMost(MAX_RANGE_BYTES))
        val total = bytes.size.toLong()
        if (start > total) {
            throw PluginHostException("PARAM_INVALID", "start beyond file size: $total")
        }
        val requestedEnd = optionalLong(params, "end") ?: total
        if (requestedEnd < start) throw paramInvalid("end must be >= start")
        val end = requestedEnd.coerceAtMost(total)
        val slice = bytes.copyOfRange(start.toInt(), end.toInt())
        return buildJsonObject {
            put("content", String(slice, Charsets.UTF_8))
            put("start", start)
            put("end", end)
            put("size", total)
            put("truncated", end < total)
        }
    }

    /**
     * 按文件名查找。带 `*` / `?` 时按通配符匹配，否则按包含匹配（都不区分大小写）。
     * 默认跳过 `.git` 与 `node_modules`：插件找源码时几乎不想扫它们，扫了也只是拖时间。
     */
    fun find(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val path = PluginHostPaths.requireString(params, "path")
        val pattern = PluginHostPaths.requireString(params, "pattern")
        if (pattern.isBlank()) throw paramInvalid("pattern is blank")
        val limit = (optionalLong(params, "limit") ?: DEFAULT_FIND_LIMIT).coerceIn(1, MAX_RESULT_LIMIT).toInt()
        val matcher = nameMatcher(pattern)
        val stat = paths.stat(path)
        val statObj = stat as? JsonObject
        val isDir = (statObj?.get("isDir") as? JsonPrimitive)?.booleanOrNull ?: false
        if ((statObj?.get("exists") as? JsonPrimitive)?.booleanOrNull == false) {
            throw PluginHostException("NOT_FOUND", "not found: $path")
        }

        val matches = mutableListOf<JsonObject>()
        var scanned = 0
        var truncated = false

        if (!isDir) {
            val name = fileName(path)
            if (matcher(name)) {
                matches += buildJsonObject {
                    put("path", path)
                    put("name", name)
                    put("isDir", false)
                    put("size", (statObj?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L)
                }
            }
            return buildJsonObject {
                put("matches", JsonArray(matches))
                put("scanned", 1)
                put("truncated", false)
            }
        }

        walk(paths, path) { child, name, childIsDir ->
            scanned++
            if (matches.size >= limit) {
                truncated = true
                return@walk false
            }
            if (matcher(name)) {
                matches += buildJsonObject {
                    put("path", child)
                    put("name", name)
                    put("isDir", childIsDir)
                    put("size", if (childIsDir) 0L else runCatching {
                        (paths.stat(child) as? JsonObject)?.get("size")?.let { (it as JsonPrimitive).longOrNull } ?: 0L
                    }.getOrDefault(0L))
                }
            }
            true
        }
        if (matches.size >= limit) truncated = true
        return buildJsonObject {
            put("matches", JsonArray(matches))
            put("scanned", scanned)
            put("truncated", truncated)
        }
    }

    /** 按内容匹配：合法正则按正则，不合法按字面（插件写错的模式不该让整个调用失败）。 */
    fun grep(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val path = PluginHostPaths.requireString(params, "path")
        val pattern = PluginHostPaths.requireString(params, "pattern")
        if (pattern.isEmpty()) throw paramInvalid("pattern is empty")
        val limit = (optionalLong(params, "limit") ?: DEFAULT_GREP_LIMIT).coerceIn(1, MAX_RESULT_LIMIT).toInt()
        val regex = runCatching { Regex(pattern) }.getOrNull()

        val matches = mutableListOf<JsonObject>()
        var scannedFiles = 0
        var truncated = false

        fun scanFile(filePath: String, strict: Boolean = false) {
            if (matches.size >= limit) {
                truncated = true
                return
            }
            val text = if (strict) readText(paths, filePath)
            else runCatching { readText(paths, filePath) }.getOrNull() ?: return
            scannedFiles++
            var lineNo = 0
            for (line in text.lineSequence()) {
                lineNo++
                val hit = if (regex != null) regex.containsMatchIn(line) else line.contains(pattern, ignoreCase = true)
                if (!hit) continue
                matches += buildJsonObject {
                    put("path", filePath)
                    put("line", lineNo)
                    put("text", line.take(MAX_LINE_CHARS))
                }
                if (matches.size >= limit) {
                    truncated = true
                    return
                }
            }
        }

        val dirStat = paths.stat(path) as? JsonObject
        val isDir = (dirStat?.get("isDir") as? JsonPrimitive)?.booleanOrNull ?: false
        if ((dirStat?.get("exists") as? JsonPrimitive)?.booleanOrNull == false) {
            throw PluginHostException("NOT_FOUND", "not found: $path")
        }
        if (!isDir) {
            scanFile(path, strict = true)
        } else {
            walk(paths, path) { child, _, childIsDir ->
                if (!childIsDir) scanFile(child)
                matches.size < limit && scannedFiles < MAX_GREP_FILES
            }
            if (scannedFiles >= MAX_GREP_FILES) truncated = true
        }
        return buildJsonObject {
            put("matches", JsonArray(matches))
            put("scannedFiles", scannedFiles)
            put("truncated", truncated)
        }
    }

    /**
     * 把文件或目录打成 zip。
     *
     * 条目名取相对源路径：压缩包会被别的程序解开，不该把本机目录结构写进去。
     * 条目数与解压总量沿用 unzip 的上限，避免插件一个调用把磁盘写满。
     */
    fun zip(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val source = PluginHostPaths.requireString(params, "path")
        val target = PluginHostPaths.requireString(params, "target")
        val stat = paths.stat(source) as? JsonObject
            ?: throw PluginHostException("IO_ERROR", "source not found: $source")
        val sourceIsDir = (stat["isDir"] as? JsonPrimitive)?.booleanOrNull ?: false
        val buffer = ByteArrayOutputStream()
        var entries = 0
        var totalBytes = 0L
        ZipOutputStream(buffer).use { zip ->
            val rootPrefix = source.trimEnd('/') + "/"
            fun addEntry(filePath: String, entryName: String) {
                entries += 1
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    throw PluginHostException("TOO_LARGE", "archive has more than $MAX_ARCHIVE_ENTRIES entries")
                }
                val bytes = readBytes(paths, filePath, MAX_ARCHIVE_BYTES)
                totalBytes += bytes.size
                if (totalBytes > MAX_ARCHIVE_BYTES) {
                    throw PluginHostException("TOO_LARGE", "archive larger than $MAX_ARCHIVE_BYTES bytes")
                }
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
            if (sourceIsDir) {
                walk(paths, source) { child, name, isDir ->
                    if (!isDir) {
                        val relative = child.removePrefix(rootPrefix).ifBlank { name }
                        if (isSafeEntryName(relative)) addEntry(child, relative)
                    }
                    true
                }
            } else {
                addEntry(source, fileName(source))
            }
        }
        val bytes = buffer.toByteArray()
        paths.writeBinary(target, Base64.getEncoder().encodeToString(bytes))
        return buildJsonObject {
            put("path", target)
            put("source", source)
            put("entries", entries)
            put("bytes", bytes.size)
        }
    }

    /**
     * 解压 zip 到目标目录。条目名逐个归一化：绝对路径、含 `..` 段的条目直接拒绝——
     * 压缩包里的路径是压缩包作者写的，不能让它决定写到哪。
     */
    fun unzip(paths: PluginHostPaths, params: JsonObject): JsonElement {
        val source = PluginHostPaths.requireString(params, "path")
        val target = PluginHostPaths.requireString(params, "target")
        val archive = readBytes(paths, source, MAX_ARCHIVE_BYTES)
        var entries = 0
        var extractedBytes = 0L
        val extracted = mutableListOf<String>()
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ARCHIVE_ENTRIES) {
                        throw PluginHostException("TOO_LARGE", "archive has more than $MAX_ARCHIVE_ENTRIES entries")
                    }
                    val name = entry.name.replace('\\', '/')
                    if (!isSafeEntryName(name)) {
                        throw PluginHostException("PATH_DENIED", "unsafe archive entry: ${entry.name}")
                    }
                    val outPath = target.trimEnd('/') + "/" + name
                    if (entry.isDirectory) {
                        paths.mkdir(outPath)
                        continue
                    }
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                        extractedBytes += read
                        if (extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw PluginHostException("TOO_LARGE", "extracted content is too large")
                        }
                        // 单个条目的上限在读取过程中就拦住：压缩包可以声明一个 1 字节的条目却解出几十兆。
                        if (buffer.size() > MAX_UNZIP_FILE_BYTES) {
                            throw PluginHostException("TOO_LARGE", "archive entry too large: $name")
                        }
                    }
                    val bytes = buffer.toByteArray()
                    paths.writeBinary(outPath, Base64.getEncoder().encodeToString(bytes))
                    extracted += outPath
                }
            }
        } catch (e: PluginHostException) {
            throw e
        } catch (e: Exception) {
            throw PluginHostException("IO_ERROR", e.message ?: "unzip failed")
        }
        return buildJsonObject {
            put("ok", true)
            put("entries", entries)
            put("bytes", extractedBytes)
            put("paths", JsonArray(extracted.map { JsonPrimitive(it) }))
        }
    }

    /**
     * 广度优先遍历 [root] 的直接子项；[visitor] 返回 false 时停止。
     * 每层的目录也交给 visitor 判断，由调用方决定要不要进去。
     */
    private fun walk(
        paths: PluginHostPaths,
        root: String,
        visitor: (child: String, name: String, isDir: Boolean) -> Boolean
    ) {
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue
            val entries = runCatching { paths.list(dir) }.getOrNull() as? JsonObject ?: continue
            val items = entries["entries"] as? JsonArray ?: continue
            for (item in items) {
                val obj = item as? JsonObject ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val isDir = (obj["isDir"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (isDir && name in SKIPPED_DIRS) continue
                visited++
                if (visited > MAX_SCANNED_ENTRIES) return
                val child = dir.trimEnd('/') + "/" + name
                if (!visitor(child, name, isDir)) return
                if (isDir) queue.addLast(child to depth + 1)
            }
        }
    }

    /** 读文本内容；沿用 [PluginHostPaths.read] 的大小上限与错误码。 */
    private fun readText(paths: PluginHostPaths, path: String): String {
        val result = paths.read(path) as? JsonObject
            ?: throw PluginHostException("IO_ERROR", "unexpected read result: $path")
        return (result["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun readBytes(paths: PluginHostPaths, path: String, maxBytes: Long): ByteArray {
        val result = paths.readBinary(path, maxBytes) as? JsonObject
            ?: throw PluginHostException("IO_ERROR", "unexpected read result: $path")
        val base64 = (result["base64"] as? JsonPrimitive)?.contentOrNull
            ?: throw PluginHostException("IO_ERROR", "unexpected read result: $path")
        return Base64.getDecoder().decode(base64)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun nameMatcher(pattern: String): (String) -> Boolean {
        val hasWildcard = pattern.any { it == '*' || it == '?' }
        if (!hasWildcard) {
            return { name -> name.contains(pattern, ignoreCase = true) }
        }
        val regex = buildString {
            append('^')
            pattern.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }.let { Regex(it, RegexOption.IGNORE_CASE) }
        return { name -> regex.matches(name) }
    }

    private fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("/")) return false
        if (name.contains(':')) return false
        return name.split('/').none { it == ".." || it.isEmpty() }
    }

    private fun optionalBoolean(params: JsonObject, key: String): Boolean? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.booleanOrNull ?: throw paramInvalid("$key must be a boolean")
    }

    private fun optionalLong(params: JsonObject, key: String): Long? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.longOrNull ?: throw paramInvalid("$key must be a number")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val DEFAULT_FIND_LIMIT = 200L
        const val DEFAULT_GREP_LIMIT = 200L
        const val MAX_RESULT_LIMIT = 1_000L
        const val MAX_RANGE_BYTES = 8L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 5_000
        const val MAX_EXTRACTED_BYTES = 64L * 1024 * 1024

        /** 单条目上限与 PluginHostPaths.writeBinary 的写上限一致，超了本来也写不进去。 */
        const val MAX_UNZIP_FILE_BYTES = 1024 * 1024
        const val MAX_GREP_FILES = 2_000
        const val MAX_SCANNED_ENTRIES = 20_000
        const val MAX_DEPTH = 32
        const val MAX_LINE_CHARS = 400
        const val COPY_BUFFER_BYTES = 8 * 1024

        val SKIPPED_DIRS = setOf(".git", "node_modules", ".gradle")
    }
}
