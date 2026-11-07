package com.aharou.feature.backup.data

import com.aharou.feature.backup.domain.AgentMessageDto
import com.aharou.feature.backup.domain.BackupMetadata
import com.aharou.feature.backup.domain.BackupSnapshot
import com.aharou.feature.backup.domain.ChatSessionDto
import com.aharou.feature.backup.domain.TodoItemDto
import com.aharou.feature.backup.domain.toMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

internal class BackupRestorePlan private constructor(
    val tarFile: File,
    val metadata: BackupMetadata
) {
    companion object {
        suspend fun prepare(source: TarSource, directory: File, json: Json, checkVersion: (Int) -> Unit): BackupRestorePlan {
            val file = File(directory, "validated.tar")
            source.copyDecompressedTo(file)
            currentCoroutineContext().ensureActive()
            require(file.length() >= 1024 && file.length() % 512L == 0L) { "Truncated backup tar" }
            RandomAccessFile(file, "r").use { input ->
                input.seek(file.length() - 1024)
                val tail = ByteArray(1024)
                input.readFully(tail)
                require(tail.all { it == 0.toByte() }) { "Backup tar end marker missing" }
            }
            var metadata: BackupMetadata? = null
            var legacy = false
            var modern = false
            TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val name = entry.name
                    require(entry.isCheckSumOK) { "Invalid backup tar checksum" }
                    val knownFile = name in setOf("metadata.json", "snapshot.json", "chatSessions.jsonl", "messages.jsonl", "todoItems.jsonl") ||
                        name.startsWith("workspaces/") || name.startsWith(GLOBAL_MEMORY_PREFIX) || name.startsWith(PROJECT_MEMORY_PREFIX)
                    if (knownFile) {
                        require(entry.isFile && !entry.isSymbolicLink && !entry.isLink) { "Backup entry is not a regular file" }
                        require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                            "Invalid backup entry path"
                        }
                    }
                    when (name) {
                        "metadata.json" -> {
                            require(metadata == null) { "Duplicate backup metadata" }
                            metadata = json.decodeFromString(BackupMetadata.serializer(), tar.readBytes().toString(Charsets.UTF_8))
                            modern = true
                        }
                        "snapshot.json" -> {
                            require(metadata == null) { "Duplicate backup metadata" }
                            metadata = json.decodeFromString(BackupSnapshot.serializer(), tar.readBytes().toString(Charsets.UTF_8)).toMetadata()
                            legacy = true
                        }
                        "chatSessions.jsonl" -> {
                            modern = true
                            readBackupJsonl(tar, json, ChatSessionDto.serializer()) {}
                        }
                        "messages.jsonl" -> {
                            modern = true
                            readBackupJsonl(tar, json, AgentMessageDto.serializer()) {}
                        }
                        "todoItems.jsonl" -> {
                            modern = true
                            readBackupJsonl(tar, json, TodoItemDto.serializer()) {}
                        }
                    }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (tar.read(buffer) < 0) break
                    }
                    entry = tar.nextEntry
                }
                file.inputStream().use { remainder ->
                    var skipped = 0L
                    while (skipped < tar.bytesRead) {
                        val count = remainder.skip(tar.bytesRead - skipped)
                        require(count > 0) { "Truncated backup tar" }
                        skipped += count
                    }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = remainder.read(buffer)
                        if (count < 0) break
                        require((0 until count).all { buffer[it] == 0.toByte() }) { "Unexpected data after backup tar end" }
                    }
                }
            }
            require(!(legacy && modern)) { "Mixed backup formats" }
            val meta = requireNotNull(metadata) { "Backup metadata missing" }
            checkVersion(meta.schemaVersion)
            return BackupRestorePlan(file, meta)
        }
    }
}

internal suspend fun <T> readBackupJsonl(
    input: InputStream,
    json: Json,
    serializer: KSerializer<T>,
    insert: suspend (List<T>) -> Unit
): Int {
    val buffer = ByteArray(64 * 1024)
    val line = ByteArrayOutputStream(16 * 1024)
    val batch = ArrayList<T>(500)
    var count = 0
    suspend fun acceptLine() {
        if (line.size() == 0) return
        batch.add(json.decodeFromString(serializer, line.toByteArray().toString(Charsets.UTF_8)))
        line.reset()
        if (batch.size == 500) {
            insert(batch.toList())
            count += batch.size
            batch.clear()
        }
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buffer)
        if (n < 0) break
        var start = 0
        for (i in 0 until n) {
            if (buffer[i] == '\n'.code.toByte()) {
                line.write(buffer, start, i - start)
                start = i + 1
                acceptLine()
            }
        }
        line.write(buffer, start, n - start)
    }
    acceptLine()
    if (batch.isNotEmpty()) {
        insert(batch.toList())
        count += batch.size
    }
    return count
}
