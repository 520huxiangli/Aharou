package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 版本校验失败：这条记忆在调用方读取后被别处改过，本次未写盘。
     *
     * 带上最新的版本与正文，调用方才能在同一轮里改用最新正文重新构造编辑并重试，
     * 而不是只得到一句「冲突了」再去 read 一次。
     */
    data class VersionConflict(
        val name: String,
        val currentVersion: String,
        val currentContent: String
    ) : MemoryEditResult

    /** 编辑校验或写盘失败。 */
    data class Error(val code: String, val message: String) : MemoryEditResult
}

/**
 * 带乐观锁的写入结果。
 *
 * 把「版本冲突」从「写盘失败」里单独拎出来：前者是并发改动的正常后果，要带着最新正文
 * 回到调用方（模型）让它合并后重试；后者是磁盘/权限问题，重试无用。
 */
sealed interface MemoryWriteResult {
    data object Success : MemoryWriteResult

    /** 版本校验失败（含「要求不存在却已存在」与「要求已存在却已消失」）。 */
    data class VersionConflict(
        val name: String,
        val currentVersion: String,
        val currentContent: String,
        /** 校验时文件已不存在（被别处删掉）：调用方应改用新建语义重试。 */
        val missing: Boolean = false
    ) : MemoryWriteResult

    data class Failure(val code: String, val message: String) : MemoryWriteResult
}

/** 一句话说明失败原因，供日志与流水用；刻意不带正文，避免把记忆内容拷进日志。 */
internal fun MemoryWriteResult.failureSummary(): String = when (this) {
    is MemoryWriteResult.Success -> "成功"
    is MemoryWriteResult.VersionConflict ->
        if (missing) "版本冲突：目标已被删除" else "版本冲突：当前版本 $currentVersion"
    is MemoryWriteResult.Failure -> "$code：$message"
}

