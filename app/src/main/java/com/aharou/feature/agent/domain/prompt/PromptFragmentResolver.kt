package com.aharou.feature.agent.domain.prompt

import java.io.File

/**
 * `prompts.custom/` 的片段解析规则（纯文件系统逻辑，便于单测）。
 *
 * 目录里分两类片段：
 * - 顶层 `<两位数字>-<名称>.md`：数字即身份。数字命中内置静态片段即为覆盖，否则作为新增片段并入静态基线按数字排序。
 * - 其它文件（含 `agent/` 子目录）：按精确同名覆盖，无数字身份。
 */
internal object PromptFragmentResolver {

    /** 存在该文件即完全禁用内置提示词（仅主代理生效）。 */
    const val DISABLE_BUILTIN_FILE = ".no-builtin"

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