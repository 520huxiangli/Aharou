package com.aharou.feature.agent.domain.memory

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID

internal data class MemorySyncFile(val bytes: ByteArray, val hash: String)

internal class MemorySyncFiles {
    fun checked(root: File, relative: String = ""): File {
        val base = root.toPath().toAbsolutePath().normalize()
        require(!File(relative).isAbsolute && '\\' !in relative) { "unsafe_path" }
        require(relative.split('/').none { it == ".." || it == "." }) { "unsafe_path" }
        val path = base.resolve(relative).normalize()
        require(path.startsWith(base)) { "unsafe_path" }
        var ancestor = path.root
        for (part in path) {
            ancestor = ancestor.resolve(part)
            if (Files.isSymbolicLink(ancestor)) throw IOException("symbolic_link")
            if (ancestor != path && Files.exists(ancestor, NOFOLLOW_LINKS) &&
                !Files.isDirectory(ancestor, NOFOLLOW_LINKS)
            ) throw IOException("invalid_parent")
        }
        return path.toFile()
    }

    fun directory(root: File): File {
        checked(root)
        Files.createDirectories(root.toPath())
        checked(root)
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) throw IOException("invalid_directory")
        return root
    }

    fun present(file: File): Boolean {
        checked(file)
        return Files.exists(file.toPath(), NOFOLLOW_LINKS)
    }

    fun read(file: File): MemorySyncFile? {
        checked(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("invalid_file")
        val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { it.readBytes() }
        checked(file)
        return MemorySyncFile(bytes, hash(bytes))
    }

    fun snapshot(root: File): Map<String, MemorySyncFile> {
        checked(root)
        if (!present(root)) return emptyMap()
        val result = linkedMapOf<String, MemorySyncFile>()
        fun visit(dir: File, prefix: String) {
            checked(root, prefix.trimEnd('/'))
            val children = dir.listFiles() ?: throw IOException("unreadable_directory")
            children.sortedBy { it.name }.forEach { child ->
                if (ignored(child.name)) return@forEach
                val relative = prefix + child.name
                checked(root, relative)
                when {
                    Files.isDirectory(child.toPath(), NOFOLLOW_LINKS) -> visit(child, "$relative/")
                    isMemoryFile(child.name) -> result[relative] = read(child) ?: throw IOException("file_changed")
                }
            }
        }
        visit(root, "")
        return result
    }

    fun archive(root: File, relative: String, version: MemorySyncFile?): File {
        val path = "$relative.${version?.hash ?: "deleted"}.snapshot"
        val target = checked(root, path)
        val bytes = version?.bytes ?: byteArrayOf()
        val existing = read(target)
        if (existing == null) atomicWrite(target, bytes)
        if (read(target)?.hash != hash(bytes)) throw IOException("archive_failed")
        return target
    }

    fun replace(
        target: File,
        expected: String?,
        replacement: MemorySyncFile?,
        archiveRoot: File,
        archivePath: String,
    ) = synchronized(MemoryFileStorage.lock) {
        val previous = read(target)
        if (previous?.hash != expected) throw IOException("file_changed")
        if (previous?.hash == replacement?.hash) return@synchronized
        if (previous != null) archive(archiveRoot, archivePath, previous)
        if (read(target)?.hash != expected) throw IOException("file_changed")
        if (replacement == null) {
            checked(target)
            Files.delete(target.toPath())
        } else {
            atomicWrite(target, replacement.bytes) {
                if (read(target)?.hash != expected) throw IOException("file_changed")
            }
        }
        if (read(target)?.hash != replacement?.hash) throw IOException("file_changed")
    }

    fun atomicWrite(target: File, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        checked(target)
        directory(target.parentFile ?: throw IOException("invalid_parent"))
        val temporary = checked(target.parentFile, ".sync-${UUID.randomUUID()}.tmp")
        try {
            Files.createFile(temporary.toPath())
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            checked(target)
            checked(temporary)
            beforeMove()
            checked(target)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            checked(temporary)
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun removeStaging(root: File) {
        checked(root)
        if (!present(root)) return
        fun remove(file: File) {
            checked(file)
            if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
                (file.listFiles() ?: throw IOException("unreadable_directory")).forEach(::remove)
            }
            Files.delete(file.toPath())
        }
        remove(root)
    }

    companion object {
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun ignored(name: String): Boolean = name.startsWith('.') || name.endsWith(".tmp") ||
            name in setOf("projects", "credentials", "conflicts", "memory-sync-state", "memory-repo")

        fun isMemoryFile(name: String): Boolean = !ignored(name) &&
            (name.endsWith(".md") || name.contains(".md.bak-") ||
                name in setOf("facts.json", "mindstream.jsonl"))
    }
}