/** 归档目录里的一条旧版本（覆盖 / 合并前留的底）。 */
data class MemoryArchiveInfo(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/**
 * 记忆数据源抽象接口，支持全局和项目级。
 */
interface MemorySource {
    /** 扫描并返回该数据源下当前所有合法的 Memory。 */
    fun listMemories(): List<Memory>

    /** 读取指定 memory 的完整指令正文；不存在或解析失败时返回 null。 */
    fun loadContent(name: String): String?

    /** 保存一条记忆（创建或覆盖） */
    fun saveMemory(name: String, description: String, content: String, type: MemoryType? = null, expiresAt: Long? = null, pending: Boolean? = null, keywords: List<String>? = null): Boolean

    /**
     * 带乐观锁的保存：语义同 [saveMemory]，但把「版本冲突」与「写盘失败」分开回报。
     *
     * [expectedVersion] 三种语义：[MemorySource.VERSION_NEW] 要求文件不存在；具体版本号要求
     * 与当前版本相等；null 不校验（人手工编辑、后台刚读完就写这两类调用方走这条）。
     * 默认实现只转发 [saveMemory]，自定义数据源可以覆写成真正的版本校验。
     */
    fun writeMemory(
        name: String,
        description: String,
        content: String,
        type: MemoryType? = null,
        expiresAt: Long? = null,
        pending: Boolean? = null,
        keywords: List<String>? = null,
        expectedVersion: String? = null
    ): MemoryWriteResult =
        if (saveMemory(name, description, content, type, expiresAt, pending, keywords)) {
            MemoryWriteResult.Success
        } else {
            MemoryWriteResult.Failure("SAVE_FAILED", "写入记忆文件失败，原文未修改")
        }

    /**
     * 对已有记忆的正文做局部编辑（old_string/new_string 精确匹配），语义与 editFile 一致。
     * 默认实现基于 [listMemories] 定位记忆文件，在内存中按序应用所有编辑；
     * 任一编辑校验失败则整批不写盘（原子），成功后用原 name/description 重新格式化写回。
     *
     * [expectedVersion] 非空时先校验版本：读取与校验同在 [MemoryFileStorage.lock] 内，
     * 所以比对用的就是「这一刻」的版本。
     */
    fun editMemory(
        name: String,
        edits: List<MemoryEdit>,
        expectedVersion: String? = null
    ): MemoryEditResult = synchronized(MemoryFileStorage.lock) {
        val memory = listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MemoryEditResult.NotFound(name)
        val file = memory.file ?: return MemoryEditResult.NotFound(name)
        if (expectedVersion != null && expectedVersion != memory.version) {
            return MemoryEditResult.VersionConflict(memory.name, memory.version, memory.content)
        }
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
            val directory = file.parentFile ?: return MemoryEditResult.NotFound(name)
            if (archiveExistingFile(directory, file.nameWithoutExtension) == null) {
                return MemoryEditResult.Error("EDIT_ARCHIVE_FAILED", "归档旧版记忆失败，原文未修改")
            }
            val payload = MemoryParser.format(
                memory.name, memory.description, content, memory.type, memory.keywords,
                memory.expiresAt, memory.pending
            )
            if (MemoryFileStorage.write(file, payload)) MemoryEditResult.Success
            else MemoryEditResult.Error("EDIT_WRITE_FAILED", "写入记忆文件失败，原文未修改")
        } catch (e: Exception) {
            FileLogger.e("MemorySource", "Failed to edit memory: $name", e)
            MemoryEditResult.Error("EDIT_WRITE_FAILED", "写入记忆文件失败: ${e.message}")
        }
    }

    fun deleteMemory(name: String): Boolean

    /**
     * 该作用域的记忆根目录；null 表示当前不可用（未选工作区等）。
     * 设置页浏览归档、编辑器定位写盘目标都要用它。
     */
    val root: File? get() = null

    /** 清理归档目录，返回删除的文件数。默认不处理，由具体数据源覆写。 */
    fun pruneArchive(keepDays: Int = 30, keepFiles: Int = 300): Int = 0

    /** 归档目录下的旧版本，最新在前。 */
    fun listArchive(): List<MemoryArchiveInfo> {
        val dir = root?.let { File(it, ARCHIVE_DIR) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .map { MemoryArchiveInfo(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
    }

    /** 读一条归档文件的原文；文件名含路径分隔符时拒绝（防路径穿越）。 */
    fun readArchive(fileName: String): String? {
        if (fileName.contains('/') || fileName.contains("..")) return null
        val dir = root?.let { File(it, ARCHIVE_DIR) } ?: return null
        val file = File(dir, fileName)
        return file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    }

    /**
     * 把一条归档恢复成正式条目：按归档里的 frontmatter 还原名称与摘要，写回顶层。
     * 同名条目已存在时由 [saveMemory] 先把现有的归档掉——恢复不该悄悄抹掉新版本。
     */
    fun restoreArchive(fileName: String): Boolean = synchronized(MemoryFileStorage.lock) {
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return false
        val directory = root ?: return false
        val file = File(File(directory, ARCHIVE_DIR), fileName)
        if (!file.isFile) return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        val (frontmatter, _) = Frontmatter.splitAndParse(text, "MemorySource", "解析归档失败")
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: fileName.removeSuffix(".md").replace(Regex("-\\d{8}-\\d{6}(?:-\\d+)?$"), "")
        val target = resolveMemoryFile(directory, name)
        if (target.exists() && archiveExistingFile(directory, name) == null) return false
        if (!MemoryFileStorage.write(target, text)) return false
        return file.delete()
    }

    /**
     * 把一条「没能写成本体」的内容存进归档目录（记忆根目录下的 `archive/`）。
     *
     * 记忆是「一个名字一条」的模型，同名再写就会覆盖旧版，而旧值本身常常是有用信息
     * （判断演变方向、回滚误改都要靠它）。所以凡是要被覆盖或被丢弃的版本，都往这里留一份。
     * 归档是子目录，[listMemories] 只扫顶层 `.md`，因此归档不会被注入提示词、也不会被
     * search 命中——要取回得显式读文件。
     */
    fun archiveContent(
        name: String, description: String, content: String, type: MemoryType? = null,
        keywords: List<String> = emptyList(), expiresAt: Long? = null, pending: Boolean = false
    ): Boolean

    companion object {
        /** 版本校验的「要求文件不存在」语义：新建一条记忆。 */
        const val VERSION_NEW = "new"

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

        /** 归档子目录名。放在记忆根目录下，天然被 [listMemories] 的顶层扫描排除。 */
        const val ARCHIVE_DIR = "archive"

        /**
         * 归档清理：archive/ 以前只写不删，实测项目级已堆到 1250 个文件 / 8.4MB。
         *
         * 只删「既超过 [keepDays] 天、又超出最近 [keepFiles] 个」的文件：归档是后悔药，
         * 把近期的删了等于没有后悔药；两个条件同时满足才动。
         */
        fun pruneArchive(root: File, keepDays: Int = 30, keepFiles: Int = 300): Int =
            synchronized(MemoryFileStorage.lock) {
                val dir = archiveDir(root, mkdirs = false) ?: return@synchronized 0
                val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?: return@synchronized 0
                val cutoff = System.currentTimeMillis() - keepDays * 24L * 60L * 60L * 1_000L
                var removed = 0
                files.forEachIndexed { index, file ->
                    if (index >= keepFiles && file.lastModified() < cutoff && file.delete()) removed++
                }
                removed
            }

        /** 归档目录；[mkdirs] 为 true 时尝试创建，创建失败返回 null。 */
        private fun archiveDir(root: File, mkdirs: Boolean = false): File? {
            val dir = File(root, ARCHIVE_DIR)
            if (mkdirs && !dir.isDirectory && !dir.mkdirs()) return null
            return dir
        }

        /**
         * 为 [name] 在归档目录里挑一个不重名的目标文件（带时间戳）。
         * 同一秒内重复归档时追加序号，绝不覆盖已有归档。
         */
        fun uniqueArchiveFile(root: File, name: String): File? {
            val dir = archiveDir(root, mkdirs = true) ?: return null
            val base = sanitizeName(name)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var candidate = File(dir, "$base-$stamp.md")
            var seq = 1
            while (candidate.exists()) {
                candidate = File(dir, "$base-$stamp-$seq.md")
                seq++
            }
            return candidate
        }

        /** 覆盖写之前把 [name] 现有文件原样留一份到归档目录；没有现成文件时返回 null。 */
        fun archiveExistingFile(root: File, name: String): File? = synchronized(MemoryFileStorage.lock) {
            val existing = resolveMemoryFile(root, name)
            if (!existing.isFile) return null
            val target = uniqueArchiveFile(root, name) ?: return null
            return try {
                if (MemoryFileStorage.write(target, existing.readText())) target else null
            } catch (e: Exception) {
                FileLogger.w("MemorySource", "归档旧版记忆失败: $name", e)
                null
            }
        }

        /** 把任意内容按记忆格式写进归档目录；成功返回归档文件。 */
        fun writeArchiveFile(
            root: File,
            name: String,
            description: String,
            content: String,
            tag: String,
            type: MemoryType? = null,
            keywords: List<String> = emptyList(),
            expiresAt: Long? = null,
            pending: Boolean = false
        ): File? = synchronized(MemoryFileStorage.lock) {
            val target = uniqueArchiveFile(root, name) ?: return null
            return try {
                val payload = MemoryParser.format(sanitizeName(name), description, content, type, keywords, expiresAt, pending)
                if (MemoryFileStorage.write(target, payload)) target else null
            } catch (e: Exception) {
                FileLogger.w(tag, "写入归档记忆失败: $name", e)
                null
            }
        }
    }
}
