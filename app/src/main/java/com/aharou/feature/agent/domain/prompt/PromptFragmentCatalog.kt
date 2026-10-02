package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 片段来源，优先级从高到低：项目 > 全局 > 本地 > 内置。 */
enum class PromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }

/** 一个编号最终生效的片段。 */
data class PromptFragment(
    val number: Int,
    val title: String,
    val source: PromptFragmentSource,
    val content: String,
    /** 生效层文件；来源为内置时为 null。 */
    val file: File?,
    /** 正文开头的 `<!-- ... -->` 注释，作为列表摘要。 */
    val description: String
) {
    val stableReorderKey: String
        get() = "$title:${content.hashCode()}"

    val body: String
        get() = content.replace(LEADING_COMMENT, "")

    companion object {
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        fun composeContent(description: String, body: String): String =
            if (description.isBlank()) body else "<!-- ${description.trim().replace("-->", "-- >")} -->\n$body"
    }

    /** 生效层是否可写（项目/全局可写，本地/内置只读）。 */
    val editable: Boolean
        get() = source == PromptFragmentSource.PROJECT || source == PromptFragmentSource.GLOBAL
}

/**
 * 提示词片段的四级来源解析：同一编号只生效优先级最高的一层。
 *
 * - 项目：工作区 `.aharou/prompts.custom/`（远程走 [ProjectAharouRoot] 私有目录）
 * - 全局：`<aharouDir>/prompts.custom/`
 * - 本地：`<aharouDir>/prompts/`（启动时从内置释放的副本）
 * - 内置：`assets/prompts/`
 *
 * 编号即身份（文件名前两位数字），决定片段在系统提示词里的顺序。
 */
