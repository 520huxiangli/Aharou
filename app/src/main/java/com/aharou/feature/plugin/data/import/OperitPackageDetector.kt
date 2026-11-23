package com.aharou.feature.plugin.data.import

import com.aharou.feature.plugin.domain.PluginError
import com.aharou.feature.plugin.domain.PluginErrorCode
import com.aharou.feature.plugin.domain.PluginManifestParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 识别到的 Operit 包形态。 */
enum class OperitPackageFormat {
    /** 单个 `.js`，头部带 METADATA。 */
    SINGLE_SCRIPT,

    /** `.toolpkg`（ZIP）：manifest.hjson / manifest.json + 若干 JS。 */
    TOOL_PACKAGE,

    /** 一个 JSON：`commands[]` 命令模板 + `args[]`。 */
    SKILL
}

data class OperitFile(val path: String, val bytes: ByteArray)

/** 一段带 METADATA 的 JS。元数据解析失败时 [metadata] 为 null，但仍参与兼容性扫描。 */
data class OperitScript(val path: String, val text: String, val metadata: OperitMetadata?)

data class OperitSkillCommand(val name: String, val description: String, val template: String)

/** toolpkg 的 `manifest.json` 字段；缺失字段全靠 METADATA 补充。 */
data class OperitManifestInfo(
    val displayName: String? = null,
    val displayNameZh: String? = null,
    val displayNameEn: String? = null,
    val description: String? = null,
    val descriptionZh: String? = null,
    val descriptionEn: String? = null,
    val version: String? = null,
    val author: String? = null
)

/** 已经读进内存、通过路径与体积校验的 Operit 包。 */
data class OperitSource(
    val format: OperitPackageFormat,
    val sourceName: String?,
    val packageId: String?,
    val apiVersion: String?,
    val mainFile: String?,
    val files: List<OperitFile>,
    val scripts: List<OperitScript>,
    val manifest: OperitManifestInfo? = null,
    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillCommands: List<OperitSkillCommand> = emptyList()
) {
    val toolCount: Int get() = scripts.sumOf { script -> script.metadata?.tools?.count { !it.advice } ?: 0 }
}

sealed interface OperitDetection {
    data class Operit(val source: OperitSource) : OperitDetection

    data class Failed(val errors: List<PluginError>) : OperitDetection

    /** 不是 Operit 包，交回现有插件导入流程（含 Aharou 的 plugin.json 压缩包）。 */
    data object NotOperit : OperitDetection
}

/**
 * 识别用户选中的文件是不是 Operit 包。
 *
 * 判定顺序：ZIP 里根目录有 `plugin.json` 就是 Aharou 自己的插件，直接放行；
 * 有 `manifest.hjson` / `manifest.json` 或带 METADATA 的 JS 才算 `.toolpkg`；
 * 非 ZIP 的 JSON 里出现 `mcpServers` 或 `commands[]` 才是 MCP / 技能形态。
 * 只有识别成功才会继续转换，其余一律交回原流程，避免把普通压缩包的失败原因改写。
 */
object OperitPackageDetector {

    const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_SCAN_BYTES = 512L * 1024

