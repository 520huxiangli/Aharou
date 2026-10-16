package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object MemoryFileStorage {
    val lock = Any()

    fun write(file: File, content: String): Boolean = synchronized(lock) {
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
    ): Boolean = synchronized(lock) {
        try {
            val file = MemorySource.resolveMemoryFile(root, name)
            val existing = if (file.exists()) MemoryParser.parse(file, scope) ?: return@synchronized false else null
            val payload = MemoryParser.format(
                MemorySource.sanitizeName(name), description, content,
                type ?: existing?.type,
                keywords ?: existing?.keywords,
                expiresAt ?: existing?.expiresAt,
                pending ?: existing?.pending ?: false
            )
            if (file.isFile && file.readText() == payload) return@synchronized true
            if (file.exists() && MemorySource.archiveExistingFile(root, name) == null) return@synchronized false
            write(file, payload)
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "保存记忆失败: $name", e)
            false
        }
    }
}
