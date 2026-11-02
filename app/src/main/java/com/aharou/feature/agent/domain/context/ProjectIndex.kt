package com.aharou.feature.agent.domain.context

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目上下文分层索引：给模型一份「哪个模块在哪、细节去哪读」的目录，省掉每轮 `ls` 与盲搜。
 *
 * 只在工作区确是索引所描述的仓库时才注入——把某一份模块表塞进别的仓库只会误导模型，
 * 所以识别靠「特征文件组合」而不是「是不是 Gradle 工程」。
 *
 * 索引正文是 `prompts/agent/project-index.md`（静态资产，走 [PromptFileResolver] 以便被
 * `prompts.custom/` 同名覆盖），这里只负责识别、读取与超长降级。
 */
@Singleton
class ProjectIndex @Inject constructor(
    private val promptFileResolver: PromptFileResolver,
) {
    fun build(projectRoot: String): String? {
        if (projectRoot.isBlank()) return null
        if (!isIndexedRepo(File(projectRoot))) return null
        val body = runCatching { promptFileResolver.resolve(INDEX_FILE) }
            .onFailure { FileLogger.w(TAG, "读取项目上下文索引失败: ${it.message}", it) }
            .getOrNull()
            ?.replace(LEADING_COMMENT, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (body.length > MAX_CHARS) degrade(body) else body
    }

    /** 单看 `settings.gradle.kts` 会命中所有 Android 工程，必须再叠一个本仓库独有的包目录。 */
    private fun isIndexedRepo(dir: File): Boolean =
        File(dir, "settings.gradle.kts").isFile && File(dir, REPO_MARKER).isDirectory

    /**
     * 超长降级：条目行按「- 模块路径 — 覆盖范围 — 索引路径」压成「- 模块路径 → 索引路径」，
     * 先丢描述、保住路径列表与说明文字；压完仍超长才硬截断，避免把提示词挤爆。
     */
    private fun degrade(body: String): String {
        val compact = body.lines().map { line ->
            val parts = line.split(SEPARATOR)
            if (line.startsWith("- ") && parts.size >= 3) {
                "- ${parts.first().removePrefix("- ").trim()} → ${parts.last().trim()}"
            } else {
                line
            }
        }.joinToString("\n")
        return if (compact.length > MAX_CHARS) {
            compact.take(MAX_CHARS) + "\n…（项目索引过长，已截断）"
        } else {
            compact
        }
    }

    private companion object {
        const val TAG = "ProjectIndex"
        const val INDEX_FILE = "agent/project-index.md"

        /** 本仓库独有路径：与 `settings.gradle.kts` 一起出现才认定是索引描述的那个仓库。 */
        const val REPO_MARKER = "app/src/main/java/com/aharou/feature"

        /** 索引只做目录层，10KB 是天花板（实际正文约 5KB），超了就降级只留路径。 */
        const val MAX_CHARS = 10 * 1024

        const val SEPARATOR = " — "
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
