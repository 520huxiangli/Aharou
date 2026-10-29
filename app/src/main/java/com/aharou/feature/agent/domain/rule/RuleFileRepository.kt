package com.aharou.feature.agent.domain.rule

import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则文件（AGENTS.md）的读写。
 *
 * 解析口径必须与注入侧（SystemPromptProvider 的 ProjectRuleSource）一致：全局取 `~/.aharou`、
 * 项目取工作区根目录，各自独立判断 `AGENTS.md` → `CLAUDE.md` → `rules.md`，再加上同目录
 * `rules.d` 目录里的 .md 片段。两边分开写的话，用户在设置页看到的和实际注入给模型的会是两份不同的内容。
 *
 * 页面只编辑主规则文件；片段目录是给习惯拆文件的人准备的，这里只读名字用于展示。
 */
@Singleton
class RuleFileRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {

    /**
     * [fileName] 是当前生效那一份的文件名（可能是回退的 CLAUDE.md / rules.md），[exists] 为 false 时内容为空；
     * [fragments] 是 `rules.d/` 下的片段文件名（按注入顺序）；
     * [displayPath] 是给人看的简写路径（`~/.aharou/...` / `~/workspace/...`），与注入侧的标注一致。
     */
    data class RuleDocument(
        val path: String,
        val fileName: String,
        val content: String,
        val exists: Boolean,
        val fragments: List<String> = emptyList(),
        val displayPath: String = path
    )

    fun readGlobal(): RuleDocument = read(containerInstaller.aharouDir, GLOBAL_LABEL)

    fun readProject(workspacePath: String): RuleDocument? =
        workspacePath.takeIf { it.isNotBlank() }?.let { read(File(it), PROJECT_LABEL) }

    fun saveGlobal(content: String): RuleDocument = save(containerInstaller.aharouDir, GLOBAL_LABEL, content)

    /** 调用方需保证 [workspacePath] 非空（界面侧没有工作区时不让编辑）。 */
    fun saveProject(workspacePath: String, content: String): RuleDocument =
        save(File(workspacePath), PROJECT_LABEL, content)

    /** 全局规则目录（`~/.aharou`），片段目录 `rules.d` 就在它下面。 */
    fun globalDir(): File = containerInstaller.aharouDir

    fun projectDir(workspacePath: String): File? =
        workspacePath.takeIf { it.isNotBlank() }?.let { File(it) }

    fun readFragment(dir: File, name: String): String? =
        File(File(dir, RULES_DIR), name).takeIf { it.isFile }?.let(::readText)

    fun saveFragment(dir: File, name: String, content: String) {
        val target = File(File(dir, RULES_DIR), name)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    fun deleteFragment(dir: File, name: String): Boolean =
        File(File(dir, RULES_DIR), name).delete()

    /** 删掉当前生效的主规则文件（AGENTS.md / CLAUDE.md / rules.md 中实际在用的那份）。 */
    fun deleteRuleFile(dir: File): Boolean = activeFile(dir)?.delete() ?: false

    /** 片段文件名白名单：只允许字母、数字、下划线、连字符，以 `.md` 结尾，顺带挡住路径穿越。 */
    fun isValidFragmentName(name: String): Boolean = FRAGMENT_NAME_REGEX.matches(name)

    /** 写回当前生效的那一份；目录里一份都没有时新建 AGENTS.md（它的优先级最高）。 */
    private fun save(dir: File, label: String, content: String): RuleDocument {
        val target = activeFile(dir) ?: File(dir, AGENTS_FILE)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return RuleDocument(
            path = target.absolutePath,
            fileName = target.name,
            content = content,
            exists = true,
            fragments = fragmentNames(dir),
            displayPath = "$label/${target.name}"
        )
    }

    private fun read(dir: File, label: String): RuleDocument {
        val active = activeFile(dir)
        val target = active ?: File(dir, AGENTS_FILE)
        return RuleDocument(
            path = target.absolutePath,
            fileName = target.name,
            content = active?.let(::readText).orEmpty(),
            exists = active != null,
            fragments = fragmentNames(dir),
            displayPath = "$label/${target.name}"
        )
    }

    private fun activeFile(dir: File): File? =
        listOf(AGENTS_FILE, CLAUDE_FILE, RULES_FILE)
            .map { File(dir, it) }
            .firstOrNull { it.isFile }

    private fun fragmentNames(dir: File): List<String> =
        File(dir, RULES_DIR)
            .listFiles { file -> file.isFile && file.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.map { it.name }
            .orEmpty()

    private fun readText(file: File): String? = try {
        file.readText()
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val RULES_FILE = "rules.md"
        const val RULES_DIR = "rules.d"
        val FRAGMENT_NAME_REGEX = Regex("[A-Za-z0-9_-]+\\.md")

        /** 与注入侧标注的目录写法保持一致，避开展开成三行的绝对路径。 */
        const val GLOBAL_LABEL = "~/.aharou"
        const val PROJECT_LABEL = "~/workspace"
    }
}
