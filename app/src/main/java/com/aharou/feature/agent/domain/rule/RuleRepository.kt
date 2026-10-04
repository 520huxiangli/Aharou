package com.aharou.feature.agent.domain.rule

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按需规则块仓库：低频、专门的规则不进系统提示词的常驻部分，只注入「名称 + 摘要」清单，
 * AI 判断适用时用 `loadRule` 取正文（与技能的 `loadSkill` 同一套路）。
 *
 * 两级来源，同名覆盖：内置 `assets/rules` 目录下的 md < 全局 `~/.aharou/rules` 目录下的同名文件。
 * 文件首行的 `<!-- ... -->` 注释即清单摘要，与提示词片段的约定一致。
 */
@Singleton
class RuleRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    data class RuleEntry(val name: String, val description: String, val content: String)

    private val globalDir: File get() = File(containerInstaller.aharouDir, RULES_DIR)

    /** 注入清单用：按名称排序的全部规则块。 */
    fun listRules(): List<RuleEntry> = merged().values.sortedBy { it.name.lowercase() }

    /** 读取规则块正文；不存在返回 null。 */
    fun loadText(name: String): String? = merged()[name.trim().lowercase()]?.content

    private fun merged(): Map<String, RuleEntry> {
        val byName = LinkedHashMap<String, RuleEntry>()
        builtinRules().forEach { byName[it.name.lowercase()] = it }
        globalRules().forEach { byName[it.name.lowercase()] = it }
        return byName
    }

    private fun builtinRules(): List<RuleEntry> = runCatching {
        context.assets.list(RULES_DIR)?.toList().orEmpty()
            .filter { it.endsWith(EXT) }
            .sorted()
            .mapNotNull { fileName ->
                context.assets.open("$RULES_DIR/$fileName").bufferedReader().use { it.readText() }
                    .let { parse(fileName, it) }
            }
    }.onFailure { FileLogger.w(TAG, "读取内置规则块失败: ${it.message}", it) }
        .getOrDefault(emptyList())

    private fun globalRules(): List<RuleEntry> {
        val files = globalDir.listFiles { file -> file.isFile && file.name.endsWith(EXT) } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            runCatching { file.readText() }.getOrNull()?.let { parse(file.name, it) }
        }
    }

    private fun parse(fileName: String, text: String): RuleEntry {
        val name = fileName.removeSuffix(EXT)
        val description = DESCRIPTION.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val body = LEADING_COMMENT.replaceFirst(text, "").trim()
        return RuleEntry(name, description, body)
    }

    private companion object {
        const val TAG = "RuleRepository"
        const val RULES_DIR = "rules"
        const val EXT = ".md"
        val DESCRIPTION = Regex("(?s)^\\s*<!--\\s*(.*?)\\s*-->")
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
