package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import java.io.File

/** 单个编辑项，语义与 editFile 的 edits 一致。 */
data class MemoryEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean
)

/** 记忆编辑操作的结果。 */
sealed interface MemoryEditResult {
    /** 编辑成功并已写盘。 */
    data object Success : MemoryEditResult

    /** 指定名称的记忆不存在。 */
    data class NotFound(val name: String) : MemoryEditResult

    /** 编辑校验或写盘失败。 */
    data class Error(val code: String, val message: String) : MemoryEditResult
}

/**
 * 记忆数据源抽象接口，支持全局和项目级。
 */
interface MemorySource {
    /** 扫描并返回该数据源下当前所有合法的 Memory。 */
    fun listMemories(): List<Memory>

    /** 读取指定 memory 的完整指令正文；不存在或解析失败时返回 null。 */
    fun loadContent(name: String): String?

    /** 保存一条记忆（创建或覆盖） */
    fun saveMemory(name: String, description: String, content: String): Boolean

    /**
     * 对已有记忆的正文做局部编辑（old_string/new_string 精确匹配），语义与 editFile 一致。
     * 默认实现基于 [listMemories] 定位记忆文件，在内存中按序应用所有编辑；
     * 任一编辑校验失败则整批不写盘（原子），成功后用原 name/description 重新格式化写回。
     */
    fun editMemory(name: String, edits: List<MemoryEdit>): MemoryEditResult {
        val memory = listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MemoryEditResult.NotFound(name)
        val file = memory.file ?: return MemoryEditResult.NotFound(name)
        if (edits.isEmpty()) {
            return MemoryEditResult.Error("EMPTY_EDITS", "edits 不能为空，请至少提供一个 {old_string,new_string} 编辑")
        }

        var content = memory.content
        edits.forEachIndexed { i, e ->
            if (e.oldString.isEmpty()) {
                return MemoryEditResult.Error("EMPTY_OLD_STRING", "第 ${i + 1} 个编辑的 old_string 不能为空")
            }
            if (e.oldString == e.newString) {
                return MemoryEditResult.Error("NO_OP", "第 ${i + 1} 个编辑的 old_string 与 new_string 相同，无需修改")
            }
            val occurrences = content.split(e.oldString).size - 1
            if (occurrences == 0) {
                return MemoryEditResult.Error(
                    "NO_MATCH",
                    "第 ${i + 1} 个编辑未在记忆「${memory.name}」正文中找到 old_string，请确认内容与 read 返回的正文完全一致（含缩进/换行）"
                )
            }
            if (occurrences > 1 && !e.replaceAll) {
                return MemoryEditResult.Error(
                    "MULTIPLE_MATCHES",
                    "第 ${i + 1} 个编辑的 old_string 在正文中匹配到 $occurrences 处，请提供更长的唯一上下文，或对该编辑设置 replace_all=true"
                )
            }
            content = if (e.replaceAll) content.replace(e.oldString, e.newString)
            else content.replaceFirst(e.oldString, e.newString)
        }

        return try {
            file.writeText(MemoryParser.format(memory.name, memory.description, content))
            MemoryEditResult.Success
        } catch (e: Exception) {
            FileLogger.e("MemorySource", "Failed to edit memory: $name", e)
            MemoryEditResult.Error("EDIT_WRITE_FAILED", "写入记忆文件失败: ${e.message}")
        }
    }

    fun deleteMemory(name: String): Boolean

    companion object {
        /**
         * 将模型传入的记忆名归一化为安全文件名片段。
         *
         * 记忆名来自大模型工具参数（自由文本），不经处理直接拼到 "$name.md" 会引入路径穿越
         * （如 "../x" 会写到 memoryRoot 之外）、含分隔符的非法文件名等问题。
         * 这里只保留「字母/数字/连字符/下划线」 Unicode 字符，其余替换为 '-'，并去首尾连字符；
         * 结果只含合法文件名字符、不含路径分隔符，且对同一输入确定性可逆，保证
         * 「写出去的文件名 ↔ MemoryParser.parse 回读的 name」一致。
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
            }.joinToString("").trim('-')
            // 全非法字符时回退一个占位名，避免空文件名
            return if (cleaned.isBlank()) "memory" else cleaned
        }

        /**
         * 解析 memory 名对应的磁盘文件，并校验解析后的 canonical 路径仍落在 [root] 之内，
         * 杜绝 sanitize 漏网导致的越界写入。
         */
        fun resolveMemoryFile(root: File, name: String): File {
            val safe = sanitizeName(name)
            return File(root, "$safe.md")
        }
    }
}
