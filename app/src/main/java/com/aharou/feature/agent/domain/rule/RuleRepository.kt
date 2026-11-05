package com.aharou.feature.agent.domain.rule

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.prompt.PromptFragmentResolver
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
    data class RuleEntry(
        val name: String,
        val description: String,
        val content: String,
        /** 关掉的规则不进注入清单，`loadRule` 也取不到正文；设置页里仍可见并重新打开。 */
        val enabled: Boolean = true,
        /** 同名覆盖了某个内置规则；只有用户文件与内置同名时才算。 */
        val overridden: Boolean = false,
        /** 只存在于 `~/.aharou/rules`、内置没有同名，用户可以整个删掉。 */
        val userDefined: Boolean = false
    )

    private val globalDir: File get() = File(containerInstaller.aharouDir, RULES_DIR)

    /** 注入清单用：按名称排序的已启用规则块。 */
    fun listRules(): List<RuleEntry> = listAllRules().filter { it.enabled }

    /** 设置页用：全部规则块（含被关掉的）。 */
    fun listAllRules(): List<RuleEntry> = merged().values.sortedBy { it.name.lowercase() }

    /** 读取规则块正文；不存在或已被关掉返回 null。 */
    fun loadText(name: String): String? {
        val entry = merged()[name.trim().lowercase()] ?: return null
        return entry.content.takeIf { entry.enabled }
    }

    /** 单条规则的启用/关闭；标记落在 `~/.aharou/rules/.disabled/`。 */
    fun setEnabled(name: String, enabled: Boolean): Boolean =
        PromptFragmentResolver.setNamedDisabled(globalDir, name, !enabled)

    /** 把正文写成同名覆盖文件 `~/.aharou/rules/<名称>.md`。 */
    fun saveOverride(name: String, content: String): Boolean = try {
        val file = File(globalDir, "$name$EXT")
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    } catch (e: Exception) {
        FileLogger.e(TAG, "写规则覆盖失败: $name", e)
        false
    }

    /**
     * 新建一条用户自己的按需规则。名字只允许小写字母、数字、下划线与连字符：
     * 它同时是文件名与 `loadRule` 的调用名，带路径分隔符会越出目录。
     * 与内置同名时按「覆盖」处理，不允许用这种方式冒出一份同名二义。
     */
    fun createRule(name: String, content: String): Boolean {
        val safe = name.trim().lowercase()
        if (!NAME_REGEX.matches(safe)) return false
        return saveOverride(safe, content)
    }

    /** 删掉用户侧的规则文件：用户新增的整体消失，覆盖内置的则恢复内置版本。 */
    fun deleteUserRule(name: String): Boolean = runCatching {
        File(globalDir, "$name$EXT").delete()
    }.onFailure { FileLogger.e(TAG, "删规则失败: $name", it) }.getOrDefault(false)

    private fun merged(): Map<String, RuleEntry> {
        val byName = LinkedHashMap<String, RuleEntry>()
        val builtin = builtinRules()
        builtin.forEach { byName[it.name.lowercase()] = it }
        val builtinNames = builtin.map { it.name.lowercase() }.toSet()
        globalRules().forEach {
            val key = it.name.lowercase()
            byName[key] = it.copy(overridden = key in builtinNames, userDefined = key !in builtinNames)
        }
        val disabled = PromptFragmentResolver.disabledFileNames(globalDir).map { it.lowercase() }.toSet()
        return byName.mapValues { (_, entry) ->
            // 标记文件名不带扩展名（见 setNamedDisabled），这里再拼 .md 就永远比不上了。
            entry.copy(enabled = entry.name.lowercase() !in disabled)
        }
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

        /** 规则名同时是文件名与 `loadRule` 的调用名，不能带路径分隔符与空白。 */
        val NAME_REGEX = Regex("[a-z0-9_-]{1,64}")
    }
}