@Singleton
class PromptFragmentCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val projectAharouRoot: ProjectAharouRoot
) {

    private val globalDir: File get() = File(containerInstaller.aharouDir, CUSTOM_DIR)
    private val localDir: File get() = File(containerInstaller.aharouDir, LOCAL_DIR)

    /** 某工作区对应的项目层目录；无工作区返回 null。 */
    private fun projectDir(projectRoot: String?): File? =
        projectRoot?.takeIf { it.isNotBlank() }
            ?.let { File(projectAharouRoot.forPath(it), CUSTOM_DIR) }

    /** 编辑/新增的写入层：有工作区写项目层，否则写全局层。 */
    fun writableDir(projectRoot: String?): File = projectDir(projectRoot) ?: globalDir

    /** 按编号升序列出所有生效片段。 */
    fun list(projectRoot: String?): List<PromptFragment> {
        val builtin = builtinFiles()
        val project = numberedFragments(projectDir(projectRoot))
        val global = numberedFragments(globalDir)
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtin.keys)
            addAll(project.keys)
            addAll(global.keys)
        }
        val all = numbers.sorted().mapNotNull { resolve(it, builtin, project, global) }
        // 「完全禁用内置提示词」时列表只保留自定义来源，与注入结果一致。
        return if (isBuiltinDisabled()) {
            all.filter {
                it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL
            }
        } else {
            all
        }
    }

    fun fragment(number: Int, projectRoot: String?): PromptFragment? = resolve(
        number,
        builtinFiles(),
        numberedFragments(projectDir(projectRoot)),
        numberedFragments(globalDir)
    )

    /** 静态基线正文：所有生效片段去掉前导注释后按编号拼接。 */
    fun renderStatic(projectRoot: String?): String =
        list(projectRoot)
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 「仅自定义片段」模式：只取项目层与全局层的自定义片段正文。 */
    fun renderCustomOnly(projectRoot: String?): String =
        list(projectRoot)
            .filter { it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL }
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 是否存在「完全禁用内置提示词」标记（全局层）。 */
    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(globalDir)

    /**
     * 保存某编号的覆盖。
     *
     * - [target] 指定写入层（项目/全局）；null 表示写到该编号当前生效的层（只读层则回落可写层）。
     * - [previousNumber] 编辑时若改了编号，传原编号，用于清掉旧编号的覆盖。
     * - 同一编号只保留一份：写入前清掉各可写层里的同编号旧文件。
     */
    fun saveOverride(
        number: Int,
        title: String,
        content: String,
        projectRoot: String?,
        target: PromptFragmentSource? = null,
        previousNumber: Int? = null
    ): Boolean {
        val project = projectDir(projectRoot)
        val dir = when (target) {
            PromptFragmentSource.PROJECT -> project ?: globalDir
            PromptFragmentSource.GLOBAL -> globalDir
            else -> fragment(number, projectRoot)?.takeIf { it.editable }?.file?.parentFile
                ?: writableDir(projectRoot)
        }
        return try {
            if (previousNumber != null && previousNumber != number) {
                deleteOverridesFor(previousNumber, project)
                deleteOverridesFor(previousNumber, globalDir)
            }
            if (!dir.exists()) dir.mkdirs()
            deleteOverridesFor(number, dir)
            if (project != null && project != dir) deleteOverridesFor(number, project)
            if (globalDir != dir) deleteOverridesFor(number, globalDir)
            File(dir, "%02d-%s.md".format(number, sanitizeTitle(title))).writeText(content)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "写提示词覆盖失败: $number", e)
            false
        }
    }

    /**
     * 按拖拽后的顺序重新编号并落盘：编号集合保持不变，只把各片段内容依次映射到这些编号，
     * 从而改变注入顺序（数字越小越靠前）。写入可写层。
     */
    fun reorder(reordered: List<PromptFragment>, projectRoot: String?): Boolean {
        if (reordered.isEmpty()) return false
        val project = projectDir(projectRoot)
        val dir = writableDir(projectRoot)
        val numbers = reordered.map { it.number }.sorted()
        return try {
            if (!dir.exists()) dir.mkdirs()
            numbers.forEach { number ->
                deleteOverridesFor(number, dir)
                if (project != null && project != dir) deleteOverridesFor(number, project)
                if (globalDir != dir) deleteOverridesFor(number, globalDir)
            }
            reordered.forEachIndexed { index, fragment ->
                File(dir, "%02d-%s.md".format(numbers[index], sanitizeTitle(fragment.title)))
                    .writeText(fragment.content)
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "重排提示词失败", e)
            false
        }
    }

    /** 删除某编号的覆盖（仅当生效层可写），删后自动回退到下一层。 */
    fun deleteOverride(number: Int, projectRoot: String?): Boolean {
        val file = fragment(number, projectRoot)?.takeIf { it.editable }?.file ?: return false
        return file.delete()
    }

    /** 切换「完全禁用内置提示词」标记（全局层）。 */
    fun setBuiltinDisabled(disabled: Boolean): Boolean {
        return try {
            val marker = File(globalDir, PromptFragmentResolver.DISABLE_BUILTIN_FILE)
            if (disabled) {
                if (!globalDir.exists()) globalDir.mkdirs()
                marker.writeText("")
            } else if (marker.isFile) {
                marker.delete()
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换禁用内置提示词失败", e)
            false
        }
    }

    private fun resolve(
        number: Int,
        builtin: Map<Int, String>,
        project: Map<Int, File>,
        global: Map<Int, File>
    ): PromptFragment? {
        project[number]?.let { return readFragment(it, number, PromptFragmentSource.PROJECT) }
        global[number]?.let { return readFragment(it, number, PromptFragmentSource.GLOBAL) }
        builtin[number]?.let { name ->
            File(localDir, name).takeIf { it.isFile }?.let {
                return readFragment(it, number, PromptFragmentSource.LOCAL)
            }
            readAsset(name)?.let { content ->
                return PromptFragment(
                    number,
                    titleOf(name),
                    PromptFragmentSource.BUILTIN,
                    content,
                    null,
                    extractDescription(content)
                )
            }
        }
        return null
    }

    private fun readFragment(file: File, number: Int, source: PromptFragmentSource): PromptFragment? =
        readText(file)?.let { content ->
            PromptFragment(number, titleOf(file.name), source, content, file, extractDescription(content))
        }

    /** 目录顶层 `<两位数字>-<名称>.md`，按编号去重（同编号取字典序首个）。 */
    private fun numberedFragments(dir: File?): Map<Int, File> {
        val files = dir?.listFiles() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            PromptFragmentResolver.parseNumber(file.name)?.let { byNumber.putIfAbsent(it, file) }
        }
        return byNumber
    }

    /** `assets/prompts` 顶层带编号的 md：编号 → 文件名。 */
    private fun builtinFiles(): Map<Int, String> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, String>()
        names.filter { it.endsWith(".md") }.sorted().forEach { name ->
            PromptFragmentResolver.parseNumber(name)?.let { byNumber.putIfAbsent(it, name) }
        }
        return byNumber
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun deleteOverridesFor(number: Int, dir: File?): Boolean {
        val files = dir?.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        } ?: return false
        var deleted = false
        files.forEach { deleted = it.delete() || deleted }
        return deleted
    }

    private fun titleOf(fileName: String): String {
        val base = fileName.removeSuffix(".md")
        return base.substringAfter('-', missingDelimiterValue = base)
    }

    /** 正文开头的 `<!-- ... -->` 注释即摘要（内置片段都带一行）。 */
    private fun extractDescription(content: String): String =
        DESCRIPTION.find(content)?.groupValues?.get(1)?.trim().orEmpty()

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    private companion object {
        const val TAG = "PromptFragmentCatalog"
        const val ASSET_DIR = "prompts"
        const val CUSTOM_DIR = "prompts.custom"
        const val LOCAL_DIR = "prompts"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        val DESCRIPTION = Regex("^\\s*<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
    }
}