    fun detect(fileName: String?, bytes: ByteArray): OperitDetection {
        if (bytes.isEmpty()) return OperitDetection.NotOperit
        return if (isZip(bytes)) detectZip(fileName, bytes) else detectText(fileName, bytes)
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun detectZip(fileName: String?, bytes: ByteArray): OperitDetection {
        val entries = try {
            readEntries(bytes)
        } catch (e: Rejected) {
            return OperitDetection.Failed(listOf(e.error))
        }
        if (entries.keys.any { it.equals("plugin.json", ignoreCase = true) && !it.contains('/') }) {
            return OperitDetection.NotOperit
        }
        val prefix = findRootPrefix(entries.keys)
        val manifestName = MANIFEST_NAMES.firstOrNull { entries.containsKey(prefix + it) }
        val scripts = entries.entries
            .filter { (name, data) -> isScriptName(name) && data.size <= MAX_SCAN_BYTES }
            .map { (name, data) ->
                val text = String(data, Charsets.UTF_8)
                OperitScript(name, text, OperitMetadataReader.read(text, name.substringAfterLast('/')))
            }
        val hasMetadata = scripts.any { it.metadata != null }
        if (manifestName == null && !hasMetadata) return OperitDetection.NotOperit

        val manifest = manifestName?.let { name ->
            runCatching { OperitHjson.parseObject(String(entries.getValue(prefix + name), Charsets.UTF_8)) }
                .getOrNull()
        }
        val mainRaw = manifest?.get("main") as? String
        val main = mainRaw?.trim()?.trimStart('/')?.let { if (entries.containsKey(prefix + it)) it else null }
            ?: scripts.firstOrNull { it.metadata != null }?.path?.removePrefix(prefix)
            ?: scripts.firstOrNull()?.path?.removePrefix(prefix)
        val files = entries.map { (name, data) ->
            OperitFile(name.removePrefix(prefix), data)
        }
        return OperitDetection.Operit(
            OperitSource(
                format = OperitPackageFormat.TOOL_PACKAGE,
                sourceName = fileName ?: manifest?.get("name") as? String,
                packageId = (manifest?.get("toolpkg_id") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                apiVersion = (manifest?.get("api_version") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                mainFile = main,
                files = files,
                scripts = scripts.map { it.copy(path = it.path.removePrefix(prefix)) },
                manifest = manifest?.let(::readManifestInfo)
            )
        )
    }

    private fun readManifestInfo(manifest: Map<String, Any?>): OperitManifestInfo {
        val name = manifest["display_name"] ?: manifest["displayName"]
        val description = manifest["description"]
        return OperitManifestInfo(
            displayName = operitLocalizedText(name),
            displayNameZh = operitLocalizedText(name, "zh"),
            displayNameEn = operitLocalizedText(name, "en"),
            description = operitLocalizedText(description),
            descriptionZh = operitLocalizedText(description, "zh"),
            descriptionEn = operitLocalizedText(description, "en"),
            version = (manifest["version"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            author = readAuthor(manifest["author"])
        )
    }

    /** 实测社区包 `author` 有字符串与数组两种写法。 */
    private fun readAuthor(raw: Any?): String? = when (raw) {
        is String -> raw.trim().takeIf { it.isNotEmpty() }
        is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.joinToString(", ")
        else -> null
    }

    private fun detectText(fileName: String?, bytes: ByteArray): OperitDetection {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            val map = runCatching { OperitHjson.parseObject(trimmed) }.getOrNull()
            if (map != null) {
                if (map.containsKey("mcpServers")) {
                    return OperitDetection.Failed(
                        listOf(PluginError(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, "mcpServers"))
                    )
                }
                if (map["commands"] is List<*>) return detectSkill(fileName, map)
            }
        }
        if (OperitMetadataReader.findBlock(text) == null) return OperitDetection.NotOperit
        val baseName = "main.js"
        val metadata = OperitMetadataReader.read(text, fileName ?: baseName)
        return OperitDetection.Operit(
            OperitSource(
                format = OperitPackageFormat.SINGLE_SCRIPT,
                sourceName = fileName,
                packageId = null,
                apiVersion = null,
                mainFile = baseName,
                files = listOf(OperitFile(baseName, bytes)),
                scripts = listOf(OperitScript(baseName, text, metadata))
            )
        )
    }

    private fun detectSkill(fileName: String?, map: Map<String, Any?>): OperitDetection {
        val commands = (map["commands"] as? List<*>).orEmpty().mapNotNull { item ->
            val command = item as? Map<*, *> ?: return@mapNotNull null
            val template = listOf("command", "template", "cmd", "shell")
                .mapNotNull { key -> (command[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } }
                .firstOrNull()
                ?: return@mapNotNull null
            OperitSkillCommand(
                name = (command["name"] as? String)?.trim().orEmpty(),
                description = (command["description"] as? String)?.trim().orEmpty(),
                template = template
            )
        }
        if (commands.isEmpty()) return OperitDetection.NotOperit
        return OperitDetection.Operit(
            OperitSource(
                format = OperitPackageFormat.SKILL,
                sourceName = fileName,
                packageId = (map["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                apiVersion = null,
                mainFile = null,
                files = emptyList(),
                scripts = emptyList(),
                skillName = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                skillDescription = (map["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                skillCommands = commands
            )
        )
    }

    private class Rejected(val error: PluginError) : Exception(null, null, false, false)

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (++count > MAX_ENTRIES) {
                        throw Rejected(PluginError(PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES))
                    }
                    if (!PluginManifestParser.isSafeRelativePath(name)) {
                        throw Rejected(PluginError(PluginErrorCode.ARCHIVE_UNSAFE_PATH, name.take(120)))
                    }
                    if (!seen.add(name.lowercase())) {
                        throw Rejected(PluginError(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, name.take(120)))
                    }
                    val data = readBounded(zip, MAX_ENTRY_BYTES, name)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) {
                        throw Rejected(PluginError(PluginErrorCode.ARCHIVE_TOO_LARGE))
                    }
                    result[name] = data
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun readBounded(zip: ZipInputStream, limit: Long, name: String): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var written = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            written += read
            if (written > limit) {
                throw Rejected(PluginError(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE, name.take(120)))
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** 允许整个包多套一层目录（`pkg/manifest.hjson`），后面统一剥掉这个前缀。 */
    private fun findRootPrefix(names: Set<String>): String {
        if (MANIFEST_NAMES.any { names.contains(it) }) return ""
        val nested = names.firstOrNull { name ->
            val slash = name.indexOf('/')
            slash > 0 && name.substringAfterLast('/') in MANIFEST_NAMES
        } ?: return ""
        return nested.substringBeforeLast('/') + "/"
    }

    private val MANIFEST_NAMES = listOf("manifest.hjson", "manifest.json")

    private fun isScriptName(name: String): Boolean =
        name.endsWith(".js", ignoreCase = true) ||
            name.endsWith(".mjs", ignoreCase = true) ||
            name.endsWith(".cjs", ignoreCase = true)
}
