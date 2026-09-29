package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.LocalFileAccess
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 仓库，聚合各 [SkillSource]（全局目录 + 项目目录）提供的技能，
 * 并按 [SkillConfigRepository] 的禁用名单过滤注入清单。
 *
 * 全局技能固定在 App 私有目录（始终本地），项目级技能随工作区（本地宿主目录或远程 SSH 工作区），
 * 读写都经 [FileAccessProvider] 以容器路径完成。
 */
@Singleton
class SkillRepository @Inject constructor(
    private val builtinSkillSource: BuiltinSkillSource,
    private val localDirectorySkillSource: LocalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val localFileAccess: LocalFileAccess,
    private val fileAccess: FileAccessProvider
) {
    /** 全部技能（含来源作用域），未过滤禁用；同名按 内置 < 全局 < 项目 逐级覆盖。 */
    fun listAllSkills(): List<SkillEntry> = mergeAll(
        builtinSkillSource.listSkills(),
        localDirectorySkillSource.listSkills(),
        projectDirectorySkillSource.listSkills()
    )

    /** 启用的技能列表（注入系统提示词用），禁用技能被过滤。 */
    fun listSkills(): List<Skill> =
        filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }

    /** 读取指定 skill 的完整指令正文；不存在 / 解析失败 / 已被禁用时返回 null。 */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        return localDirectorySkillSource.loadInstructions(name)
            ?: projectDirectorySkillSource.loadInstructions(name)
            ?: builtinSkillSource.loadInstructions(name)
    }

    /** 技能是否在任一作用域中被禁用。 */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** 在指定作用域启用/禁用某个技能。内置技能不可停用，直接忽略。 */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        if (scope == SkillScope.BUILTIN) return
        skillConfigRepository.setDisabled(name, disabled, scope)
    }

    /**
     * 写入技能文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；返回 null 表示成功。
     *
     * 编辑时写回原目录里的原指令文件（可能叫 CLAUDE.md），改名只改 frontmatter 的 name、不动目录名——
     * 技能正文常按 `~/.aharou/skills/<目录>/run.py` 引用同目录脚本，跟着改名会把这些引用打断。
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        if (scope == SkillScope.BUILTIN) return SkillSaveError.READ_ONLY
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = listAllSkills()
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.scope == scope && it.skill.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull {
                it.scope == scope && it.skill.name.equals(old, ignoreCase = true)
            }?.skill?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions
        )

        val provider = providerFor(scope)
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存技能失败: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** 指定作用域的技能根目录（容器路径）。内置技能随 App 打包，没有可写目录。 */
    fun skillsRoot(scope: SkillScope): String = when (scope) {
        SkillScope.GLOBAL -> localDirectorySkillSource.skillsRoot
        SkillScope.PROJECT -> projectDirectorySkillSource.skillsRoot
        SkillScope.BUILTIN -> error("内置技能随 App 打包，没有可写目录")
    }

    /**
     * 从 Markdown 文本导入技能到指定作用域；[fallbackName] 为 frontmatter 缺 name 时的兜底名。
     * 名称非法 / 同名冲突 / 正文为空时整体失败，不落盘；[overwrite] 为 true 时同名直接覆盖。
     */
    fun importMarkdown(
        text: String,
        fallbackName: String,
        scope: SkillScope,
        overwrite: Boolean = false
    ): SkillImportReport =
        SkillImporter.importMarkdown(
            providerFor(scope), skillsRoot(scope), existingNamesIn(scope), text, fallbackName, overwrite
        )

    /** 从 zip 输入流导入技能（可含多个技能目录）到指定作用域。[overwrite] 同 [importMarkdown]。 */
    fun importZip(
        input: InputStream,
        fallbackName: String,
        scope: SkillScope,
        overwrite: Boolean = false
    ): SkillImportReport =
        SkillImporter.importArchive(
            providerFor(scope), skillsRoot(scope), existingNamesIn(scope), input, fallbackName, overwrite
        )

    /** 指定作用域下已有技能名（小写），供导入查重。 */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        listAllSkills().filter { it.scope == scope }.map { it.skill.name.lowercase() }.toSet()

    /** 删除指定作用域的技能（删除其目录，不可恢复）。返回是否成功；内置技能不可删。 */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        if (scope == SkillScope.BUILTIN) return false
        val entry = listAllSkills().firstOrNull {
            it.skill.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val dirPath = entry.skill.dirPath ?: return false
        return safeDeleteSkillDir(providerFor(scope), dirPath)
    }

    /** 全局技能固定在本地私有目录，项目级技能跟随工作区（可能是远程）；内置技能只读。 */
    private fun providerFor(scope: SkillScope): FileAccessProvider = when (scope) {
        SkillScope.GLOBAL -> localFileAccess
        SkillScope.PROJECT -> fileAccess
        SkillScope.BUILTIN -> error("内置技能只读，不接受写入")
    }

    companion object {
        private const val TAG = "SkillRepository"

        /** 新建技能时写入的指令文件名。 */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** 名称同时用作目录名，禁掉路径分隔符与保留字符；长度上限防止极端目录名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 目录里已有的指令文件完整路径：SKILL.md 优先，其次 CLAUDE.md（与 [SkillParser.parse] 同一套回退）。 */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** 合并三级来源：同名逐级覆盖（内置 < 全局 < 项目），按名称排序。 */
        internal fun mergeAll(
            builtin: List<Skill>,
            global: List<Skill>,
            project: List<Skill>
        ): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            builtin.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.BUILTIN) }
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** 过滤禁用技能（禁用名单已归一化为小写）。 */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** 仅当目录存在且含 SKILL.md/CLAUDE.md 指令文件时才删除，避免误删非技能目录。 */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
