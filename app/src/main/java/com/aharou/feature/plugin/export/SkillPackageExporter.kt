package com.aharou.feature.plugin.export

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.skill.Skill
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把一个已装技能打成一个可分享的 zip。
 *
 * 必须整目录打包：技能正文常按 `~/.aharou/skills/<目录>/run.py` 这种相对位置引用同目录脚本，
 * 只导 `SKILL.md` 会给对方一个跑不起来的空壳。
 *
 * 与 `agent/domain/skill/SkillExport.kt` 里那个同用途的对象分开：那个只负责把字节打出来、
 * 不跳构建目录也不写说明，这里要产出「别人拿到就能装上」的包。同名放同模块会被读的人混淆，
 * 故叫 `Package`。
 */
@Singleton
class SkillPackageExporter @Inject constructor(
    private val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val context: Context,
) {

    /**
     * @param source 来源展示（市场仓库名 / 本机导入），写进包内 README；空串表示来源不明。
     * @return 落盘后的产物；没有磁盘目录、读不到或超限时返回 null（调用方提示失败即可，不阻断别的操作）。
     */
    fun export(skill: Skill, source: String): File? {
        // 没有磁盘目录的技能（随 App 打包、只是引用仓库）用户拿不到可分享的本地内容，调用方本就不该给入口
        val dir = skill.dirPath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val stamp = ExportFiles.timestamp()
        val root = dir.substringAfterLast('/').takeIf { it.isNotBlank() } ?: ExportFiles.safeName(skill.name)
        val target = ExportFiles.newFile(
            context,
            "skill-${ExportFiles.safeName(skill.name)}",
            "zip",
            stamp
        )
        val readme = readme(skill, source, root, stamp).toByteArray(Charsets.UTF_8)
        return if (SkillZip.write(fileAccess, dir, root, target, mapOf(README_NAME to readme))) target else null
    }

    /** 包内说明：对方没看过 Aharou 也该知道这是什么、往哪放。 */
    private fun readme(skill: Skill, source: String, root: String, stamp: String): String = buildString {
        appendLine("# ${skill.name}")
        appendLine()
        if (skill.description.isNotBlank()) {
            appendLine(skill.description)
            appendLine()
        }
        appendLine("- 来源：${source.ifBlank { "本机" }}")
        appendLine("- 导出时间：$stamp")
        appendLine("- 导出工具：Aharou")
        appendLine()
        appendLine("## 怎么导入")
        appendLine()
        appendLine("1. 解压压缩包，得到 `$root/` 目录；")
        appendLine("2. 整个目录放进 `~/.aharou/skills/`（所有项目都能用），或放进 `<工作区>/.aharou/skills/`（只在那个项目生效）；")
        appendLine("3. `SKILL.md` 要留在目录顶层，同目录的脚本等文件按原相对路径一起放——技能正文就是按这个目录写的；")
        appendLine("4. 打开 Aharou 的「设置 → 技能」，它就在列表里了。")
        appendLine()
        appendLine("> 打包时已跳过 .git、node_modules、.venv、__pycache__；这些目录若在运行期要用，需要在对方机器上重新准备。")
    }

    private companion object {
        const val README_NAME = "README.md"
    }
}

/**
 * 按目录树打 zip 的那部分，单拎出来是为了能离线核对包结构。
 *
 * 只依赖「列目录 + 读字节」两件事，所以既走本地宿主文件也走远程 SFTP，导出逻辑不用分两套。
 */
internal object SkillZip {

    private class Budget {
        var entries = 0
        var bytes = 0L
    }

    private class PackageTooLarge(message: String) : Exception(message)

    /**
     * @param extraEntries 额外追加在技能根目录下的文件（相对路径 → 内容）。
     * @return 打包成功与否；失败时会把半截包删掉——留着只会让人以为它就是能用的东西。
     */
    fun write(
        provider: FileAccessProvider,
        dir: String,
        root: String,
        target: File,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): Boolean = try {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val budget = Budget()
            addDirectory(provider, dir, "", root, zip, budget, depth = 0)
            extraEntries.forEach { (path, bytes) -> writeEntry(zip, budget, "$root/$path", bytes) }
        }
        true
    } catch (e: Exception) {
        FileLogger.e(TAG, "打包技能失败：$dir", e)
        target.delete()
        false
    }

    private fun addDirectory(
        provider: FileAccessProvider,
        dir: String,
        relative: String,
        root: String,
        zip: ZipOutputStream,
        budget: Budget,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = runCatching { provider.listFiles(dir) }.getOrElse { e ->
            // 单个子目录读不到就跳过：一个坏目录不该毁掉整次导出，用户要的是能用的那一份
            FileLogger.w(TAG, "列出目录失败：$dir（${e.message}）")
            return
        }
        for (entry in entries.sortedBy { it.name }) {
            if (entry.name in SKIPPED_DIRS) continue
            val childPath = "$dir/${entry.name}"
            val childRelative = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            if (entry.isDirectory) {
                addDirectory(provider, childPath, childRelative, root, zip, budget, depth + 1)
            } else {
                // 单个文件读不到同样跳过，理由同上
                val bytes = runCatching { provider.readBytes(childPath) }.getOrNull() ?: continue
                writeEntry(zip, budget, "$root/$childRelative", bytes)
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, budget: Budget, path: String, bytes: ByteArray) {
        budget.entries++
        budget.bytes += bytes.size
        if (budget.entries > MAX_ENTRIES) throw PackageTooLarge("文件数超过 $MAX_ENTRIES")
        if (budget.bytes > MAX_TOTAL_BYTES) throw PackageTooLarge("总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private const val TAG = "SkillZip"

    /** 与插件打包（安装器）同一口径，避免出现「插件能装、技能包却没人收」的怪尺寸。 */
    private const val MAX_ENTRIES = 2_000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val SKIPPED_DIRS = setOf(".git", "node_modules", ".venv", "__pycache__")

    /** 目录嵌套上限：只为挡住符号链接造成的死循环，正常技能用不到这么深。 */
    private const val MAX_DEPTH = 16
}
