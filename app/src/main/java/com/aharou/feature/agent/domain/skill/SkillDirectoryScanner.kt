package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。
 */
object SkillDirectoryScanner {
    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"
    private const val TAG = "SkillDirectoryScanner"

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表。
     *
     * 扫描失败（远程 SSH 断连时 listFiles 抛 IOException）降级为“没有技能”，
     * 不让异常冒泡到 viewModelScope——那会变成未捕获异常、直接弹全局崩溃页。
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> {
        val files = runCatching { provider.listFilesRecursive(root, MAX_DEPTH) }
            .getOrElse { e ->
                FileLogger.w(TAG, "扫描技能目录失败（$root）：${e.message}")
                return emptyList()
            }
        val dirs = files
            .filter { relative ->
                val name = relative.substringAfterLast('/')
                name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
            }
            .map { it.substringBeforeLast('/', "") }
            .distinct()

        val base = root.trimEnd('/')
        return dirs.mapNotNull { relative ->
            val dirPath = if (relative.isEmpty()) base else "$base/$relative"
            SkillParser.parse(provider, dirPath)
        }.sortedBy { it.name.lowercase() }
    }
}
