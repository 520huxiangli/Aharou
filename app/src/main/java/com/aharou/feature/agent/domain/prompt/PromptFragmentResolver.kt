package com.aharou.feature.agent.domain.prompt

import java.io.File

/**
 * `prompts.custom/` 的片段解析规则（纯文件系统逻辑，便于单测）。
 *
 * 目录里分两类片段：
 * - 顶层 `<两位数字>-<名称>.md`：数字即身份。数字命中内置静态片段即为覆盖，否则作为新增片段并入静态基线按数字排序。
 * - 其它文件（含 `agent/` 子目录）：按精确同名覆盖，无数字身份。
 *
 * 另外两个隐藏标记：`.no-builtin`（整体关掉内置片段，仅主代理生效）与
 * `.disabled/`（按编号或按名称关掉单个片段，内置片段同样适用）。
 */
internal object PromptFragmentResolver {

    /** 存在该文件即完全禁用内置提示词（仅主代理生效）。 */
    const val DISABLE_BUILTIN_FILE = ".no-builtin"

    /** 单片段关闭标记放在该子目录：目录里每个文件代表一个被关闭的编号。 */
    const val DISABLED_DIR = ".disabled"

    private val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")

    /** 顶层数字片段的数字值；非两位数字前缀返回 null。 */
    fun parseNumber(fileName: String): Int? =
        NUMBERED.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

    /**
     * 目录顶层 `<两位数字>-<名称>.md` 文件，按数字升序。
     * 同一数字有多个文件时只保留字典序首个，避免排序不稳定。
     */
    fun numberedFragments(dir: File): List<Pair<Int, File>> {
        val files = dir.listFiles() ?: return emptyList()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val number = parseNumber(file.name) ?: return@forEach
                byNumber.putIfAbsent(number, file)
            }
        return byNumber.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** `.no-builtin` 是否存在。 */
    fun isBuiltinDisabled(dir: File): Boolean = File(dir, DISABLE_BUILTIN_FILE).isFile

    /**
     * `.disabled/` 里登记的被关闭编号。文件名就是编号（两位数字，如 `60`），
     * 其余文件忽略——手工塞进去的 README 之类不该影响注入。
     */
    fun disabledNumbers(dir: File): Set<Int> {
        val files = File(dir, DISABLED_DIR).listFiles() ?: return emptySet()
        return files.filter { it.isFile }
            .mapNotNull { it.name.trim().toIntOrNull() }
            .toSet()
    }

    /** 关闭标记的落盘路径；编号统一按两位数字命名，便于人工辨认。 */
    fun disabledMarker(dir: File, number: Int): File =
        File(File(dir, DISABLED_DIR), "%02d".format(number))

    /**
     * `.disabled/` 里登记的被关闭的按需片段（`agent/plan-mode.md` 这类无数字身份的片段）：
     * 文件名就是片段文件名（`plan-mode.md`）。
     */
    fun disabledFileNames(dir: File): Set<String> {
        val files = File(dir, DISABLED_DIR).listFiles() ?: return emptySet()
        return files.filter { it.isFile }
            .map { it.name }
            .filter { it.toIntOrNull() == null }
            .toSet()
    }

    /** 按需片段的关闭标记：用片段文件名（去掉 `agent/` 前缀）当键。 */
    fun namedDisabledMarker(dir: File, name: String): File =
        File(File(dir, DISABLED_DIR), name.substringAfterLast('/'))

    /** 写/删按需片段的关闭标记。 */
    fun setNamedDisabled(dir: File, name: String, disabled: Boolean): Boolean = try {
        val marker = namedDisabledMarker(dir, name)
        if (disabled) {
            marker.parentFile?.mkdirs()
            marker.writeText("")
        } else if (marker.isFile) {
            marker.delete()
        }
        true
    } catch (e: Exception) {
        false
    }

    /** 写/删关闭标记；目录不存在时按需创建。 */
    fun setDisabled(dir: File, number: Int, disabled: Boolean): Boolean = try {
        val marker = disabledMarker(dir, number)
        if (disabled) {
            marker.parentFile?.mkdirs()
            marker.writeText("")
        } else if (marker.isFile) {
            marker.delete()
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 合并静态基线：以 [builtinNumbers] 顺序为骨架，[custom] 中命中这些数字的作为覆盖、
     * 其余作为新增片段，整体按数字升序排列。
     *
     * @return 有序的 (数字, 覆盖文件或 null)；null 表示该数字没有自定义文件、用内置默认内容。
     */
    fun mergeStatic(
        builtinNumbers: List<Int>,
        custom: List<Pair<Int, File>>
    ): List<Pair<Int, File?>> {
        val customByNumber = custom.toMap()
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtinNumbers)
            custom.forEach { if (it.first !in this) add(it.first) }
        }
        return numbers.sorted().map { it to customByNumber[it] }
    }
}