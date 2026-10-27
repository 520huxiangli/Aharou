package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object MemoryFileStorage {
    val lock = Any()

    /**
     * 内容版本：全文的 SHA-256 取前 12 位十六进制。
     *
     * 用内容哈希而不是 mtime / 递增号：版本不落盘、不加数据库字段，老条目天然就有版本；
     * 内容没变版本就不变，跨设备同步、拷贝、重写同样的正文都不会产生假冲突。
     */
    fun versionOf(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 乐观锁校验。返回 null 表示放行。
     *
     * [expectedVersion] 三种语义：null=不校验（后台刚读过、或人在设置页手工保存）；
     * [MemorySource.VERSION_NEW]=要求文件**不存在**；其它=必须与当前版本相等。
     */
    fun conflictOf(
        name: String,
        expectedVersion: String?,
        currentText: String?
    ): MemoryWriteResult.VersionConflict? {
        if (expectedVersion == null) return null
        val currentVersion = currentText?.let(::versionOf)
        if (expectedVersion == MemorySource.VERSION_NEW) {
            return currentVersion?.let {
                MemoryWriteResult.VersionConflict(name, it, bodyOf(currentText))
            }
        }
        if (currentVersion == null) {
            return MemoryWriteResult.VersionConflict(name, "", "", missing = true)
        }
        if (currentVersion != expectedVersion) {
            return MemoryWriteResult.VersionConflict(name, currentVersion, bodyOf(currentText))
        }
        return null
    }

    /** 记忆文件里的正文（剥掉 frontmatter）：冲突时回给模型的就是它能直接合并的那部分。 */
    private fun bodyOf(text: String?): String {
        if (text == null) return ""
        val body = runCatching {
            Frontmatter.splitAndParse(text, "MemoryFileStorage", "解析记忆失败").second
        }.getOrNull() ?: return text.trim()
        return body.trim()
    }

    /**
     * 写盘。给了 [expectedVersion] 时先在同一把锁内校验版本，不一致就不写。
     *
     * 归档、恢复归档这类「内容原样搬运」的写入不传版本，走无条件分支。
     */
    fun write(file: File, content: String, expectedVersion: String? = null): Boolean = synchronized(lock) {
        if (expectedVersion != null) {
            val current = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
            if (conflictOf(file.nameWithoutExtension, expectedVersion, current) != null) {
                FileLogger.w("MemoryFileStorage", "记忆写入被版本校验拦下: ${file.name}")
                return@synchronized false
            }
        }
        var temporary: File? = null
        try {
            val parent = file.absoluteFile.parentFile ?: return@synchronized false
            if (!parent.isDirectory && !parent.mkdirs()) return@synchronized false
            if (file.exists() && !file.isFile) return@synchronized false
            temporary = File.createTempFile(".memory-", ".tmp", parent)
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "记忆写入失败: ${file.name}", e)
            false
        } finally {
            temporary?.delete()
        }
    }

    fun merge(
        group: List<Memory>, name: String, description: String, content: String
    ): Boolean = synchronized(lock) {
        if (group.size < 2 || group.any { it.pending || it.file == null }) return@synchronized false
        val first = group.first()
        if (group.any { it.scope != first.scope || it.type != first.type || it.expiresAt != first.expiresAt }) {
            return@synchronized false
        }
        val root = first.file?.parentFile ?: return@synchronized false
        val files = group.map { it.file!! }
        if (files.any { it.parentFile != root } || files.distinct().size != files.size) return@synchronized false
        // 组员在读取后是否被改过：重新解析（含版本）与调用方手里的比对，不一致就整簇放弃。
        if (group.any { MemoryParser.parse(it.file!!, it.scope) != it }) return@synchronized false
        val target = MemorySource.resolveMemoryFile(root, name)
        val outsideGroup = root.listFiles()?.any {
            it !in files && (it.name.equals(target.name, ignoreCase = true) ||
                (it.isFile && it.extension == "md" &&
                    MemoryParser.parse(it, first.scope)?.name.equals(name, ignoreCase = true)))
        } ?: return@synchronized false
        if (outsideGroup) return@synchronized false
        for (file in files) {
            if (MemorySource.archiveExistingFile(root, file.nameWithoutExtension) == null) return@synchronized false
        }
        val payload = MemoryParser.format(
            name, description, content, first.type, group.flatMap { it.keywords }.distinct(), first.expiresAt
        )
        if (!write(target, payload)) return@synchronized false
        var deleted = true
        files.filterNot { it == target }.forEach { if (!it.delete()) deleted = false }
        deleted
    }

    fun save(
        root: File,
        scope: MemoryScope,
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        expiresAt: Long?,
        pending: Boolean?,
        keywords: List<String>?
    ): Boolean = saveChecked(
        root, scope, name, description, content, type, expiresAt, pending, keywords,
        expectedVersion = null
    ) == MemoryWriteResult.Success

    /**
     * 带乐观锁的保存（新建或覆盖）。
     *
     * 读当前文件、校验版本、归档旧版、写新内容全在 [lock] 内完成，中间没有任何插入点：
     * 「校验通过」与「写下去」之间别人不可能再改一次。
     */
    fun saveChecked(
        root: File,
        scope: MemoryScope,
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        expiresAt: Long?,
        pending: Boolean?,
        keywords: List<String>?,
        expectedVersion: String?
    ): MemoryWriteResult = synchronized(lock) {
        try {
            val file = MemorySource.resolveMemoryFile(root, name)
            val currentText = if (file.isFile) file.readText() else null
            conflictOf(name, expectedVersion, currentText)?.let { return@synchronized it }
            val existing = if (currentText != null) {
                MemoryParser.parse(file, scope)
                    ?: return@synchronized MemoryWriteResult.Failure("SAVE_FAILED", "已有记忆文件解析失败，未覆盖")
            } else {
                null
            }
            val payload = MemoryParser.format(
                MemorySource.sanitizeName(name), description, content,
                type ?: existing?.type,
                keywords ?: existing?.keywords,
                expiresAt ?: existing?.expiresAt,
                pending ?: existing?.pending ?: false
            )
            if (currentText == payload) return@synchronized MemoryWriteResult.Success
            if (currentText != null && MemorySource.archiveExistingFile(root, name) == null) {
                return@synchronized MemoryWriteResult.Failure("SAVE_ARCHIVE_FAILED", "归档旧版记忆失败，原文未修改")
            }
            if (write(file, payload)) MemoryWriteResult.Success
            else MemoryWriteResult.Failure("SAVE_WRITE_FAILED", "写入记忆文件失败，原文未修改")
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "保存记忆失败: $name", e)
            MemoryWriteResult.Failure("SAVE_FAILED", "保存记忆失败: ${e.message}")
        }
    }
}
