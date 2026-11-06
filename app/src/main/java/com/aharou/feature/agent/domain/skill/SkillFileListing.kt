package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider

/**
 * 一个技能的附属文件清单，路径相对技能目录（如 `scripts/foo.py`），不给绝对路径。
 *
 * @param root 技能目录的容器路径；内置技能随 App 打包、没有磁盘目录时为 null
 * @param truncated 文件数超过上限、清单已被截断
 */
data class SkillFileListing(
    val root: String?,
    val files: List<String> = emptyList(),
    val truncated: Boolean = false
)

/**
 * 按目录列出技能附属文件，供 `LoadSkillTool` 随正文一起返回，让模型不必再自己 `list` 探目录。
 *
 * 只走 [FileAccessProvider]：远程 SSH 工作区与本地目录同一套逻辑。
 */
object SkillFileLister {
    private const val TAG = "SkillFileLister"

    /**
     * 只放行这几个约定俗成的附属目录：技能正文引用的脚本、参考资料、素材基本都在这三类里。
     * 其余子目录一律不下钻——`node_modules`、`.git`、构建产物这类目录一次就能灌进上千条路径，
     * 既挤爆上下文，对「照技能做事」又没有价值。
     */
    private val ALLOWED_DIRS = setOf("references", "scripts", "assets")

    /** 清单上限：够看清技能带了哪些附件，又不至于让 `loadSkill` 的结果本身变成负担。 */
    private const val MAX_FILES = 200

    /** 白名单目录内部允许的嵌套层数。`scripts/lib/xxx` 属正常用法，但总得有上限。 */
    private const val MAX_NESTED_DEPTH = 4

    fun list(provider: FileAccessProvider, dirPath: String): SkillFileListing {
        val root = dirPath.trimEnd('/')
        // 多收一条用来判断「还有没有更多」：正好 200 条时无法从条数区分是否还有剩余。
        val collected = ArrayList<String>(MAX_FILES + 1)
        collect(provider, root, "", 0, collected)
        val truncated = collected.size > MAX_FILES
        return SkillFileListing(
            root = root,
            files = if (truncated) collected.take(MAX_FILES) else collected,
            truncated = truncated
        )
    }

    /** [relative] 是 [path] 相对技能根的位置（根目录传空串），只用于拼相对路径。 */
    private fun collect(
        provider: FileAccessProvider,
        path: String,
        relative: String,
        depth: Int,
        out: MutableList<String>
    ) {
        // 列目录失败不向上抛：附属清单纯属锦上添花，正文才是模型干活所需的东西。
        val entries = runCatching { provider.listFiles(path) }.getOrElse { e ->
            FileLogger.w(TAG, "列出技能目录失败: $path", e)
            return
        }
        for (entry in entries.sortedBy { it.name.lowercase() }) {
            if (out.size > MAX_FILES) return
            val childRelative = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            if (!entry.isDirectory) {
                out.add(childRelative)
                continue
            }
            // 技能根层（depth 0）只看白名单目录；进了白名单目录之后，其下子目录照常递归——
            // 但隐藏目录（.git / .cache 之类）跳过，它们同样只贡献噪音。
            val allowed = if (depth == 0) entry.name in ALLOWED_DIRS else !entry.name.startsWith(".")
            if (allowed && depth < MAX_NESTED_DEPTH) {
                collect(provider, "$path/${entry.name}", childRelative, depth + 1, out)
            }
        }
    }
}
