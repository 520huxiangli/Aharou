package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 导入单个技能失败的原因。 */
enum class SkillImportError {
    /** 名称为空、过长或含不能做目录名的字符。 */
    INVALID_NAME,

    /** 同作用域已有同名技能。 */
    NAME_CONFLICT,

    /** 指令正文为空。 */
    EMPTY_CONTENT,

    /** 压缩包里没有找到任何技能（缺少 SKILL.md / CLAUDE.md）。 */
    NO_SKILL_FOUND,

    /** 压缩包无法读取（非合法 zip、条目越界或超出体积上限）。 */
    INVALID_ARCHIVE,

    /** 所选文件类型不支持（如把非 zip 当压缩包导入）。 */
    UNSUPPORTED_FILE,

    /** 写盘失败。 */
    IO_FAILED
}

/** 批量导入中某个技能的失败记录。 */
data class SkillImportFailure(val name: String, val error: SkillImportError)

/**
 * 一次导入的结果：[imported] 为成功写入的技能名；[failures] 为被跳过的技能及原因；
 * [fatal] 非空表示整体失败（一个技能都没导入，如压缩包非法或包内无技能）。
 */
data class SkillImportReport(
    val imported: List<String>,
    val failures: List<SkillImportFailure> = emptyList(),
    val fatal: SkillImportError? = null
)

/** 从压缩包解析出的单个技能：名称 + 该技能目录下的文件（相对技能根，`/` 分隔）。 */
internal data class ArchivedSkill(val name: String, val files: Map<String, ByteArray>)

/**
 * 技能导入的核心逻辑：把一份 Markdown 文本或一个 zip 压缩包写入指定作用域的技能根目录。
 *
 * 只依赖 [FileAccessProvider] + 技能根路径 + 已存在名称集合，本地与远程（SSH）同一套逻辑，便于单测。
 * 名称校验、同名冲突判定与 App 内新建保持一致；冲突不覆盖，直接跳过并记入失败清单。
 */
