package com.aharou.feature.backup.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class BackupRestoreChanges(val directory: File) {
    private val undo = ArrayList<suspend () -> Unit>()
    private val createdDirectories = LinkedHashMap<File, Any?>()
    private val refresh = ArrayList<suspend () -> Unit>()
    private val manifest = File(directory, "rollback.jsonl")
    private val written = HashMap<File, FileStamp?>()

    fun afterRollback(action: suspend () -> Unit) { refresh.add(action) }

    private fun note(target: File, original: File?) {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("target", kotlinx.serialization.json.JsonPrimitive(target.path))
            put("original", original?.let { kotlinx.serialization.json.JsonPrimitive(it.name) }
                ?: kotlinx.serialization.json.JsonNull)
        }
        FileOutputStream(manifest, true).use { output ->
            output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
    var rollbackFailed: Boolean = false
        private set

    fun record(action: suspend () -> Unit) { undo.add(action) }

    suspend fun rollback(cause: Throwable) = withContext(NonCancellable) {
        undo.asReversed().forEach { action ->
            try { action() } catch (failure: Throwable) {
                rollbackFailed = true
                cause.addSuppressed(failure)
            }
        }
        createdDirectories.entries.toList().asReversed().forEach { (dir, identity) ->
            try {
                val sameDirectory = dir.exists() && !Files.isSymbolicLink(dir.toPath()) && identity != null &&
                    Files.readAttributes(dir.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() == identity
                if (dir.exists() && (!sameDirectory || dir.list()?.isNotEmpty() != false || !dir.delete())) {
                    throw IOException("Restore directory rollback conflict: ${dir.path}")
                }
            } catch (failure: Throwable) {
                rollbackFailed = true
                cause.addSuppressed(failure)
            }
        }
        if (cause.suppressed.isNotEmpty()) rollbackFailed = true
        refresh.forEach { action ->
            try { action() } catch (failure: Throwable) {
                rollbackFailed = true
                cause.addSuppressed(failure)
            }
        }
    }

    private fun checkedTarget(target: File): File {
        val rawRoot = requireNotNull(directory.parentFile).absoluteFile.toPath().normalize()
        val canonicalRoot = rawRoot.toFile().canonicalFile.toPath()
        val candidate = target.absoluteFile.toPath().normalize()
        val root = when {
            candidate.startsWith(rawRoot) -> rawRoot
            candidate.startsWith(canonicalRoot) -> canonicalRoot
            else -> throw IOException("Restore target outside application data")
        }
        var path = candidate
        while (path != root) {
            if (Files.isSymbolicLink(path)) throw IOException("Restore target contains a symbolic link")
            path = requireNotNull(path.parent)
        }
        return canonicalRoot.resolve(root.relativize(candidate)).toFile()
    }

    fun checkTarget(target: File) { checkedTarget(target) }

    fun ensureDirectory(directory: File) {
        val target = checkedTarget(directory)
        if (target.isDirectory) return
        if (target.exists()) throw IOException("Restore directory conflicts with a file")
        target.parentFile?.let { ensureDirectory(it) }
        if (!target.mkdir()) throw IOException("Cannot create restore directory")
        createdDirectories[target] = Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        note(target, null)
    }

    suspend fun write(target: File, input: InputStream) {
        currentCoroutineContext().ensureActive()
        val boundary = requireNotNull(directory.parentFile).canonicalFile.toPath()
        val canonical = checkedTarget(target)
        if (canonical.toPath() == boundary) throw IOException("Restore target is application data root")
        val parent = requireNotNull(canonical.parentFile)
        ensureDirectory(parent)
        if (canonical.exists() && !canonical.isFile) throw IOException("Restore target is not a file")
        val oldDigest = stampOrNull(canonical)
        val old = if (canonical.exists()) File.createTempFile("old-", ".tmp", directory).also {
            Files.copy(canonical.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        } else null
        note(canonical, old)
        val pending = File.createTempFile(".backup-restore-", ".tmp", parent)
        try {
            FileOutputStream(pending).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            if (canonical.isFile) {
                val permissions = Files.getPosixFilePermissions(canonical.toPath())
                Files.setPosixFilePermissions(pending.toPath(), permissions)
            }
            val expected = stampOrNull(pending)
            withContext(NonCancellable) {
                if (stampOrNull(canonical) != oldDigest) throw IOException("Restore target changed concurrently")
                Files.move(pending.toPath(), canonical.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                written[canonical] = expected
                record {
                    var rollbackPath = canonical.toPath()
                    while (rollbackPath != boundary) {
                        if (Files.isSymbolicLink(rollbackPath)) throw IOException("Restore rollback symbolic link conflict")
                        rollbackPath = requireNotNull(rollbackPath.parent)
                    }
                    if (stampOrNull(canonical) != written[canonical]) throw IOException("Restore rollback conflict; original retained")
                    if (old == null) {
                        if (!canonical.delete() && canonical.exists()) throw IOException("Cannot remove restored file")
                    } else {
                        val rollback = File.createTempFile(".backup-rollback-", ".tmp", parent)
                        try {
                            Files.copy(old.toPath(), rollback.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                            Files.move(rollback.toPath(), canonical.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { rollback.delete() }
                    }
                    written[canonical] = stampOrNull(canonical)
                }
            }
        } finally { pending.delete() }
        currentCoroutineContext().ensureActive()
    }

    suspend fun write(target: File, source: File) = FileInputStream(source).use { write(target, it) }

    private data class FileStamp(
        val digest: String?, val identity: Any?, val modified: java.nio.file.attribute.FileTime,
        val permissions: Set<java.nio.file.attribute.PosixFilePermission>
    )

    private fun stampOrNull(file: File): FileStamp? {
        if (!file.exists()) return null
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        return FileStamp(digestOrNull(file), attributes.fileKey(), attributes.lastModifiedTime(), Files.getPosixFilePermissions(file.toPath()))
    }

    companion object {
        fun digestOrNull(file: File): String? {
            if (!file.exists()) return null
            if (!file.isFile) throw IOException("Restore target is not a regular file")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