internal object SkillImporter {
    private const val TAG = "SkillImporter"
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /** 解压总量与条目数上限，防止异常压缩包耗尽内存。 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    /**
     * 从 Markdown 文本导入单个技能：解析 frontmatter 取名称/描述/工具，再归一化为 SKILL.md 落盘。
     * [fallbackName] 用于正文缺 name 时兜底（如源文件名）。
     * [overwrite] 为 true 时同名技能直接覆盖（用于市场更新），否则按冲突跳过。
     */
    fun importMarkdown(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        text: String,
        fallbackName: String,
        overwrite: Boolean = false
    ): SkillImportReport {
        val parsed = SkillParser.parseText(text, fallbackName)
        val name = parsed.name.trim()
        validate(name, existingNames, overwrite)?.let { return SkillImportReport(emptyList(), fatal = it) }
        if (parsed.instructions.isBlank()) {
            return SkillImportReport(emptyList(), fatal = SkillImportError.EMPTY_CONTENT)
        }
        val content = SkillParser.serialize(name, parsed.description, parsed.requiredTools, parsed.instructions)
        return try {
            provider.writeFile("${rootFor(skillsRoot, name)}/$SKILL_FILE", content, overwrite = true)
            SkillImportReport(imported = listOf(name))
        } catch (e: Exception) {
            FileLogger.e(TAG, "导入技能失败: $name", e)
            SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED)
        }
    }

    /** 从 zip 输入流导入技能（一个包内可含多个技能目录）。[overwrite] 同 [importMarkdown]。 */
    fun importArchive(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        input: InputStream,
        fallbackName: String,
        overwrite: Boolean = false
    ): SkillImportReport {
        val skills = when (val read = readArchive(input, fallbackName)) {
            is ArchiveRead.Failed -> return SkillImportReport(emptyList(), fatal = read.error)
            is ArchiveRead.Ok -> read.skills
        }

        val imported = mutableListOf<String>()
        val failures = mutableListOf<SkillImportFailure>()
        // 同批导入里已占用的名称，避免一个包内两个同名技能互相覆盖（与 [overwrite] 无关）。
        val used = mutableSetOf<String>()

        for (skill in skills) {
            val name = skill.name.trim()
            val error = when {
                !SkillRepository.isValidName(name) -> SkillImportError.INVALID_NAME
                name.lowercase() in used -> SkillImportError.NAME_CONFLICT
                !overwrite && name.lowercase() in existingNames -> SkillImportError.NAME_CONFLICT
                skill.files.isEmpty() -> SkillImportError.EMPTY_CONTENT
                else -> null
            }
            if (error != null) {
                failures += SkillImportFailure(name, error)
                continue
            }
            try {
                skill.files.forEach { (relative, bytes) ->
                    provider.writeBytes("${rootFor(skillsRoot, name)}/$relative", bytes, overwrite = true)
                }
                used += name.lowercase()
                imported += name
            } catch (e: Exception) {
                FileLogger.e(TAG, "导入技能失败: $name", e)
                failures += SkillImportFailure(name, SkillImportError.IO_FAILED)
            }
        }
        return SkillImportReport(imported = imported, failures = failures)
    }

    private fun validate(name: String, existingNames: Set<String>, overwrite: Boolean = false): SkillImportError? = when {
        !SkillRepository.isValidName(name) -> SkillImportError.INVALID_NAME
        !overwrite && name.lowercase() in existingNames -> SkillImportError.NAME_CONFLICT
        else -> null
    }

    private fun rootFor(skillsRoot: String, name: String) = "${skillsRoot.trimEnd('/')}/$name"

    private sealed interface ArchiveRead {
        data class Ok(val skills: List<ArchivedSkill>) : ArchiveRead
        data class Failed(val error: SkillImportError) : ArchiveRead
    }

    /**
     * 读取 zip 到内存并按「最深的、是其祖先目录的技能目录」把每个文件归属到一个技能，避免嵌套技能父子重复。
     * 防 zip-slip、限制条目数与总量；非法包或包内无技能返回 [ArchiveRead.Failed]。
     */
    private fun readArchive(input: InputStream, fallbackName: String): ArchiveRead {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (++count > MAX_ENTRIES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                    val path = normalize(entry.name)
                    if (path != null) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                        entries[path] = bytes
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取压缩包失败", e)
            return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
        }
        if (entries.isEmpty()) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)

        // 技能目录：含指令文件的条目所在目录（相对路径，根目录为 ""）。
        val skillDirs = entries.keys
            .filter { isInstructionFile(it.substringAfterLast('/')) }
            .map { it.substringBeforeLast('/', "") }
            .distinct()
        if (skillDirs.isEmpty()) return ArchiveRead.Failed(SkillImportError.NO_SKILL_FOUND)

        // 归属：每个文件归到最深的祖先技能目录（根目录技能兜底匹配全部）。
        val grouped = skillDirs.associateWith { mutableMapOf<String, ByteArray>() }
        entries.forEach { (key, bytes) ->
            val owner = skillDirs
                .filter { it.isEmpty() || key.startsWith("$it/") }
                .maxByOrNull { it.length } ?: return@forEach
            val relative = if (owner.isEmpty()) key else key.substring(owner.length + 1)
            grouped.getValue(owner)[relative] = bytes
        }

        val skills = skillDirs.map { dir ->
            val files = grouped.getValue(dir)
            val instructionText = files.entries
                .firstOrNull { isInstructionFile(it.key.substringAfterLast('/')) }
                ?.value?.toString(Charsets.UTF_8).orEmpty()
            val dirFallback = dir.substringAfterLast('/').ifBlank { fallbackName }
            ArchivedSkill(name = SkillParser.parseText(instructionText, dirFallback).name, files = files)
        }
        return ArchiveRead.Ok(skills)
    }

    /** 归一化 zip 条目路径：反斜杠转正斜杠、去前导 `./`；拒绝绝对路径与含 `..` 的越界条目。 */
    private fun normalize(raw: String): String? {
        val path = raw.replace('\\', '/').trimStart('/').removePrefix("./")
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val cleaned = segments.filter { it.isNotEmpty() && it != "." }
        return if (cleaned.isEmpty()) null else cleaned.joinToString("/")
    }

    private fun isInstructionFile(name: String): Boolean =
        name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
}
